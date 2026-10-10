package io.legado.app.help.sync

import android.content.Context
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.BackupArchiveExtractor
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File
import java.net.URLEncoder

/**
 * 联网同步编排。
 *
 * 冲突处理：以「上次同步时间」为分界判断双端是否都改动过。
 *
 * - 只有一端改动：由改动的那一端覆盖另一端；
 * - 双端都改动：按「本设备为主」开关决定——主设备上传本机版本，
 *   非主设备采用服务端版本。被覆盖掉的那一份会另存到
 *   `Android/data/<包名>/files/cloudSync/local|remote/`，
 *   服务端自身也保留历史版本，因此覆盖之后仍可回溯。
 *
 * 多设备共用同一账号时，只让一台设备保持「本设备为主」，
 * 否则双端冲突时会互相覆盖。
 */
object CloudSyncManager {

    private const val TAG = "CloudSync"
    private const val WORK_DIR = "cloudSync"
    private const val REMOTE_KEEP_DIR = "remote"
    private const val LOCAL_KEEP_DIR = "local"
    private const val STATE_KEY_PREFIX = "cloudSyncState_"

    /** 退出阅读同步的最小间隔，避免频繁切前后台导致反复同步 */
    private const val EXIT_SYNC_INTERVAL_MS = 60_000L

    private val mutex = Mutex()

    private var lastExitSyncAt = 0L

    // ------------------------------------------------------------ 配置

    fun server(context: Context): String =
        context.getPrefString(PreferKey.cloudSyncServer).orEmpty()

    fun account(context: Context): String =
        context.getPrefString(PreferKey.cloudSyncAccount).orEmpty()

    fun password(context: Context): String =
        context.getPrefString(PreferKey.cloudSyncPassword).orEmpty()

    fun isLoggedIn(context: Context): Boolean =
        context.getPrefString(PreferKey.cloudSyncToken).orEmpty().isNotBlank()

    fun isConfigured(context: Context): Boolean =
        server(context).isNotBlank() && isLoggedIn(context)

    fun enabled(context: Context): Boolean =
        context.getPrefBoolean(PreferKey.cloudSyncEnabled, false)

    fun autoOnStart(context: Context): Boolean =
        context.getPrefBoolean(PreferKey.cloudSyncAutoOnStart, true)

    /**
     * 本设备是否为主设备。
     *
     * 双端都有改动时：主设备以本机为准上传，非主设备采用服务端版本下载。
     * 手机、平板、模拟器共用同一个账号时，靠它避免互相覆盖——
     * 手机开、备用机与模拟器关。
     */
    fun isPrimaryDevice(context: Context): Boolean =
        context.getPrefBoolean(PreferKey.cloudSyncPrimaryDevice, true)

    /** 是否把字体、背景图片一起同步（体积较大） */
    fun withAssets(context: Context): Boolean =
        context.getPrefBoolean(PreferKey.cloudSyncWithAssets, false)

    fun lastSyncTime(context: Context): Long =
        context.getPrefLong(PreferKey.cloudSyncLastTime, 0L)

    fun lastResult(context: Context): String =
        context.getPrefString(PreferKey.cloudSyncLastResult).orEmpty()

    fun selectedNamespaces(context: Context): List<SyncNamespace> {
        val saved = context.getPrefStringSet(PreferKey.cloudSyncTypes)
            ?: return SyncNamespace.defaults().mapNotNull { SyncNamespace.from(it) }
        if (saved.isEmpty()) return emptyList()
        return SyncNamespace.values().filter { it.key in saved }
    }

    fun saveSelectedNamespaces(context: Context, keys: Collection<String>) {
        context.putPrefStringSet(PreferKey.cloudSyncTypes, keys.toMutableSet())
    }

    fun saveServerAccount(context: Context, server: String, account: String, password: String) {
        context.putPrefString(PreferKey.cloudSyncServer, server.trim())
        context.putPrefString(PreferKey.cloudSyncAccount, account.trim())
        context.putPrefString(PreferKey.cloudSyncPassword, password)
    }

    fun logout(context: Context) {
        context.putPrefString(PreferKey.cloudSyncToken, null)
        context.putPrefLong(PreferKey.cloudSyncTokenExpires, 0L)
        clearStates(context)
    }

    // ------------------------------------------------------------ 登录

