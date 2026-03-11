package rockopera.workspace

import org.slf4j.LoggerFactory
import rockopera.config.WorkflowConfig
import rockopera.model.Workspace
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceManager(private val config: WorkflowConfig) {
    private val log = LoggerFactory.getLogger(WorkspaceManager::class.java)

    private val workspaceRoot: Path by lazy {
        val root = config.workspaceRoot
            ?: (System.getProperty("java.io.tmpdir") + "/rockopera_workspaces")
        Path.of(root).toAbsolutePath()
    }

    fun createOrReuse(issueIdentifier: String): Workspace {
        val key = sanitizeKey(issueIdentifier)
        val wsPath = workspaceRoot.resolve(key)

        validatePathContainment(wsPath)

        val dir = wsPath.toFile()
        val createdNow: Boolean

        when {
            dir.isDirectory -> {
                // Reuse existing, clean temp artifacts
                cleanTempArtifacts(dir)
                createdNow = false
            }
            dir.exists() -> {
                // File exists but is not a directory — remove and create
                dir.delete()
                dir.mkdirs()
                createdNow = true
            }
            else -> {
                dir.mkdirs()
                createdNow = true
            }
        }

        if (createdNow) {
            runHook(config.hookAfterCreate, wsPath, "after_create", fatal = true)
        }

        log.info("Workspace ready: path={}, key={}, createdNow={}", wsPath, key, createdNow)
        return Workspace(
            path = wsPath.toString(),
            workspaceKey = key,
            createdNow = createdNow
        )
    }

    fun remove(issueIdentifier: String) {
        val key = sanitizeKey(issueIdentifier)
        val wsPath = workspaceRoot.resolve(key)
        val dir = wsPath.toFile()

        if (dir.exists()) {
            runHook(config.hookBeforeRemove, wsPath, "before_remove", fatal = false)
            dir.deleteRecursively()
            log.info("Workspace removed: {}", wsPath)
        }
    }

    fun runBeforeRunHook(workspacePath: Path) {
        runHook(config.hookBeforeRun, workspacePath, "before_run", fatal = true)
    }

    fun runAfterRunHook(workspacePath: Path) {
        runHook(config.hookAfterRun, workspacePath, "after_run", fatal = false)
    }

    private fun runHook(script: String?, workspacePath: Path, hookName: String, fatal: Boolean) {
        if (script.isNullOrBlank()) return

        log.info("Running hook {}: {}", hookName, script.take(80))
        try {
            val process = ProcessBuilder("bash", "-lc", script)
                .directory(workspacePath.toFile())
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(config.hookTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                val msg = "Hook $hookName timed out after ${config.hookTimeoutMs}ms"
                if (fatal) throw RuntimeException(msg) else log.warn(msg)
                return
            }

            val output = process.inputStream.bufferedReader().readText().take(2048)
            val exitCode = process.exitValue()

            if (exitCode != 0) {
                val msg = "Hook $hookName failed with exit code $exitCode: $output"
                if (fatal) throw RuntimeException(msg) else log.warn(msg)
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            val msg = "Hook $hookName error: ${e.message}"
            if (fatal) throw RuntimeException(msg, e) else log.warn(msg, e)
        }
    }

    private fun cleanTempArtifacts(dir: File) {
        listOf(".elixir_ls", "tmp").forEach { name ->
            val artifact = File(dir, name)
            if (artifact.exists()) {
                artifact.deleteRecursively()
            }
        }
    }

    private fun validatePathContainment(wsPath: Path) {
        val normalizedRoot = workspaceRoot.normalize()
        val normalizedWs = wsPath.normalize()

        require(normalizedWs.startsWith(normalizedRoot)) {
            "Workspace path escapes root: $normalizedWs is not under $normalizedRoot"
        }
        require(normalizedWs != normalizedRoot) {
            "Workspace path must not equal workspace root"
        }

        // Check for symlink escapes
        if (Files.exists(wsPath)) {
            val realPath = wsPath.toRealPath()
            val realRoot = workspaceRoot.toRealPath()
            require(realPath.startsWith(realRoot)) {
                "Symlink escape detected: real path $realPath is not under $realRoot"
            }
        }
    }

    companion object {
        fun sanitizeKey(identifier: String): String =
            identifier.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
