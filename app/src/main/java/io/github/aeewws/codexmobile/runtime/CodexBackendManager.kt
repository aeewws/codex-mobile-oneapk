package io.github.aeewws.codexmobile.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private const val LOCAL_HOST = "127.0.0.1"
private const val APP_SERVER_PORT = 8765

class CodexBackendManager(
    private val runtimeManager: EmbeddedRuntimeManager,
) {
    suspend fun isBackendListening(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOCAL_HOST, APP_SERVER_PORT), 600)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun ensureBackendRunning(): ShellResult {
        val runtime = runtimeManager.ensureRuntimeReady()
        val codexBinary = runtimeManager.resolveLaunchBinary()
            ?: return ShellResult(-1, "", runtime.detail.ifBlank { "找不到 codex 二进制" })
        if (isBackendListening()) {
            return ShellResult(0, "already-listening", "")
        }
        if (runtimeManager.shouldUseRootExecution(codexBinary)) {
            runtimeManager.prepareRootManagedStateFiles()
        }

        val logFile = runtimeManager.backendLogFile().apply {
            parentFile?.mkdirs()
            writeText("")
        }
        val pidFile = runtimeManager.backendPidFile().apply {
            parentFile?.mkdirs()
            writeText("")
        }
        val command = buildString {
            append("umask 022; ")
            append("export HOME=")
            append(RootShell.shellQuote(runtime.codexHomeDir.absolutePath))
            append(" CODEX_HOME=")
            append(RootShell.shellQuote(runtime.codexStateDir.absolutePath))
            append(" PATH=")
            append(RootShell.shellQuote(runtimeManager.runtimeEnvironment(runtime).getValue("PATH")))
            append("; rm -f ")
            append(RootShell.shellQuote(pidFile.absolutePath))
            append("; nohup ")
            append(RootShell.shellQuote(codexBinary.absolutePath))
            append(" app-server --listen ws://")
            append(LOCAL_HOST)
            append(":")
            append(APP_SERVER_PORT)
            append(" >")
            append(RootShell.shellQuote(logFile.absolutePath))
            append(" 2>&1 < /dev/null & echo ${'$'}! > ")
            append(RootShell.shellQuote(pidFile.absolutePath))
        }

        val result = if (runtimeManager.shouldUseRootExecution(codexBinary)) {
            RootShell.run(command = command, timeoutMillis = 10_000L)
        } else {
            LocalShell.run(command = command, timeoutMillis = 10_000L)
        }
        if (result.exitCode != 0) {
            return result
        }
        repeat(10) {
            if (isBackendListening()) {
                return ShellResult(0, "started", "")
            }
            delay(700L)
        }
        return ShellResult(-1, readBackendLogTail(), "端口 8765 没有起来")
    }

    suspend fun restartBackend(): ShellResult {
        stopBackend()
        return ensureBackendRunning()
    }

    suspend fun stopBackend(): ShellResult {
        val pidFile = runtimeManager.backendPidFile()
        val codexBinary = runtimeManager.resolveLaunchBinary()
        val command = buildString {
            append("if [ -f ")
            append(RootShell.shellQuote(pidFile.absolutePath))
            append(" ]; then pid=$(cat ")
            append(RootShell.shellQuote(pidFile.absolutePath))
            append("); kill \"${'$'}pid\" >/dev/null 2>&1 || true; sleep 1; kill -9 \"${'$'}pid\" >/dev/null 2>&1 || true; fi; rm -f ")
            append(RootShell.shellQuote(pidFile.absolutePath))
            if (codexBinary != null) {
                append("; pkill -f ")
                append(RootShell.shellQuote("${codexBinary.absolutePath} app-server --listen ws://$LOCAL_HOST:$APP_SERVER_PORT"))
                append(" >/dev/null 2>&1 || true")
            }
        }
        val result = if (runtimeManager.shouldUseRootExecution(codexBinary)) {
            RootShell.run(command = command, timeoutMillis = 8_000L)
        } else {
            LocalShell.run(command = command, timeoutMillis = 8_000L)
        }
        repeat(8) {
            if (!isBackendListening()) {
                runtimeManager.deleteWithRootFallback(runtimeManager.backendPidFile())
                return ShellResult(0, "stopped", "")
            }
            delay(400L)
        }
        return if (result.exitCode == 0) {
            ShellResult(-1, readBackendLogTail(), "app-server 停止失败")
        } else {
            result
        }
    }

    suspend fun readBackendLogTail(maxLines: Int = 60): String = withContext(Dispatchers.IO) {
        val logFile = runtimeManager.backendLogFile()
        if (!logFile.exists()) {
            return@withContext ""
        }
        logFile.readLines()
            .takeLast(maxLines)
            .joinToString("\n")
            .trim()
    }
}