    suspend fun login(context: Context, register: Boolean = false): String {
        val server = server(context)
        val account = account(context)
        val password = password(context)
        if (server.isBlank()) throw SyncException("请先填写服务器地址")
        if (account.isBlank()) throw SyncException("请先填写账号")
        if (password.isBlank()) throw SyncException("请先填写密码")
        val auth = if (register) {
            CloudSyncApi.register(server, account, password)
        } else {
            CloudSyncApi.login(server, account, password)
        }
        val token = auth.token
        if (token.isNullOrBlank()) throw SyncException(auth.error ?: "登录失败")
        context.putPrefString(PreferKey.cloudSyncToken, token)
        context.putPrefLong(PreferKey.cloudSyncTokenExpires, auth.expiresAt)
        clearStates(context)
        AppLog.put("同步账号登录成功：${auth.username ?: account}")
        return token
    }

    private suspend fun ensureToken(context: Context): String {
        val token = context.getPrefString(PreferKey.cloudSyncToken).orEmpty()
        val expires = context.getPrefLong(PreferKey.cloudSyncTokenExpires, 0L)
        if (token.isNotBlank() && expires > System.currentTimeMillis() + 60_000L) {
            return token
        }
        return login(context, register = false)
    }

    // ------------------------------------------------------------ 同步入口

    /** 双向自动同步：按时间戳决定上传还是拉取 */
    suspend fun syncAll(context: Context): SyncRunResult = run(context, PushMode.AUTO)

    /** 以本机为准，全量推送到服务端 */
    suspend fun pushAll(context: Context): SyncRunResult = run(context, PushMode.PUSH)

    /** 以服务端为准，全量拉取并覆盖本机 */
    suspend fun pullAll(context: Context): SyncRunResult = run(context, PushMode.PULL)

    private enum class PushMode { AUTO, PUSH, PULL }

    private suspend fun run(context: Context, mode: PushMode): SyncRunResult = mutex.withLock {
        val startedAt = System.currentTimeMillis()
        val server = CloudSyncApi.normalizeServer(server(context))
        val token = ensureToken(context)
        val namespaces = selectedNamespaces(context)
        if (namespaces.isEmpty()) throw SyncException("请先选择要同步的内容")
        val manifest = CloudSyncApi.manifest(server, token)
        val workDir = File(appCtx.externalFiles, WORK_DIR)
        FileUtils.delete(workDir.absolutePath)
        workDir.mkdirs()

        val results = ArrayList<SyncNamespaceResult>()
        try {
            for (ns in namespaces) {
                val result = runCatching {
                    when (mode) {
                        PushMode.PUSH -> push(context, server, token, ns, workDir)
                        PushMode.PULL -> pull(context, server, token, ns, manifest, workDir)
                        PushMode.AUTO -> auto(context, server, token, ns, manifest, workDir)
                    }
                }.getOrElse { error ->
                    AppLog.put("同步[${ns.key}]失败：${error.localizedMessage}", error)
                    SyncNamespaceResult(
                        ns,
                        SyncOutcome.FAILED,
                        error.localizedMessage ?: "同步失败"
                    )
                }
                results.add(result)
            }
        } finally {
            FileUtils.delete(workDir.absolutePath)
        }

        val finishedAt = System.currentTimeMillis()
        val runResult = SyncRunResult(results, startedAt, finishedAt)
        context.putPrefLong(PreferKey.cloudSyncLastTime, finishedAt)
        context.putPrefString(PreferKey.cloudSyncLastResult, describe(runResult))
        AppLog.put("同步完成：${describe(runResult)}")
        runResult
    }

    // ------------------------------------------------------------ 三种模式

