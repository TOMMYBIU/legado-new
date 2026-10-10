package io.legado.app.help.sync

import androidx.annotation.StringRes
import com.google.gson.annotations.SerializedName
import io.legado.app.R
import java.io.File

/**
 * 联网同步的数据分区。
 *
 * 每个分区对应服务端的一个快照文件，分区内部是一整包数据（zip），
 * 冲突时按时间戳整包后写覆盖，不做条目级合并。
 */
enum class SyncNamespace(
    val key: String,
    @param:StringRes val titleRes: Int
) {
    SOURCES("sources", R.string.cloud_sync_ns_sources),
    SHELF("shelf", R.string.cloud_sync_ns_shelf),
    SETTINGS("settings", R.string.cloud_sync_ns_settings),
    BOOKMARKS("bookmarks", R.string.cloud_sync_ns_bookmarks);

    companion object {
        fun from(key: String?): SyncNamespace? = values().firstOrNull { it.key == key }

        /** 默认同步全部分区 */
        fun defaults(): List<String> = values().map { it.key }
    }
}

/** 服务端某个分区的版本信息 */
data class SyncManifestEntry(
    @SerializedName("updatedAt") val updatedAt: Long = 0L,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("size") val size: Long = 0L,
    @SerializedName("deviceId") val deviceId: String? = null
)

/** 服务端清单 */
data class SyncManifest(
    @SerializedName("username") val username: String? = null,
    @SerializedName("namespaces") val namespaces: Map<String, SyncManifestEntry>? = null,
    @SerializedName("time") val time: Long = 0L
) {
    fun entry(ns: SyncNamespace): SyncManifestEntry? = namespaces?.get(ns.key)
}

/** 注册/登录响应 */
data class SyncAuthResponse(
    @SerializedName("username") val username: String? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("expiresAt") val expiresAt: Long = 0L,
    @SerializedName("error") val error: String? = null
)

/** 上传快照的响应 */
data class SyncUploadResponse(
    @SerializedName("namespace") val namespace: String? = null,
    @SerializedName("updatedAt") val updatedAt: Long = 0L,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("size") val size: Long = 0L,
    @SerializedName("error") val error: String? = null
)

/**
 * 单个分区的本地同步基线。
 *
 * [localHash] 是上次同步结束时本地快照的哈希，用来判断"本地改动过没有"；
 * [remoteTime]/[remoteHash] 是上次同步结束时服务端的版本，用来判断"服务端改动过没有"。
 */
data class SyncState(
    @SerializedName("remoteTime") val remoteTime: Long = 0L,
    @SerializedName("remoteHash") val remoteHash: String? = null,
    @SerializedName("localHash") val localHash: String? = null,
    @SerializedName("lastSyncTime") val lastSyncTime: Long = 0L
) {
    val initialized: Boolean get() = lastSyncTime > 0L
}

enum class SyncOutcome {
    UPLOADED,
    DOWNLOADED,
    SKIPPED,
    FAILED
}

data class SyncNamespaceResult(
    val namespace: SyncNamespace,
    val outcome: SyncOutcome,
    val message: String
)

data class SyncRunResult(
    val results: List<SyncNamespaceResult>,
    val startedAt: Long,
    val finishedAt: Long
) {
    val failed: List<SyncNamespaceResult> get() = results.filter { it.outcome == SyncOutcome.FAILED }

    val uploaded: Int get() = results.count { it.outcome == SyncOutcome.UPLOADED }

    val downloaded: Int get() = results.count { it.outcome == SyncOutcome.DOWNLOADED }

    val skipped: Int get() = results.count { it.outcome == SyncOutcome.SKIPPED }
}

class SyncException(message: String) : Exception(message)

/**
 * 打好的同步包。
 *
 * [contentHash] 是对包内文件内容（按相对路径排序后）计算的哈希，与打包时间无关，
 * 因此可以用来判断"本机数据到底有没有改动"。
 * 注意不能用 zip 文件自身的哈希：zip 条目带文件时间戳，每次打包都不一样。
 */
data class SyncPackage(
    val zipFile: File,
    val contentHash: String
)
