package io.github.aeewws.codexmobile.runtime

import android.content.Context
import android.os.Build
import io.github.aeewws.codexmobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val RUNTIME_ABI = "arm64-v8a"

data class EmbeddedRuntimeSnapshot(
    val runtimeReady: Boolean,
    val runtimePackaged: Boolean,
    val runtimeAbi: String,
    val runtimeVersion: String,
    val detail: String,
    val runtimeDir: File,
    val codexHomeDir: File,
    val codexStateDir: File,
    val codexBinary: File?,
)

class EmbeddedRuntimeManager(private val context: Context) {
    suspend fun ensureRuntimeReady(): EmbeddedRuntimeSnapshot = withContext(Dispatchers.IO) {
        ensureCodexHomeScaffold()
        val supportedAbi = Build.SUPPORTED_ABIS.any { it.equals(RUNTIME_ABI, ignoreCase = true) }
        if (!supportedAbi) {
            return@withContext snapshot(
                runtimeReady = false,
                detail = "当前设备不是 arm64-v8a，首版单 APK 只支持 arm64 运行时。",
                codexBinary = findCodexBinary(runtimeDir()),
            )
        }
        if (!BuildConfig.CODEX_RUNTIME_PACKAGED) {
            return@withContext snapshot(
                runtimeReady = false,
                detail = "APK 未内置 Codex runtime 包；构建时请提供 codexMobile.runtime.archive 或 CODEX_MOBILE_RUNTIME_ARCHIVE。",
                codexBinary = null,
            )
        }

        val versionFile = runtimeVersionMarker()
        val extractedVersion = versionFile.takeIf(File::exists)?.readText()?.trim()
        if (extractedVersion != BuildConfig.CODEX_RUNTIME_VERSION || findCodexBinary(runtimeDir()) == null) {
            extractRuntimeAsset()
            versionFile.writeText(BuildConfig.CODEX_RUNTIME_VERSION)
        }
        ensureCodexHomeConfig()

        val codexBinary = findCodexBinary(runtimeDir())
        return@withContext if (codexBinary != null && codexBinary.canExecute()) {
            snapshot(
                runtimeReady = true,
                detail = "内置 runtime 已就绪。",
                codexBinary = codexBinary,
            )
        } else {
            snapshot(
                runtimeReady = false,
                detail = "已解压 runtime，但没有找到可执行的 codex 二进制。",
                codexBinary = codexBinary,
            )
        }
    }

    fun inspect(): EmbeddedRuntimeSnapshot = snapshot(
        runtimeReady = findCodexBinary(runtimeDir())?.canExecute() == true,
        detail = "等待检查内置 runtime 状态。",
        codexBinary = findCodexBinary(runtimeDir()),
    )

    fun defaultWorkingDirectory(): String = codexHomeDir().absolutePath

    fun runtimeDir(): File = File(context.filesDir, "embedded-runtime/$runtimeVersionName")

    fun codexHomeDir(): File = File(context.filesDir, "codex-home")

    fun codexStateDir(): File = File(codexHomeDir(), ".codex")

    fun backendAttachmentDirectory(): File = File(context.cacheDir, "codex-mobile-backend-attachments")

    fun authFile(): File = File(codexStateDir(), "auth.json")

    fun configFile(): File = File(codexStateDir(), "config.toml")

    fun backendLogFile(): File = File(codexHomeDir(), ".codex-app-server.log")

    fun backendPidFile(): File = File(codexHomeDir(), ".codex-app-server.pid")

    fun loginLogFile(): File = File(codexHomeDir(), ".codex-login.log")

    fun sessionIndexFile(): File = File(codexStateDir(), "session_index.jsonl")

    fun appUid(): Int = context.applicationInfo.uid

    fun shouldUseRootExecution(binary: File?): Boolean =
        binary?.absolutePath?.startsWith("/data/local/tmp/") == true

