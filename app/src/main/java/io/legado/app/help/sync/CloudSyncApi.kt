package io.legado.app.help.sync

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.postJson
import io.legado.app.help.http.syncHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 同步服务端接口客户端。
 *
 * 所有请求走 [syncHttpClient]，与书源请求隔离，避免被书源 DNS/代理配置干扰。
 */
object CloudSyncApi {

    private val binMediaType = "application/octet-stream".toMediaType()

    fun normalizeServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw SyncException("请先填写服务器地址")
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun url(server: String, path: String): String = normalizeServer(server) + path

    suspend fun health(server: String): Boolean {
        val response = syncHttpClient.newCallResponse {
            url(url(server, "/v1/health"))
        }
        response.use { return it.isSuccessful }
    }

    suspend fun register(server: String, account: String, password: String): SyncAuthResponse {
        return auth(server, "/v1/auth/register", account, password)
    }

    suspend fun login(server: String, account: String, password: String): SyncAuthResponse {
        return auth(server, "/v1/auth/login", account, password)
    }

    private suspend fun auth(
        server: String,
        path: String,
        account: String,
        password: String
    ): SyncAuthResponse {
        val payload = GSON.toJson(mapOf("username" to account, "password" to password))
        val str = syncHttpClient.newCallStrResponse {
            url(url(server, path))
            postJson(payload)
        }
        val parsed = GSON.fromJsonObject<SyncAuthResponse>(str.body).getOrNull()
        if (parsed?.token.isNullOrBlank()) {
            throw SyncException(serverMessage(str.body) ?: "请求失败(HTTP ${str.code()})")
        }
        return parsed
    }

    suspend fun manifest(server: String, token: String): SyncManifest {
        val str = syncHttpClient.newCallStrResponse {
            url(url(server, "/v1/manifest"))
            header("Authorization", "Bearer $token")
        }
        if (!str.isSuccessful()) {
            throw SyncException(
                serverMessage(str.body) ?: "获取同步清单失败(HTTP ${str.code()})"
            )
        }
        return GSON.fromJsonObject<SyncManifest>(str.body)
            .getOrElse { throw SyncException("同步清单解析失败") }
    }

    /** 下载快照，服务端没有该分区的数据时返回 null */
    suspend fun download(server: String, token: String, ns: SyncNamespace): ByteArray? {
        val response = syncHttpClient.newCallResponse {
            url(url(server, "/v1/snapshot/${ns.key}"))
            header("Authorization", "Bearer $token")
        }
        response.use { res ->
            if (res.code == 404) return null
            if (!res.isSuccessful) {
                throw SyncException(
                    serverMessage(res.body?.string()) ?: "下载失败(HTTP ${res.code})"
                )
            }
            return res.body?.bytes() ?: throw SyncException("下载内容为空")
        }
    }

    suspend fun upload(
        server: String,
        token: String,
        ns: SyncNamespace,
        bytes: ByteArray,
        deviceId: String
    ): SyncUploadResponse {
        val str = syncHttpClient.newCallStrResponse {
            url(url(server, "/v1/snapshot/${ns.key}"))
            header("Authorization", "Bearer $token")
            header("X-Sync-Device", deviceId)
            put(bytes.toRequestBody(binMediaType))
        }
        if (!str.isSuccessful()) {
            throw SyncException(
                serverMessage(str.body) ?: "上传失败(HTTP ${str.code()})"
            )
        }
        return GSON.fromJsonObject<SyncUploadResponse>(str.body)
            .getOrElse { SyncUploadResponse(updatedAt = System.currentTimeMillis()) }
    }

    private fun serverMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return GSON.fromJsonObject<Map<String, String>>(body).getOrNull()?.get("error")
    }
}
