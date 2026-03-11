package rockopera.agent

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Simple CLI agent client that launches a configurable command,
 * pipes the prompt via stdin, and reads stdout line-by-line.
 *
 * Works with any CLI coding agent: claude, opencode, custom scripts, etc.
 * Example command: "claude -p --output-format stream-json --dangerously-skip-permissions"
 */
class CliAgentClient(
    private val command: String,
    private val workspacePath: String,
    private val env: Map<String, String> = emptyMap()
) {
    private val log = LoggerFactory.getLogger(CliAgentClient::class.java)
    private var process: Process? = null

    val pid: String? get() = process?.pid()?.toString()
    val isAlive: Boolean get() = process?.isAlive == true

    fun start(prompt: String): String {
        log.info("Launching agent: cmd={}, cwd={}", command, workspacePath)

        val pb = ProcessBuilder("sh", "-c", command)
            .directory(File(workspacePath))
            .redirectErrorStream(false)

        pb.environment().putAll(env)

        process = pb.start()
        val pidStr = process!!.pid().toString()
        log.info("Agent process started: pid={}", pidStr)

        // Write prompt to stdin and close it
        process!!.outputStream.bufferedWriter().use { writer ->
            writer.write(prompt)
            writer.flush()
        }

        return pidStr
    }

    fun readLine(): String? {
        return process?.inputStream?.bufferedReader()?.readLine()
    }

    fun readStderrLine(): String? {
        return process?.errorStream?.bufferedReader()?.readLine()
    }

    /**
     * Read all stdout until process exits or timeout.
     * Returns collected output lines.
     */
    suspend fun readAllOutput(
        timeoutMs: Long,
        onLine: suspend (String) -> Unit = {}
    ): AgentOutput {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs

        val proc = process ?: return AgentOutput("", "Process not started", -1)

        // Read stdout in IO dispatcher
        val stdoutJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null && isActive) {
                        stdout.appendLine(line)
                        onLine(line)
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {}
        }

        // Read stderr in parallel
        val stderrJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                proc.errorStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null && isActive) {
                        stderr.appendLine(line)
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {}
        }

        // Wait for process to complete or timeout
        val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        val exited = withContext(Dispatchers.IO) {
            proc.waitFor(remainingMs, TimeUnit.MILLISECONDS)
        }

        if (!exited) {
            log.warn("Agent process timed out after {}ms, destroying", timeoutMs)
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
        }

        stdoutJob.join()
        stderrJob.join()

        val exitCode = if (exited) proc.exitValue() else -1
        return AgentOutput(stdout.toString(), stderr.toString(), exitCode)
    }

    fun stop() {
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                }
                log.info("Agent process stopped: pid={}", p.pid())
            }
        }
        process = null
    }
}

data class AgentOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
) {
    val isSuccess: Boolean get() = exitCode == 0
}
