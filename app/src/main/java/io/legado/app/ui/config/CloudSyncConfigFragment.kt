package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.sync.CloudSyncApi
import io.legado.app.help.sync.CloudSyncManager
import io.legado.app.help.sync.SyncNamespace
import io.legado.app.help.sync.SyncRunResult
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 联网同步设置页。
 *
 * 服务器地址、账号、密码写在本机偏好里，登录后拿到 token 存本地；
 * 同步内容按四个分区勾选，冲突按时间戳后写覆盖。
 */
class CloudSyncConfigFragment : ComposeSettingFragment() {

    private companion object {
        const val KEY_ACCOUNT = "cloudSyncAccountEdit"
        const val KEY_LOGIN = "cloudSyncLogin"
        const val KEY_REGISTER = "cloudSyncRegister"
        const val KEY_LOGOUT = "cloudSyncLogout"
        const val KEY_TEST = "cloudSyncTest"
        const val KEY_SYNC_NOW = "cloudSyncNow"
        const val KEY_PUSH = "cloudSyncPush"
        const val KEY_PULL = "cloudSyncPull"
        const val KEY_NS_PREFIX = "cloudSyncNamespace_"
    }

    private enum class Mode { AUTO, PUSH, PULL }

    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var syncTask: Coroutine<SyncRunResult>? = null

    override val titleRes: Int = R.string.cloud_sync