    suspend fun prepareRootManagedStateFiles() = withContext(Dispatchers.IO) {
        ensureCodexHomeScaffold()
        val managedDirs = buildList {
            add(codexHomeDir())
            add(codexStateDir())
            add(backendAttachmentDirectory())
            addAll(sessionRoots())
        }
        managedDirs.forEach { dir ->
            dir.mkdirs()
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }

        val auth = authFile()
        if (!auth.exists()) {
            auth.parentFile?.mkdirs()
            auth.writeText("{}\n")
        }
        val managedFiles = listOf(
            auth,
            configFile(),
            backendLogFile(),
            backendPidFile(),
            loginLogFile(),
            sessionIndexFile(),
        )
        managedFiles.forEach { file ->
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                file.writeText("")
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
        }
    }

    suspend fun deleteWithRootFallback(file: File): Boolean = withContext(Dispatchers.IO) {
        if (file.delete()) {
            return@withContext true
        }
        val result = RootShell.run(
            command = "rm -f ${RootShell.shellQuote(file.absolutePath)}",
            timeoutMillis = 6_000L,
        )
        result.exitCode == 0 && !file.exists()
    }

    suspend fun resolveLaunchBinary(): File? = withContext(Dispatchers.IO) {
        val snapshot = ensureRuntimeReady()
        val bundledBinary = snapshot.codexBinary ?: return@withContext null
        val localProbe = LocalShell.runArgs(
            command = listOf(bundledBinary.absolutePath, "--version"),
            workingDirectory = snapshot.codexHomeDir,
            environment = runtimeEnvironment(snapshot),
            timeoutMillis = 4_000L,
        )
        val rootAvailable = RootShell.run("id", timeoutMillis = 4_000L).let { result ->
            result.exitCode == 0 && result.stdout.contains("uid=0")
        }

        if (rootAvailable) {
            val mirroredBinary = ensureRootLaunchMirror(snapshot)
            if (mirroredBinary != null) {
                val mirroredProbe = RootShell.run(
                    command = buildRootProbeCommand(snapshot, mirroredBinary),
                    uid = appUid(),
                    timeoutMillis = 4_000L,
                )
                if (mirroredProbe.exitCode == 0) {
                    return@withContext mirroredBinary
                }
            }
        }

        if (localProbe.exitCode == 0) bundledBinary else null
    }

    fun runtimeEnvironment(snapshot: EmbeddedRuntimeSnapshot = inspect()): Map<String, String> {
        val binDir = snapshot.codexBinary?.parentFile?.absolutePath.orEmpty()
        val existingPath = System.getenv("PATH").orEmpty()
        val pathPrefix = listOf(binDir, "/system/bin", "/system/xbin")
            .filter { it.isNotBlank() }
            .joinToString(":")
        return linkedMapOf(
            "HOME" to codexHomeDir().absolutePath,
            "CODEX_HOME" to codexStateDir().absolutePath,
            "PATH" to listOf(pathPrefix, existingPath).filter { it.isNotBlank() }.joinToString(":"),
        )
    }

    fun sessionRoots(): List<File> = listOf(
        File(codexStateDir(), "sessions"),
        File(codexStateDir(), "archived_sessions"),
    )

    private fun runtimeVersionMarker(): File = File(runtimeDir(), ".embedded-runtime-version")

    private suspend fun ensureRootLaunchMirror(snapshot: EmbeddedRuntimeSnapshot): File? {
        val sourceRoot = snapshot.runtimeDir
        if (!sourceRoot.exists()) return null
        val targetRoot = File("/data/local/tmp/codex-mobile-runtime/${snapshot.runtimeVersion}")
        val command = buildString {
            append("src=")
            append(RootShell.shellQuote(sourceRoot.absolutePath))
            append("; dst=")
            append(RootShell.shellQuote(targetRoot.absolutePath))
            append("; rm -rf \"${'$'}dst\"; mkdir -p \"${'$'}dst\"; cp -R \"${'$'}src\"/. \"${'$'}dst\"/; ")
            append("find \"${'$'}dst\" -type d -exec chmod 755 {} +; ")
            append("find \"${'$'}dst\" -type f -name 'codex*' -exec chmod 755 {} +")
        }
        val result = RootShell.run(command = command, timeoutMillis = 12_000L)
        if (result.exitCode != 0) {
            return null
        }
        return findCodexBinary(targetRoot)
    }

