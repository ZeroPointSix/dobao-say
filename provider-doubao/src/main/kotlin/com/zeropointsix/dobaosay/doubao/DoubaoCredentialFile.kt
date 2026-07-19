package com.zeropointsix.dobaosay.doubao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object DoubaoCredentialFile {
    private val json = Json { prettyPrint = true }

    fun read(path: Path): DoubaoCredentials? {
        if (!path.exists()) return null
        val root =
            try {
                Json.parseToJsonElement(path.readText()).jsonObject
            } catch (_: IllegalArgumentException) {
                return null
            }
        val deviceId = root.string("deviceId") ?: return null
        val cdid = root.string("cdid") ?: return null
        val openudid = root.string("openudid") ?: return null
        val clientudid = root.string("clientudid") ?: return null
        return DoubaoCredentials(
            deviceId = deviceId,
            installId = root.string("installId"),
            cdid = cdid,
            openudid = openudid,
            clientudid = clientudid,
            token = root.string("token"),
        )
    }

    fun write(
        path: Path,
        credentials: DoubaoCredentials,
    ) {
        path.parent?.createDirectories()
        val body =
            buildJsonObject {
                put("deviceId", credentials.deviceId)
                credentials.installId?.let { put("installId", it) }
                put("cdid", credentials.cdid)
                put("openudid", credentials.openudid)
                put("clientudid", credentials.clientudid)
                credentials.token?.let { put("token", it) }
            }
        path.writeText(json.encodeToString(JsonObject.serializer(), body))
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
