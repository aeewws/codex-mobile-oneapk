package io.github.aeewws.codexmobile.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val LOGIN_TIMEOUT_MS = 30_000L

data class AuthSnapshot(
    val authPresent: Boolean,
    val authMode: String = "",
    val accountId: String = "",
)

data class LoginResult(
    val started: Boolean,
    val auth: AuthSnapshot,
    val browserUrl: String = "",
    val detail: String = "",
    val error: String = "",
)

data class DeviceLoginStartResult(
    val started: Boolean,
    val auth: AuthSnapshot,
    val verificationUrl: String = "",
    val userCode: String = "",
    val detail: String = "",
    val error: String = "",
)

class CodexAuthManager(
    private val runtimeManager: EmbeddedRuntimeManager,
) {
    private data class RunningAuthProcess(
        val process: Process,
        val output: StringBuilder,
        val readerThread: Thread,
        val logFile: File,
    )

    @Volatile
    private var activeLoginProcess: RunningAuthProcess? = null

    fun readAuthSnapshot(): AuthSnapshot {
        val file = runtimeManager.authFile()
        if (!file.exists()) {
            return AuthSnapshot(authPresent = false)
        }
        return runCatching {
            val json = JSONObject(file.readText())
            val apiKey = json.optString("OPENAI_API_KEY")
            val tokens = json.optJSONObject("tokens")
            val hasTokens =
                tokens?.let { candidate ->
                    candidate.optString("id_token").isNotBlank() ||
                        candidate.optString("access_token").isNotBlank() ||
                        candidate.optString("refresh_token").isNotBlank() ||
                        candidate.optString("account_id").isNotBlank()
                } == true
            val authPresent = apiKey.isNotBlank() || hasTokens
            val authMode = json.optString("auth_mode").ifBlank { inferAuthMode(json) }
            val accountId = tokens?.optString("account_id").orEmpty()
            AuthSnapshot(
                authPresent = authPresent,
                authMode = authMode,
                accountId = accountId,
            )
        }.getOrElse {
            AuthSnapshot(authPresent = false)
        }
    }

    suspend fun checkLoginStatus(): AuthSnapshot = withContext(Dispatchers.IO) {
        val auth = readAuthSnapshot()
        if (auth.authPresent) {
            stopActiveLoginProcess()
        } else {
            activeLoginProcess?.takeIf { !it.process.isAlive }?.let { stopActiveLoginProcess() }
        }
        auth
    }

    suspend fun startLogin(onBrowserUrl: (String) -> Unit = {}): LoginResult = withContext(Dispatchers.IO) {
        val runtime = runtimeManager.ensureRuntimeReady()
        if (!runtime.runtimeReady) {
            return@withContext LoginResult(
                started = false,
                auth = readAuthSnapshot(),
                error = runtime.detail,
            )
        }
        val codexBinary = runtimeManager.resolveLaunchBinary()
            ?: return@withContext LoginResult(
                started = false,
                auth = readAuthSnapshot(),
                error = "本机运行时已解压，但当前无法执行 Codex 二进制；请确认 root 已授权。",
            )
        if (runtimeManager.shouldUseRootExecution(codexBinary)) {
            runtimeManager.prepareRootManagedStateFiles()
        }

        val running = launchLoginProcess(runtime, codexBinary, deviceAuth = false)
            ?: return@withContext LoginResult(
                started = false,
                auth = readAuthSnapshot(),
                error = "登录流程启动失败",
            )

        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
        var browserUrl = ""
        while (System.currentTimeMillis() < deadline) {
            browserUrl = browserUrl.ifBlank { extractBrowserUrl(running) }
            val auth = readAuthSnapshot()
            if (auth.authPresent) {
                stopActiveLoginProcess()
                return@withContext LoginResult(
                    started = true,
                    auth = auth,
                    browserUrl = browserUrl,
                    detail = "登录已完成。",
                )
            }
            if (browserUrl.isNotBlank()) {
                onBrowserUrl(browserUrl)
                return@withContext LoginResult(
                    started = true,
                    auth = auth,
                    browserUrl = browserUrl,
                    detail = "浏览器登录已启动，请在浏览器完成授权。",
                )
            }
            if (!running.process.isAlive) {
                break
            }
            Thread.sleep(300L)
        }

        val output = readOutput(running)
        stopActiveLoginProcess()
        return@withContext LoginResult(
            started = false,
            auth = readAuthSnapshot(),
            error = output.ifBlank { "登录流程没有返回可用信息" },
        )
    }

    suspend fun startDeviceLogin(onBrowserUrl: (String) -> Unit = {}): DeviceLoginStartResult = withContext(Dispatchers.IO) {
        val runtime = runtimeManager.ensureRuntimeReady()
        if (!runtime.runtimeReady) {
            return@withContext DeviceLoginStartResult(
                started = false,
                auth = readAuthSnapshot(),
                error = runtime.detail,
            )
        }
        val codexBinary = runtimeManager.resolveLaunchBinary()
            ?: return@withContext DeviceLoginStartResult(
                started = false,
                auth = readAuthSnapshot(),
                error = "本机运行时已解压，但当前无法执行 Codex 二进制；请确认 root 已授权。",
            )
        if (runtimeManager.shouldUseRootExecution(codexBinary)) {
            runtimeManager.prepareRootManagedStateFiles()
        }

        val running = launchLoginProcess(runtime, codexBinary, deviceAuth = true)
            ?: return@withContext DeviceLoginStartResult(
                started = false,
                auth = readAuthSnapshot(),
                error = "设备码登录启动失败",
            )

        val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
        var verificationUrl = ""
        var userCode = ""
        var detail = ""
        while (System.currentTimeMillis() < deadline) {
            val parsed = parseDeviceAuth(running)
            if (parsed.verificationUrl.isNotBlank()) {
                verificationUrl = parsed.verificationUrl
            }
            if (parsed.userCode.isNotBlank()) {
                userCode = parsed.userCode
            }
            if (parsed.detail.isNotBlank()) {
                detail = parsed.detail
            }
            val auth = readAuthSnapshot()
            if (auth.authPresent) {
                stopActiveLoginProcess()
                return@withContext DeviceLoginStartResult(
                    started = true,
                    auth = auth,
                    verificationUrl = verificationUrl,
                    userCode = userCode,
                    detail = "登录已完成。",
                )
            }
            if (verificationUrl.isNotBlank() || userCode.isNotBlank()) {
                if (verificationUrl.isNotBlank()) {
                    onBrowserUrl(verificationUrl)
                }
                val hint = when {
                    verificationUrl.isNotBlank() && userCode.isNotBlank() -> "请在浏览器打开验证地址，然后输入一次性设备码完成授权。"
                    verificationUrl.isNotBlank() -> "设备码流程未返回 code，可能已回退到普通网页登录。"
                    else -> detail.ifBlank { "设备码登录已启动。" }
                }
                return@withContext DeviceLoginStartResult(
                    started = true,
                    auth = auth,
                    verificationUrl = verificationUrl,
                    userCode = userCode,
                    detail = hint,
                )
            }
            if (!running.process.isAlive) {
                break
            }
            Thread.sleep(300L)
        }

        val output = readOutput(running)
        stopActiveLoginProcess()
        return@withContext DeviceLoginStartResult(
            started = false,
            auth = readAuthSnapshot(),
            error = output.ifBlank { "设备码登录没有返回可用信息" },
        )
    }

    suspend fun logout(): AuthSnapshot = withContext(Dispatchers.IO) {
        stopActiveLoginProcess()
        runtimeManager.deleteWithRootFallback(runtimeManager.authFile())
        runtimeManager.deleteWithRootFallback(runtimeManager.loginLogFile())
        readAuthSnapshot()
    }

    private fun launchLoginProcess(
        runtime: EmbeddedRuntimeSnapshot,
        codexBinary: File,
        deviceAuth: Boolean,
    ): RunningAuthProcess? {
        stopActiveLoginProcess()
        val logFile = runtimeManager.loginLogFile().apply {
            parentFile?.mkdirs()
            writeText("")
        }
        val process = try {
            if (runtimeManager.shouldUseRootExecution(codexBinary)) {
                RootShell.start(
                    command = buildForegroundLoginCommand(runtime, codexBinary, deviceAuth),
                )
            } else {
                ProcessBuilder(buildLoginArgs(codexBinary, deviceAuth))
                    .directory(runtime.codexHomeDir)
                    .redirectErrorStream(true)
                    .start()
                    .apply {
                        outputStream.close()
                    }
            }
        } catch (_: Throwable) {
            return null
        }

        val output = StringBuilder()
        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        output.append(line).append('\n')
                    }
                    runCatching {
                        logFile.appendText(line + "\n")
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        process.errorStream.closeQuietly()
        val running = RunningAuthProcess(process, output, readerThread, logFile)
        activeLoginProcess = running
        return running
    }

    private fun readOutput(running: RunningAuthProcess): String = synchronized(running.output) {
        stripAnsi(running.output.toString()).trim()
    }

    private fun extractBrowserUrl(running: RunningAuthProcess): String =
        URL_REGEX.find(readOutput(running))?.value.orEmpty()

    private fun inferAuthMode(json: JSONObject): String {
        return when {
            json.optString("OPENAI_API_KEY").isNotBlank() -> "api"
            json.optJSONObject("tokens")?.optString("refresh_token").orEmpty().isNotBlank() -> "chatgpt"
            else -> ""
        }
    }

    private fun parseDeviceAuth(running: RunningAuthProcess): DeviceLoginStartResult {
        val text = readOutput(running)
        val verificationUrl = URL_REGEX.find(text)?.value.orEmpty()
        val userCode = DEVICE_CODE_REGEX.find(text)?.value.orEmpty()
        val detail = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(6)
            .joinToString("\n")
        return DeviceLoginStartResult(
            started = verificationUrl.isNotBlank() || userCode.isNotBlank(),
            auth = readAuthSnapshot(),
            verificationUrl = verificationUrl,
            userCode = userCode,
            detail = detail,
        )
    }

    private fun buildForegroundLoginCommand(
        runtime: EmbeddedRuntimeSnapshot,
        codexBinary: File,
        deviceAuth: Boolean,
    ): String =
        buildString {
            append("umask 022; ")
            append("cd ")
            append(RootShell.shellQuote(runtime.codexHomeDir.absolutePath))
            append("; export HOME=")
            append(RootShell.shellQuote(runtime.codexHomeDir.absolutePath))
            append(" CODEX_HOME=")
            append(RootShell.shellQuote(runtime.codexStateDir.absolutePath))
            append(" PATH=")
            append(RootShell.shellQuote(runtimeManager.runtimeEnvironment(runtime).getValue("PATH")))
            append("; exec ")
            append(RootShell.shellQuote(codexBinary.absolutePath))
            append(" login")
            if (deviceAuth) {
                append(" --device-auth")
            }
        }

    private fun buildLoginArgs(codexBinary: File, deviceAuth: Boolean): List<String> = buildList {
        add(codexBinary.absolutePath)
        add("login")
        if (deviceAuth) {
            add("--device-auth")
        }
    }

    private fun stopActiveLoginProcess() {
        activeLoginProcess?.let { running ->
            if (running.process.isAlive) {
                running.process.destroy()
                runCatching { running.process.waitFor(2, TimeUnit.SECONDS) }
                if (running.process.isAlive) {
                    running.process.destroyForcibly()
                }
            }
        }
        activeLoginProcess = null
    }

    private fun AutoCloseable?.closeQuietly() {
        runCatching { this?.close() }
    }

    private companion object {
        val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[;\\d]*m")
        val URL_REGEX = Regex("https://[^\\s\"']+")
        val DEVICE_CODE_REGEX = Regex("\\b[A-Z0-9]{6,12}\\b|\\b[A-Z0-9]{4,6}(?:-[A-Z0-9]{4,6})+\\b")
    }

    private fun stripAnsi(value: String): String =
        ANSI_ESCAPE_REGEX.replace(value, "")
}
