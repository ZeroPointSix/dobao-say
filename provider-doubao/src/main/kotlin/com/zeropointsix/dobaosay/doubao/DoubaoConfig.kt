package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.AudioFormat
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DoubaoDeviceProfile(
    val devicePlatform: String,
    val os: String,
    val osApi: String,
    val osVersion: String,
    val deviceType: String,
    val deviceBrand: String,
    val deviceModel: String,
    val resolution: String,
    val dpi: String,
    val language: String,
    val timezone: Int,
    val access: String,
    val rom: String,
    val romVersion: String,
)

data class DoubaoCredentials(
    val deviceId: String,
    val installId: String?,
    val cdid: String,
    val openudid: String,
    val clientudid: String,
    val token: String? = null,
) {
    init {
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
        require(cdid.isNotBlank()) { "cdid must not be blank" }
        require(openudid.isNotBlank()) { "openudid must not be blank" }
        require(clientudid.isNotBlank()) { "clientudid must not be blank" }
    }

    fun withToken(newToken: String): DoubaoCredentials {
        require(newToken.isNotBlank()) { "Token must not be blank" }
        return copy(token = newToken)
    }

    override fun toString(): String =
        "DoubaoCredentials(deviceId=${deviceId.redactedSecret()}, installId=${installId.redactedSecret()}, " +
            "cdid=${cdid.redactedSecret()}, openudid=${openudid.redactedSecret()}, " +
            "clientudid=${clientudid.redactedSecret()}, token=${token.redactedSecret()})"
}

data class DoubaoEndpoints(
    val registerUrl: String = DoubaoConstants.REGISTER_URL,
    val settingsUrl: String = DoubaoConstants.SETTINGS_URL,
    val websocketUrl: String = DoubaoConstants.WEBSOCKET_URL,
)

data class DoubaoSessionOptions(
    val enablePunctuation: Boolean = true,
    val enableSpeechRejection: Boolean = false,
    val enableAsrTwopass: Boolean = true,
    val enableAsrThreepass: Boolean = true,
    val sourceAppName: String = "com.android.chrome",
    val cellCompressRate: Int = 8,
    val inputMode: String = "tool",
)

data class DoubaoProviderConfig(
    val endpoints: DoubaoEndpoints = DoubaoEndpoints(),
    val deviceProfile: DoubaoDeviceProfile = DoubaoConstants.DEFAULT_DEVICE,
    val credentials: DoubaoCredentials? = null,
    val deviceId: String? = null,
    val token: String? = null,
    val userAgent: String = DoubaoConstants.USER_AGENT,
    val aid: Int = DoubaoConstants.AID,
    val sessionOptions: DoubaoSessionOptions = DoubaoSessionOptions(),
    val httpClient: OkHttpClient = OkHttpClient.Builder().followRedirects(true).build(),
    val opusEncoderFactory: OpusEncoderFactory = OpusEncoderFactory.concentus(),
    val websocketReceiveTimeout: Duration = 15.seconds,
) {
    init {
        require(aid > 0) { "aid must be positive" }
        require(userAgent.isNotBlank()) { "User-Agent must not be blank" }
        require(websocketReceiveTimeout.isPositive()) { "Receive timeout must be positive" }
    }

    fun deviceClient(): DoubaoDeviceClient =
        DoubaoDeviceClient(
            endpoints = endpoints,
            deviceProfile = deviceProfile,
            userAgent = userAgent,
            httpClient = httpClient,
        )

    fun websocketUrl(credentials: DoubaoCredentials): String = "${endpoints.websocketUrl}?aid=$aid&device_id=${credentials.deviceId}"
}

data class DoubaoDriverRuntimeConfig(
    val audioFormat: AudioFormat,
    val providerConfig: DoubaoProviderConfig,
)

internal fun String?.redactedSecret(): String =
    when {
        isNullOrBlank() -> "<absent>"
        length <= 8 -> "<redacted>"
        else -> "${take(4)}...${takeLast(4)}"
    }