    private suspend fun auto(
        context: Context,
        server: String,
        token: String,
        ns: SyncNamespace,
        manifest: SyncManifest,
        workDir: File
    ): SyncNamespaceResult {
        val state = loadState(context, ns)
        if (!state.initialized) {
            return SyncNamespaceResult(
                ns,
                SyncOutcome.SKIPPED,
                "还没有同步基线，请先执行一次「推送本机」或「拉取服务端」"
            )
        }
        val pkg = CloudSyncPackager.buildPackage(ns, withAssets(context), workDir)
        val entry = manifest.entry(ns)
        val remoteTime = entry?.updatedAt ?: 0L
        val localChanged = pkg.contentHash != state.localHash
        val remoteChanged = remoteTime > 0L &&
            (remoteTime != state.remoteTime || entry?.hash != state.remoteHash)

        return when {
            !localChanged && !remoteChanged ->
                SyncNamespaceResult(ns, SyncOutcome.SKIPPED, "本机与服务端一致")

            localChanged && !remoteChanged ->
                upload(context, server, token, ns, pkg)

            !localChanged ->
                download(context, server, token, ns, manifest, workDir, keepLocal = false)

            else -> {
                // 双端都改过：按设备角色决定采用哪一份，另一份留存以便回溯。
                // 不能无条件以本机为准，否则多设备共用账号时会互相覆盖。
                if (isPrimaryDevice(context)) {
                    keepRemoteCopy(server, token, ns, workDir, remoteTime)
                    AppLog.put("同步[${ns.key}]双端都有修改，本机为主设备，采用本机版本并留存服务端版本")
                    upload(context, server, token, ns, pkg)
                } else {
                    keepLocalCopy(ns, pkg.zipFile)
                    AppLog.put("同步[${ns.key}]双端都有修改，本机为从设备，采用服务端版本并留存本机版本")
                    download(context, server, token, ns, manifest, workDir, keepLocal = false)
                }
            }
        }
    }

    private suspend fun push(
        context: Context,
        server: String,
        token: String,
        ns: SyncNamespace,
        workDir: File
    ): SyncNamespaceResult {
        val pkg = CloudSyncPackager.buildPackage(ns, withAssets(context), workDir)
        return upload(context, server, token, ns, pkg)
    }

    private suspend fun pull(
        context: Context,
        server: String,
        token: String,
        ns: SyncNamespace,
        manifest: SyncManifest,
        workDir: File
    ): SyncNamespaceResult {
        return download(context, server, token, ns, manifest, workDir, keepLocal = true)
    }

    private suspend fun upload(
        context: Context,
        server: String,
        token: String,
        ns: SyncNamespace,
        pkg: SyncPackage
    ): SyncNamespaceResult {
        val response = CloudSyncApi.upload(
            server = server,
            token = token,
            ns = ns,
            bytes = pkg.zipFile.readBytes(),
            deviceId = deviceId()
        )
        saveState(
            context, ns, SyncState(
                remoteTime = response.updatedAt,
                remoteHash = response.hash,
                localHash = pkg.contentHash,
                lastSyncTime = System.currentTimeMillis()
            )
        )
        return SyncNamespaceResult(
            ns,
            SyncOutcome.UPLOADED,
            "已上传到服务端（${sizeText(pkg.zipFile.length())}）"
        )
    }

    private suspend fun download(
        context: Context,
        server: String,
        token: String,
        ns: SyncNamespace,
        manifest: SyncManifest,
        workDir: File,
        keepLocal: Boolean
    ): SyncNamespaceResult {
        val entry = manifest.entry(ns)
        val bytes = CloudSyncApi.download(server, token, ns)
            ?: return SyncNamespaceResult(ns, SyncOutcome.SKIPPED, "服务端还没有该分类的数据")
        if (keepLocal) {
            runCatching {
                CloudSyncPackager.buildPackage(ns, withAssets(context), workDir).let {
                    keepLocalCopy(ns, it.zipFile)
                }
            }
        }
        val zipFile = File(workDir, "${ns.key}_download.zip")
        zipFile.writeBytes(bytes)
        val unpackDir = File(workDir, "unpack_${ns.key}")
        BackupArchiveExtractor.extract(zipFile, unpackDir)
        CloudSyncPackager.applyPackage(ns, unpackDir)
        val applied = CloudSyncPackager.buildPackage(ns, withAssets(context), workDir)
        saveState(
            context, ns, SyncState(
                remoteTime = entry?.updatedAt ?: System.currentTimeMillis(),
                remoteHash = entry?.hash,
                localHash = applied.contentHash,
                lastSyncTime = System.currentTimeMillis()
            )
        )
        return SyncNamespaceResult(
            ns,
            SyncOutcome.DOWNLOADED,
            "已拉取服务端数据（${sizeText(bytes.size.toLong())}）"
        )
    }

    // ------------------------------------------------------------ 备份留存

    private suspend fun keepRemoteCopy(
        server: String,
        token: String,
        ns: SyncNamespace,
        workDir: File,
        remoteTime: Long
    ) {
        runCatching {
            val bytes = CloudSyncApi.download(server, token, ns) ?: return
            val dir = File(workDir, REMOTE_KEEP_DIR)
            dir.mkdirs()
            File(dir, "${ns.key}_$remoteTime.zip").writeBytes(bytes)
            AppLog.put("服务端[${ns.key}]版本已留存到 ${dir.absolutePath}")
        }.onFailure {
            AppLog.put("留存服务端[${ns.key}]版本失败：${it.localizedMessage}", it)
        }
    }

