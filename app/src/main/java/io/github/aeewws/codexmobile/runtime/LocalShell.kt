package io.github.aeewws.codexmobile.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object LocalShell {
    suspend fun run(
        command: String,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 15_000L,
    ): ShellResult = withContext(Dispatchers.IO) {
        runProcess(
            command = listOf(SYSTEM_SHELL, "-c", command),
            workingDirectory = workingDirectory,
            environment = environment,
            timeoutMillis = timeoutMillis,
        )
    }

    suspend fun runArgs(
        command: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutMillis: Long = 15_000L,
    ): ShellResult = withContext(Dispatchers.IO) {
        runProcess(
            command = command,
            workingDirectory = workingDirectory,
            environment = environment,
            timeoutMillis = timeoutMillis,
        )
    }

    private fun runProcess(
        command: List<String>,
        workingDirectory: File?,
        environment: Map<String, String>,
        timeoutMillis: Long,
    ): ShellResult {
        val process = try {
            ProcessBuilder(command)
                .apply {
                    if (workingDirectory != null) {
                        directory(workingDirectory)
                    }
                    environment().putAll(environment)
                }
                .start()
        } catch (t: Throwable) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = t.message ?: t.javaClass.simpleName,
            )
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread {
            process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
        }
        val stderrThread = Thread {
            process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
        }
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        stdoutThread.join(1_000L)
        stderrThread.join(1_000L)

        return if (finished) {
            ShellResult(
                exitCode = process.exitValue(),
                stdout = stdout.toString(),
                stderr = stderr.toString(),
            )
        } else {
            ShellResult(
                exitCode = -1,
                stdout = stdout.toString(),
                stderr = (stderr.toString() + "\nTimed out after ${timeoutMillis}ms").trim(),
            )
        }
    }

    private const val SYSTEM_SHELL = "/system/bin/sh"
}