    override fun buildPageSpec(): SettingPageSpec {
        val context = requireContext()
        val loggedIn = CloudSyncManager.isLoggedIn(context)
        val enabled = CloudSyncManager.enabled(context)
        val selected = CloudSyncManager.selectedNamespaces(context).map { it.key }.toSet()
        val ready = enabled && loggedIn

        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = getString(R.string.cloud_sync_group_service),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_ACCOUNT,
                            title = getString(R.string.cloud_sync_account),
                            summary = accountSummary(),
                            onClick = ::showAccountDialog
                        ),
                        SettingActionSpec(
                            key = KEY_LOGIN,
                            title = getString(R.string.cloud_sync_login),
                            summary = getString(R.string.cloud_sync_login_summary),
                            visible = !loggedIn,
                            onClick = { doAuth(register = false) }
                        ),
                        SettingActionSpec(
                            key = KEY_REGISTER,
                            title = getString(R.string.cloud_sync_register),
                            summary = getString(R.string.cloud_sync_register_summary),
                            visible = !loggedIn,
                            onClick = { doAuth(register = true) }
                        ),
                        SettingActionSpec(
                            key = KEY_TEST,
                            title = getString(R.string.cloud_sync_test),
                            summary = getString(R.string.cloud_sync_test_summary),
                            onClick = ::testConnection
                        ),
                        SettingActionSpec(
                            key = KEY_LOGOUT,
                            title = getString(R.string.cloud_sync_logout),
                            summary = getString(R.string.cloud_sync_logout_summary),
                            visible = loggedIn,
                            onClick = ::confirmLogout
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.cloud_sync_group_content),
                    items = buildList {
                        SyncNamespace.values().forEach { ns ->
                            add(
                                SettingSwitchSpec(
                                    key = "$KEY_NS_PREFIX${ns.key}",
                                    title = getString(ns.titleRes),
                                    checked = ns.key in selected,
                                    onCheckedChange = { checked ->
                                        val keys = CloudSyncManager
                                            .selectedNamespaces(context)
                                            .map { it.key }
                                            .toMutableSet()
                                        if (checked) keys.add(ns.key) else keys.remove(ns.key)
                                        CloudSyncManager.saveSelectedNamespaces(context, keys)
                                        refreshSettings()
                                    }
                                )
                            )
                        }
                        add(
                            SettingSwitchSpec(
                                key = PreferKey.cloudSyncWithAssets,
                                title = getString(R.string.cloud_sync_with_assets),
                                summary = getString(R.string.cloud_sync_with_assets_s),
                                checked = CloudSyncManager.withAssets(context),
                                onCheckedChange = {
                                    updateBooleanSetting(PreferKey.cloudSyncWithAssets, it)
                                }
                            )
                        )
                    }
                ),
                SettingSectionSpec(
                    title = getString(R.string.cloud_sync_group_action),
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.cloudSyncEnabled,
                            title = getString(R.string.cloud_sync_enable),
                            summary = getString(R.string.cloud_sync_enable_s),
                            checked = enabled,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.cloudSyncEnabled, it)
                                refreshSettings()
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.cloudSyncAutoOnStart,
                            title = getString(R.string.cloud_sync_auto_on_start),
                            summary = getString(R.string.cloud_sync_auto_on_start_s),
                            checked = CloudSyncManager.autoOnStart(context),
                            enabled = enabled,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.cloudSyncAutoOnStart, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.cloudSyncPrimaryDevice,
                            title = getString(R.string.cloud_sync_primary_device),
                            summary = getString(R.string.cloud_sync_primary_device_s),
                            checked = CloudSyncManager.isPrimaryDevice(context),
                            enabled = enabled,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.cloudSyncPrimaryDevice, it)
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_SYNC_NOW,
                            title = getString(R.string.cloud_sync_now),
                            summary = getString(R.string.cloud_sync_now_s) + "\n" + lastSyncText(),
                            enabled = ready,
                            onClick = { runSync(Mode.AUTO) }
                        ),
                        SettingActionSpec(
                            key = KEY_PUSH,
                            title = getString(R.string.cloud_sync_push),
                            summary = getString(R.string.cloud_sync_push_s),
                            enabled = ready,
                            onClick = { runSync(Mode.PUSH) }
                        ),
                        SettingActionSpec(
                            key = KEY_PULL,
                            title = getString(R.string.cloud_sync_pull),
                            summary = getString(R.string.cloud_sync_pull_s),
                            enabled = ready,
                            onClick = { runSync(Mode.PULL) }
                        )
                    )
                )
            )
        )
    }

    // ------------------------------------------------------------ 账号

    private fun accountSummary(): String {
        val context = requireContext()
        val server = CloudSyncManager.server(context)
        val account = CloudSyncManager.account(context)
        return when {
            server.isBlank() -> getString(R.string.cloud_sync_account_empty)
            account.isBlank() -> server
            CloudSyncManager.isLoggedIn(context) -> "$server / $account"
            else -> "$server / $account (${getString(R.string.cloud_sync_not_logged_in)})"
        }
    }

    private fun showAccountDialog() {
        val context = requireContext()
        showComposeTextFormDialog(
            title = getString(R.string.cloud_sync_account),
            labels = listOf(
                getString(R.string.cloud_sync_server),
                getString(R.string.cloud_sync_account_label),
                getString(R.string.cloud_sync_password)
            ),
            initialValues = listOf(
                CloudSyncManager.server(context),
                CloudSyncManager.account(context),
                CloudSyncManager.password(context)
            ),
            passwordFields = setOf(2),
            onPositive = { values ->
                CloudSyncManager.saveServerAccount(
                    context = context,
                    server = values.getOrNull(0).orEmpty(),
                    account = values.getOrNull(1).orEmpty(),
                    password = values.getOrNull(2).orEmpty()
                )
                // 服务器或账号变化后原 token 不再适用，需要重新登录
                CloudSyncManager.logout(context)
                refreshSettings()
            }
        )
    }

    private fun confirmLogout() {
        showComposeConfirmDialog(
            title = getString(R.string.cloud_sync_logout),
            message = getString(R.string.cloud_sync_logout_summary),
            onPositive = {
                CloudSyncManager.logout(requireContext())
                refreshSettings()
            }
        )
    }

    private fun doAuth(register: Boolean) {
        val context = requireContext()
        if (CloudSyncManager.server(context).isBlank()) {
            appCtx.toastOnUi(R.string.cloud_sync_server_empty)
            return
        }
        if (CloudSyncManager.account(context).isBlank()) {
            appCtx.toastOnUi(R.string.cloud_sync_account_empty)
            return
        }
        waitDialog.setText(
            if (register) R.string.cloud_sync_registering else R.string.cloud_sync_logging_in
        )
        waitDialog.show()
        val task = Coroutine.async {
            CloudSyncManager.login(context, register)
        }.onSuccess {
            waitDialog.dismiss()
            appCtx.toastOnUi(
                if (register) R.string.cloud_sync_register_success
                else R.string.cloud_sync_login_success
            )
            refreshSettings()
        }.onError {
            waitDialog.dismiss()
            appCtx.toastOnUi(it.localizedMessage ?: getString(R.string.cloud_sync_failed))
        }
        waitDialog.setOnCancelListener { task.cancel() }
    }

    private fun testConnection() {
        val context = requireContext()
        if (CloudSyncManager.server(context).isBlank()) {
            appCtx.toastOnUi(R.string.cloud_sync_server_empty)
            return
        }
        waitDialog.setText(R.string.cloud_sync_testing)
        waitDialog.show()
        val task = Coroutine.async {
            CloudSyncApi.health(CloudSyncManager.server(context))
        }.onSuccess { ok ->
            waitDialog.dismiss()
            appCtx.toastOnUi(
                if (ok) R.string.cloud_sync_connect_ok else R.string.cloud_sync_failed
            )
        }.onError {
            waitDialog.dismiss()
            appCtx.toastOnUi(it.localizedMessage ?: getString(R.string.cloud_sync_failed))
        }
        waitDialog.setOnCancelListener { task.cancel() }
    }

    // ------------------------------------------------------------ 同步

    private fun runSync(mode: Mode) {
        val context = requireContext()
        if (!CloudSyncManager.isConfigured(context)) {
            appCtx.toastOnUi(R.string.cloud_sync_need_login)
            return
        }
        if (CloudSyncManager.selectedNamespaces(context).isEmpty()) {
            appCtx.toastOnUi(R.string.cloud_sync_need_content)
            return
        }
        waitDialog.setText(
            when (mode) {
                Mode.AUTO -> R.string.cloud_sync_now
                Mode.PUSH -> R.string.cloud_sync_push
                Mode.PULL -> R.string.cloud_sync_pull
            }
        )
        waitDialog.show()
        val task = Coroutine.async {
            when (mode) {
                Mode.AUTO -> CloudSyncManager.syncAll(context)
                Mode.PUSH -> CloudSyncManager.pushAll(context)
                Mode.PULL -> CloudSyncManager.pullAll(context)
            }
        }.onSuccess { result ->
            waitDialog.dismiss()
            // 先提示再刷新：refreshSettings 会重建页面，顺序反过来提示容易被吞掉
            appCtx.toastOnUi(describeResult(result))
            refreshSettings()
        }.onError {
            waitDialog.dismiss()
            appCtx.toastOnUi(it.localizedMessage ?: getString(R.string.cloud_sync_failed))
            refreshSettings()
        }.onFinally {
            syncTask = null
        }
        syncTask = task
        waitDialog.setOnCancelListener { syncTask?.cancel() }
    }

    private fun describeResult(result: SyncRunResult): String {
        val parts = ArrayList<String>(4)
        if (result.uploaded > 0) parts.add("上传 ${result.uploaded}")
        if (result.downloaded > 0) parts.add("拉取 ${result.downloaded}")
        if (result.skipped > 0) parts.add("跳过 ${result.skipped}")
        if (result.failed.isNotEmpty()) {
            parts.add("失败 ${result.failed.size}：${result.failed.first().message}")
        }
        return parts.joinToString("，").ifBlank { getString(R.string.cloud_sync_nothing) }
    }

    private fun lastSyncText(): String {
        val time = CloudSyncManager.lastSyncTime(requireContext())
        if (time <= 0L) return getString(R.string.cloud_sync_never)
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val result = CloudSyncManager.lastResult(requireContext())
        return buildString {
            append(getString(R.string.cloud_sync_last))
            append(": ")
            append(format.format(Date(time)))
            if (result.isNotBlank()) {
                append(" · ")
                append(result)
            }
        }
    }
}