    private fun keepLocalCopy(ns: SyncNamespace, zipFile: File) {
        if (!zipFile.isFile) return
        val dir = File(appCtx.externalFiles, "$WORK_DIR/$LOCAL_KEEP_DIR")
        dir.mkdirs()
        val target = File(dir, "${ns.key}_${System.currentTimeMillis()}.zip")
        runCatching {
            zipFile.copyTo(target, overwrite = true)
            AppLog.put("本机[${ns.key}]原数据已留存到 ${target.absolutePath}")
        }
    }

    // ------------------------------------------------------------ 状态

    private fun stateKey(ns: SyncNamespace) = "$STATE_KEY_PREFIX${ns.key}"

    private fun loadState(context: Context, ns: SyncNamespace): SyncState {
        val json = context.getPrefString(stateKey(ns))
        return GSON.fromJsonObject<SyncState>(json).getOrNull() ?: SyncState()
    }

    private fun saveState(context: Context, ns: SyncNamespace, state: SyncState) {
        context.putPrefString(stateKey(ns), GSON.toJson(state))
    }

    fun clearStates(context: Context) {
        SyncNamespace.values().forEach { context.putPrefString(stateKey(it), null) }
    }

    /** 某分类上次与服务端同步的时间 */
    fun stateSyncTime(context: Context, ns: SyncNamespace): Long = loadState(context, ns).lastSyncTime

    // ------------------------------------------------------------ 自动同步

    /** 启动时自动同步一次（设置项开启且已登录时生效） */
    fun autoSyncOnStart() {
        val context = appCtx
        if (!enabled(context) || !autoOnStart(context) || !isConfigured(context)) return
        Coroutine.async {
            runCatching { syncAll(context) }
                .onSuccess { notifyAutoResult(it) }
                .onFailure {
                    AppLog.put("启动自动同步失败：${it.localizedMessage}", it)
                    appCtx.toastOnUi("启动自动同步失败：${it.localizedMessage}")
                }
        }
    }

    /**
     * 退出阅读界面（切后台/锁屏）时同步一次，把阅读进度推上去，
     * 这样另一台设备打开时能拉到最新章节。
     * 带最小间隔节流，避免读书时频繁切前后台反复整包上传。
     */
    fun syncOnExit() {
        val context = appCtx
        if (!enabled(context) || !isConfigured(context)) return
        val now = System.currentTimeMillis()
        if (now - lastExitSyncAt < EXIT_SYNC_INTERVAL_MS) return
        lastExitSyncAt = now
        Coroutine.async {
            runCatching { syncAll(context) }
                .onSuccess { notifyAutoResult(it) }
                .onFailure {
                    AppLog.put("退出阅读同步失败：${it.localizedMessage}", it)
                }
        }
    }

    /**
     * 自动同步的轻提示：只在真正动了数据、或有分区失败时才提示，
     * 避免每次启动都弹一次刷屏。
     */
    private fun notifyAutoResult(result: SyncRunResult) {
        if (result.uploaded > 0 || result.downloaded > 0 || result.failed.isNotEmpty()) {
            appCtx.toastOnUi("自动同步：${describe(result)}")
        }
    }

    // ------------------------------------------------------------ 工具

    /**
     * 设备标识：设备名 + androidId。
     * HTTP 头只允许 ASCII，设备名可能是中文，所以这里做 URL 编码（服务端会解码）。
     */
    private fun deviceId(): String {
        val name = AppConfig.webDavDeviceName?.takeIf { it.isNotBlank() }
        val raw = if (name != null) "$name@$androidId" else androidId
        return runCatching {
            URLEncoder.encode(raw.take(120), "UTF-8").replace("+", "%20")
        }.getOrDefault(androidId)
    }

    private fun describe(result: SyncRunResult): String {
        val parts = ArrayList<String>(4)
        if (result.uploaded > 0) parts.add("上传 ${result.uploaded}")
        if (result.downloaded > 0) parts.add("拉取 ${result.downloaded}")
        if (result.skipped > 0) parts.add("跳过 ${result.skipped}")
        if (result.failed.isNotEmpty()) parts.add("失败 ${result.failed.size}")
        return if (parts.isEmpty()) "没有需要同步的内容" else parts.joinToString("，")
    }

    private fun sizeText(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

}
