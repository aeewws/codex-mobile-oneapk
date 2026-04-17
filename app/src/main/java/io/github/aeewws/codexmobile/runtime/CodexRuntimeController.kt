package io.github.aeewws.codexmobile.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.aeewws.codexmobile.service.BackendForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val APP_SERVER_PORT = 8765

data class ProjectRootOption(
    val label: String,
    val path: String,
    val note: String? = null,
)

class CodexRuntimeController(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val runtimeManager = EmbeddedRuntimeManager(context)
    private val authManager = CodexAuthManager(runtimeManager)
    private val backendManager = CodexBackendManager(runtimeManager)

    suspend fun ensureBackendRunning(): JSONObject {
        val status = getBackendStatus()
        if (!status.optBoolean("runtimeReady")) {
            return status.put("error", status.optString("backendStatusDetail").ifBlank { "本地运行时未就绪" })
        }
        if (!status.optBoolean("authPresent")) {
            return status.put("error", "Codex 还没登录")
        }
        if (status.optBoolean("backendListening")) {
            return status
        }

        val result = backendManager.ensureBackendRunning()
        return if (result.exitCode == 0) {
            getBackendStatus().put("startedNow", true)
        } else {
            getBackendStatus()
                .put("error", "端口 $APP_SERVER_PORT 没有起来")
                .put("backendStatusDetail", buildBackendFailureDetail("app-server 没有成功监听 $APP_SERVER_PORT", result.stdout, result.stderr))
        }
    }

    suspend fun restartBackend(): JSONObject {
        val status = getBackendStatus()
        if (!status.optBoolean("runtimeReady")) {
            return status.put("error", status.optString("backendStatusDetail").ifBlank { "本地运行时未就绪" })
        }
        if (!status.optBoolean("authPresent")) {
            return status.put("error", "Codex 还没登录")
        }

        val result = backendManager.restartBackend()
        return if (result.exitCode == 0) {
            getBackendStatus().put("restartedNow", true)
        } else {
            getBackendStatus()
                .put("error", "重启 app-server 失败")
                .put("backendStatusDetail", buildBackendFailureDetail("重启 app-server 失败", result.stdout, result.stderr))
        }
    }

    suspend fun stopBackend(): JSONObject {
        val status = getBackendStatus()
        if (!status.optBoolean("runtimeReady")) {
            return status.put("error", status.optString("backendStatusDetail").ifBlank { "本地运行时未就绪" })
        }
        if (!status.optBoolean("backendListening")) {
            return status.put("stoppedNow", false)
        }

        val result = backendManager.stopBackend()
        return if (result.exitCode == 0) {
            getBackendStatus().put("stoppedNow", true)
        } else {
            getBackendStatus()
                .put("error", "app-server 停止失败")
                .put("backendStatusDetail", buildBackendFailureDetail("app-server 没有成功停止", result.stdout, result.stderr))
        }
    }

    suspend fun getBackendStatus(): JSONObject {
        val runtime = runtimeManager.ensureRuntimeReady()
        val rootAvailable = isRootAvailable()
        val backendListening = backendManager.isBackendListening()
        val auth = authManager.readAuthSnapshot()

        return JSONObject()
            .put("rootAvailable", rootAvailable)
            .put("runtimeReady", runtime.runtimeReady)
            .put("runtimePackaged", runtime.runtimePackaged)
            .put("runtimeVersion", runtime.runtimeVersion)
            .put("runtimeAbi", runtime.runtimeAbi)
            .put("backendListening", backendListening)
            .put("authPresent", auth.authPresent)
            .put("authMode", auth.authMode)
            .put("accountId", auth.accountId)
            .put("loginInProgress", false)
            .put("autoHardeningEnabled", isAutoHardeningEnabled())
            .put(
                "backendStatusDetail",
                buildBackendStatusDetail(
                    runtimeReady = runtime.runtimeReady,
                    rootAvailable = rootAvailable,
                    backendListening = backendListening,
                    authPresent = auth.authPresent,
                    runtimeDetail = runtime.detail,
                ),
            )
            .put("runtimePath", runtime.runtimeDir.absolutePath)
            .put("codexHomePath", runtime.codexHomeDir.absolutePath)
            .put("port", APP_SERVER_PORT)
    }

    suspend fun getKeepaliveStatus(): JSONObject {
        val rootAvailable = isRootAvailable()
        val runtime = runtimeManager.ensureRuntimeReady()
        val deviceIdleOutput = if (rootAvailable) {
            RootShell.run("cmd deviceidle whitelist", timeoutMillis = 6_000L).stdout
        } else {
            ""
        }
        val restrictBackgroundOutput = if (rootAvailable) {
            RootShell.run("cmd netpolicy list restrict-background-whitelist", timeoutMillis = 6_000L).stdout
        } else {
            ""
        }
        val standbyBucketRaw = if (rootAvailable) {
            RootShell.run("am get-standby-bucket ${context.packageName}", timeoutMillis = 6_000L).stdout.trim()
        } else {
            "unknown"
        }
        val uid = context.applicationInfo.uid

        return JSONObject()
            .put("rootAvailable", rootAvailable)
            .put("runtimeReady", runtime.runtimeReady)
            .put("deviceIdleWhitelisted", deviceIdleOutput.lineSequence().any { it.contains(context.packageName) })
            .put(
                "restrictBackgroundWhitelisted",
                restrictBackgroundOutput.split(Regex("\\s+")).any { it == uid.toString() },
            )
            .put("standbyBucket", normalizeStandbyBucket(standbyBucketRaw))
            .put("standbyBucketRaw", standbyBucketRaw.ifBlank { "unknown" })
            .put("backendListening", backendManager.isBackendListening())
            .put("batteryOptimizationIgnoredForUiApp", powerManager.isIgnoringBatteryOptimizations(context.packageName))
            .put("autoHardeningEnabled", isAutoHardeningEnabled())
    }

    suspend fun runKeepaliveHardening(): JSONObject {
        val runtime = runtimeManager.ensureRuntimeReady()
        if (!runtime.runtimeReady) {
            return getKeepaliveStatus().put("error", runtime.detail)
        }
        if (!isRootAvailable()) {
            return getKeepaliveStatus().put("error", "root 不可用或尚未授权")
        }

        val uid = context.applicationInfo.uid
        val actions = JSONArray()
        actions.put(runAction("deviceidle", "cmd deviceidle whitelist +${context.packageName}"))
        actions.put(runAction("restrictBackground", "cmd netpolicy add restrict-background-whitelist $uid"))
        actions.put(runAction("standbyBucket", "am set-standby-bucket ${context.packageName} active"))
        prefs.edit { putBoolean(PREF_AUTO_HARDENING, true) }
        return getKeepaliveStatus().put("actions", actions)
    }

    suspend fun reapplyHardeningIfEnabled() {
        if (!isAutoHardeningEnabled()) return
        val status = getKeepaliveStatus()
        if (!status.optBoolean("rootAvailable") || !status.optBoolean("runtimeReady")) return

        val needsReapply =
            !status.optBoolean("deviceIdleWhitelisted") ||
                !status.optBoolean("restrictBackgroundWhitelisted") ||
                !isHealthyStandbyBucket(status.optString("standbyBucket"))
        if (needsReapply) {
            runKeepaliveHardening()
        }
    }

    suspend fun startLogin(): JSONObject {
        val runtime = runtimeManager.ensureRuntimeReady()
        if (!runtime.runtimeReady) {
            return getBackendStatus().put("error", runtime.detail)
        }
        runCatching { backendManager.stopBackend() }
        val result = authManager.startLogin(::openBrowserUrl)
        val status = getBackendStatus()
            .put("loginInProgress", result.started && !result.auth.authPresent)
            .put("verificationUrl", result.browserUrl)
            .put("loginHint", result.detail)
        if (result.auth.authPresent) {
            runCatching { backendManager.ensureBackendRunning() }
            return getBackendStatus().put("loggedIn", true)
        }
        return if (result.error.isNotBlank()) {
            status.put("error", result.error).put("backendStatusDetail", result.error)
        } else {
            status
        }
    }

    suspend fun startDeviceLogin(): JSONObject {
        val runtime = runtimeManager.ensureRuntimeReady()
        if (!runtime.runtimeReady) {
            return getBackendStatus().put("error", runtime.detail)
        }
        runCatching { backendManager.stopBackend() }
        val result = authManager.startDeviceLogin()
        val status = getBackendStatus()
            .put("loginInProgress", result.started && !result.auth.authPresent)
            .put("deviceCode", result.userCode)
            .put("verificationUrl", result.verificationUrl)
            .put("loginHint", result.detail)
        return if (result.error.isNotBlank()) {
            status.put("error", result.error).put("backendStatusDetail", result.error)
        } else {
            status
        }
    }

    suspend fun checkLoginStatus(): JSONObject {
        val auth = authManager.checkLoginStatus()
        return getBackendStatus()
            .put("authPresent", auth.authPresent)
            .put("authMode", auth.authMode)
            .put("accountId", auth.accountId)
    }

    suspend fun logout(): JSONObject {
        runCatching { backendManager.stopBackend() }
        authManager.logout()
        return getBackendStatus().put("loggedOut", true)
    }

    fun getAppPreferences(): JSONObject = JSONObject()
        .put("autoHardeningEnabled", isAutoHardeningEnabled())

    fun setAutoHardeningEnabled(enabled: Boolean): JSONObject {
        prefs.edit { putBoolean(PREF_AUTO_HARDENING, enabled) }
        return getAppPreferences()
    }

    fun setForegroundSessionActive(active: Boolean): JSONObject {
        val intent = Intent(context, BackendForegroundService::class.java)
        if (active) {
            ContextCompat.startForegroundService(
                context,
                intent.setAction(BackendForegroundService.ACTION_START),
            )
        } else {
            context.startService(intent.setAction(BackendForegroundService.ACTION_STOP))
        }
        return JSONObject().put("active", active)
    }

    fun shutdownManagedSession(): JSONObject {
        val intent = Intent(context, BackendForegroundService::class.java)
        context.startService(intent.setAction(BackendForegroundService.ACTION_SHUTDOWN))
        return JSONObject().put("shutdownRequested", true)
    }

    fun requestBatteryOptimizationIgnore(activity: Activity): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${context.packageName}".toUri(),
            )
            activity.startActivity(intent)
            true
        } catch (_: Throwable) {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            true
        }
    }

    fun defaultProjectRoots(): List<ProjectRootOption> = listOf(
        ProjectRootOption(
            label = "Codex Home",
            path = runtimeManager.defaultWorkingDirectory(),
            note = "最适合直接让 Codex 操作 App 内置运行时的工作目录。",
        ),
        ProjectRootOption(
            label = "共享存储根目录",
            path = "/storage/emulated/0",
            note = "适合下载目录、文档目录和你手机常用文件。",
        ),
        ProjectRootOption(
            label = "Download",
            path = "/storage/emulated/0/Download",
            note = "适合临时项目、压缩包、生成文件。",
        ),
        ProjectRootOption(
            label = "Documents",
            path = "/storage/emulated/0/Documents",
            note = "适合长期保存的项目文档和脚本。",
        ),
    )

    fun defaultWorkingDirectory(): String = runtimeManager.defaultWorkingDirectory()

    fun backendAttachmentDirectoryPath(): String = runtimeManager.backendAttachmentDirectory().absolutePath

    suspend fun getLocalThreadIndex(limit: Int = 80): JSONArray = withContext(Dispatchers.IO) {
        val indexFile = runtimeManager.codexStateDir().resolve("session_index.jsonl")
        if (!indexFile.exists()) {
            return@withContext JSONArray()
        }
        val items = JSONArray()
        indexFile.readLines()
            .takeLast(limit)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                try {
                    items.put(JSONObject(line))
                } catch (_: JSONException) {
                    // Skip malformed lines from partially-written session index records.
                }
            }
        items
    }

    suspend fun deleteThreadArtifacts(threadId: String): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        runtimeManager.sessionRoots().forEach { root ->
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.isFile && it.name.contains(threadId, ignoreCase = true) && it.extension.equals("jsonl", ignoreCase = true) }
                .forEach { file ->
                    val removed = if (file.delete()) {
                        true
                    } else {
                        runtimeManager.deleteWithRootFallback(file)
                    }
                    if (removed) {
                        deleted += 1
                    }
                }
        }
        deleted
    }

    private suspend fun runAction(name: String, command: String): JSONObject {
        val result = RootShell.run(command = command, timeoutMillis = 8_000L)
        return JSONObject()
            .put("name", name)
            .put("command", command)
            .put("success", result.exitCode == 0)
            .put("stdout", result.stdout.trim())
            .put("stderr", result.stderr.trim())
    }

    private suspend fun isRootAvailable(): Boolean {
        val result = RootShell.run("id", timeoutMillis = 4_000L)
        return result.exitCode == 0 && result.stdout.contains("uid=0")
    }

    private fun normalizeStandbyBucket(raw: String): String = when (raw.trim().lowercase()) {
        "", "unknown" -> "unknown"
        "5", "exempted" -> "exempted"
        "10", "active" -> "active"
        "20", "working_set" -> "working_set"
        "30", "frequent" -> "frequent"
        "40", "rare" -> "rare"
        "45", "restricted" -> "restricted"
        else -> raw.trim()
    }

    private fun isHealthyStandbyBucket(bucket: String): Boolean =
        bucket == "active" || bucket == "exempted"

    private fun isAutoHardeningEnabled(): Boolean =
        prefs.getBoolean(PREF_AUTO_HARDENING, true)

    private fun buildBackendStatusDetail(
        runtimeReady: Boolean,
        rootAvailable: Boolean,
        backendListening: Boolean,
        authPresent: Boolean,
        runtimeDetail: String,
    ): String = when {
        !runtimeReady -> runtimeDetail.ifBlank { "本地运行时未初始化。" }
        backendListening -> "app-server 已在 127.0.0.1:$APP_SERVER_PORT 监听。"
        !authPresent -> "本地运行时已就绪，但 Codex 登录凭证不存在。"
        !rootAvailable -> "本机运行时已登录，但当前没有 root 增强保活。"
        else -> "本机运行时已登录，但 app-server 未运行。"
    }

    private suspend fun buildBackendFailureDetail(
        prefix: String,
        stdout: String,
        stderr: String,
    ): String {
        val parts = mutableListOf(prefix)
        stdout.trim().takeIf { it.isNotBlank() }?.let { parts += "stdout:\n$it" }
        stderr.trim().takeIf { it.isNotBlank() }?.let { parts += "stderr:\n$it" }
        backendManager.readBackendLogTail().takeIf { it.isNotBlank() }?.let { parts += "log:\n$it" }
        return parts.joinToString("\n\n")
    }

    private fun openBrowserUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    companion object {
        private const val PREFS_NAME = "codex_mobile_prefs"
        private const val PREF_AUTO_HARDENING = "auto_hardening_enabled"
    }
}