    private fun buildRootProbeCommand(snapshot: EmbeddedRuntimeSnapshot, binary: File): String =
        buildString {
            append("export HOME=")
            append(RootShell.shellQuote(snapshot.codexHomeDir.absolutePath))
            append(" CODEX_HOME=")
            append(RootShell.shellQuote(snapshot.codexStateDir.absolutePath))
            append(" PATH=")
            append(RootShell.shellQuote(runtimeEnvironment(snapshot).getValue("PATH")))
            append("; ")
            append(RootShell.shellQuote(binary.absolutePath))
            append(" --version >/dev/null 2>&1")
        }

    private fun extractRuntimeAsset() {
        val destination = runtimeDir()
        destination.deleteRecursively()
        destination.mkdirs()
        context.assets.open(BuildConfig.CODEX_RUNTIME_ASSET).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = destination.resolve(entry.name).normalize()
                    if (!target.canonicalPath.startsWith(destination.canonicalPath)) {
                        throw IllegalStateException("Unsafe runtime archive entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        findCodexBinary(destination)?.let { codex ->
            codex.setExecutable(true, false)
            codex.parentFile?.listFiles()?.forEach { child ->
                if (child.isFile) {
                    child.setExecutable(true, false)
                }
            }
        }
    }

    private fun ensureCodexHomeScaffold() {
        codexHomeDir().mkdirs()
        codexStateDir().mkdirs()
        sessionRoots().forEach(File::mkdirs)
        backendAttachmentDirectory().mkdirs()
    }

    private fun ensureCodexHomeConfig() {
        val file = configFile()
        val current = file.takeIf(File::exists)?.readText().orEmpty()
        var updated = current
        updated = upsertTomlKey(updated, "cli_auth_credentials_store", "\"file\"")
        updated = upsertTomlKey(updated, "forced_login_method", "\"chatgpt\"")
        if (updated != current || !file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(updated.trimEnd() + "\n")
        }
    }

    private fun upsertTomlKey(content: String, key: String, value: String): String {
        val regex = Regex("(?m)^\\s*${Regex.escape(key)}\\s*=.*$")
        return when {
            regex.containsMatchIn(content) -> content.replace(regex, "$key = $value")
            content.isBlank() -> "$key = $value\n"
            else -> content.trimEnd() + "\n$key = $value\n"
        }
    }

    private fun snapshot(
        runtimeReady: Boolean,
        detail: String,
        codexBinary: File?,
    ): EmbeddedRuntimeSnapshot = EmbeddedRuntimeSnapshot(
        runtimeReady = runtimeReady,
        runtimePackaged = BuildConfig.CODEX_RUNTIME_PACKAGED,
        runtimeAbi = RUNTIME_ABI,
        runtimeVersion = runtimeVersionName,
        detail = detail,
        runtimeDir = runtimeDir(),
        codexHomeDir = codexHomeDir(),
        codexStateDir = codexStateDir(),
        codexBinary = codexBinary,
    )

    private fun findCodexBinary(root: File): File? {
        if (!root.exists()) return null
        val files = root.walkTopDown()
            .filter { it.isFile }
            .toList()
        val preferredNames = listOf("codex.bin", "codex-aarch64-unknown-linux-musl", "codex")
        preferredNames.forEach { preferred ->
            files.firstOrNull { file -> file.name == preferred }?.let { return it }
        }
        return files.firstOrNull { file ->
            file.name.startsWith("codex") &&
                !file.name.startsWith("codex-exec") &&
                !file.name.endsWith(".js")
        } ?: files.firstOrNull { file ->
            file.name.startsWith("codex") && !file.name.endsWith(".js")
        }
    }

    private val runtimeVersionName: String
        get() = BuildConfig.CODEX_RUNTIME_VERSION.ifBlank { "embedded-dev" }
}
