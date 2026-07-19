package com.zeropointsix.dobaosay.doubao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class DoubaoDeviceClient(
    private val endpoints: DoubaoEndpoints = DoubaoEndpoints(),
    private val deviceProfile: DoubaoDeviceProfile = DoubaoConstants.DEFAULT_DEVICE,
    private val userAgent: String = DoubaoConstants.USER_AGENT,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    suspend fun registerDevice(): DoubaoCredentials {
        val ids = generateDeviceIds()
        val header = buildRegisterHeader(ids)
        val body =
            buildJsonObject {
                put("magic_tag", "ss_app_log")
                put("header", header)
                put("_gen_time", System.currentTimeMillis())
            }
        val response =
            Request
                .Builder()
                .url("${endpoints.registerUrl}?${registerQuery(ids.cdid)}")
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
                .let { send(it) }

        if (response.statusCode !in 200..299) {
            throw DoubaoProtocolException("Device registration failed: HTTP ${response.statusCode}")
        }

        val root = parseJsonObject(response.body, "device registration")
        val deviceId = root.longString("device_id", "device_id_str")
        val installId = root.longString("install_id", "install_id_str")
        if (deviceId == null || deviceId == "0") {
            throw DoubaoProtocolException("Device registration failed: missing device_id")
        }

        return DoubaoCredentials(
            deviceId = deviceId,
            installId = installId,
            cdid = ids.cdid,
            openudid = ids.openudid,
            clientudid = ids.clientudid,
        )
    }

    suspend fun fetchAsrToken(
        deviceId: String,
        cdid: String = UUID.randomUUID().toString(),
    ): String {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        val body = "body=null"
        val response =
            Request
                .Builder()
                .url("${endpoints.settingsUrl}?${settingsQuery(deviceId, cdid)}")
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("x-ss-stub", md5Hex(body))
                .post(body.toRequestBody(formMediaType))
                .build()
                .let { send(it) }

        if (response.statusCode !in 200..299) {
            throw DoubaoProtocolException("Failed to get ASR token: HTTP ${response.statusCode}")
        }

        val root = parseJsonObject(response.body, "settings")
        val token =
            root["data"]
                ?.jsonObjectOrNull()
                ?.get("settings")
                ?.jsonObjectOrNull()
                ?.get("asr_config")
                ?.jsonObjectOrNull()
                ?.get("app_key")
                ?.jsonPrimitive
                ?.contentOrNull

        if (token.isNullOrBlank()) {
            throw DoubaoProtocolException("Failed to get ASR token: app_key absent")
        }
        return token
    }

    suspend fun ensureCredentials(config: DoubaoProviderConfig): DoubaoCredentials {
        val explicitDeviceId = config.deviceId
        val explicitToken = config.token
        val seed = config.credentials
        val credentials =
            when {
                explicitDeviceId != null -> {
                    seed?.copy(deviceId = explicitDeviceId)
                        ?: DoubaoCredentials(
                            deviceId = explicitDeviceId,
                            installId = null,
                            cdid = UUID.randomUUID().toString(),
                            openudid = randomHex(8),
                            clientudid = UUID.randomUUID().toString(),
                        )
                }

                seed != null -> {
                    seed
                }

                else -> {
                    registerDevice()
                }
            }

        if (!explicitToken.isNullOrBlank()) return credentials.withToken(explicitToken)
        if (!credentials.token.isNullOrBlank()) return credentials

        return credentials.withToken(fetchAsrToken(credentials.deviceId, credentials.cdid))
    }

    private suspend fun send(request: Request): TextResponse =
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                TextResponse(
                    statusCode = response.code,
                    body = response.body.string(),
                )
            }
        }

    private fun buildRegisterHeader(ids: GeneratedDeviceIds): JsonObject =
        buildJsonObject {
            put("device_id", 0)
            put("install_id", 0)
            put("aid", DoubaoConstants.AID)
            put("app_name", DoubaoConstants.APP_NAME)
            put("version_code", DoubaoConstants.VERSION_CODE)
            put("version_name", DoubaoConstants.VERSION_NAME)
            put("manifest_version_code", DoubaoConstants.VERSION_CODE)
            put("update_version_code", DoubaoConstants.VERSION_CODE)
            put("channel", DoubaoConstants.CHANNEL)
            put("package", DoubaoConstants.PACKAGE_NAME)
            put("device_platform", deviceProfile.devicePlatform)
            put("os", deviceProfile.os)
            put("os_api", deviceProfile.osApi)
            put("os_version", deviceProfile.osVersion)
            put("device_type", deviceProfile.deviceType)
            put("device_brand", deviceProfile.deviceBrand)
            put("device_model", deviceProfile.deviceModel)
            put("resolution", deviceProfile.resolution)
            put("dpi", deviceProfile.dpi)
            put("language", deviceProfile.language)
            put("timezone", deviceProfile.timezone)
            put("access", deviceProfile.access)
            put("rom", deviceProfile.rom)
            put("rom_version", deviceProfile.romVersion)
            put("cdid", ids.cdid)
            put("openudid", ids.openudid)
            put("clientudid", ids.clientudid)
            put("region", "CN")
            put("tz_name", "Asia/Shanghai")
            put("tz_offset", 28800)
            put("sim_region", "cn")
            put("carrier_region", "cn")
            put("cpu_abi", "arm64-v8a")
            put("build_serial", "unknown")
            put("not_request_sender", 0)
            put("sig_hash", "")
            put("google_aid", "")
            put("mc", "")
            put("serial_number", "")
        }

    private fun registerQuery(cdid: String): String =
        query(
            mapOf(
                "device_platform" to deviceProfile.devicePlatform,
                "os" to deviceProfile.os,
                "resolution" to deviceProfile.resolution,
                "dpi" to deviceProfile.dpi,
                "device_type" to deviceProfile.deviceType,
                "device_brand" to deviceProfile.deviceBrand,
                "language" to deviceProfile.language,
                "os_api" to deviceProfile.osApi,
                "os_version" to deviceProfile.osVersion,
                "channel" to DoubaoConstants.CHANNEL,
                "aid" to DoubaoConstants.AID.toString(),
                "app_name" to DoubaoConstants.APP_NAME,
                "version_code" to DoubaoConstants.VERSION_CODE.toString(),
                "version_name" to DoubaoConstants.VERSION_NAME,
                "manifest_version_code" to DoubaoConstants.VERSION_CODE.toString(),
                "update_version_code" to DoubaoConstants.VERSION_CODE.toString(),
                "ssmix" to "a",
                "_rticket" to System.currentTimeMillis().toString(),
                "cdid" to cdid,
                "ac" to "wifi",
            ),
        )

    private fun settingsQuery(
        deviceId: String,
        cdid: String,
    ): String =
        query(
            mapOf(
                "device_platform" to "android",
                "os" to "android",
                "ssmix" to "a",
                "_rticket" to System.currentTimeMillis().toString(),
                "cdid" to cdid,
                "channel" to DoubaoConstants.CHANNEL,
                "aid" to DoubaoConstants.AID.toString(),
                "app_name" to DoubaoConstants.APP_NAME,
                "version_code" to DoubaoConstants.VERSION_CODE.toString(),
                "version_name" to DoubaoConstants.VERSION_NAME,
                "device_id" to deviceId,
            ),
        )

    private fun JsonObject.longString(
        numberKey: String,
        stringKey: String,
    ): String? =
        this[stringKey]?.jsonPrimitive?.contentOrNull
            ?: this[numberKey]?.jsonPrimitive?.longOrNull?.toString()

    private fun parseJsonObject(
        text: String,
        context: String,
    ): JsonObject =
        try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: IllegalArgumentException) {
            throw DoubaoProtocolException("Invalid $context JSON", error)
        }

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.uppercase(Locale.US)
    }

    private fun randomHex(byteCount: Int): String =
        Random.Default.nextBytes(byteCount).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun generateDeviceIds(): GeneratedDeviceIds =
        GeneratedDeviceIds(
            cdid = UUID.randomUUID().toString(),
            openudid = randomHex(8),
            clientudid = UUID.randomUUID().toString(),
        )

    private data class GeneratedDeviceIds(
        val cdid: String,
        val openudid: String,
        val clientudid: String,
    )

    private data class TextResponse(
        val statusCode: Int,
        val body: String,
    )
}

class DoubaoProtocolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
