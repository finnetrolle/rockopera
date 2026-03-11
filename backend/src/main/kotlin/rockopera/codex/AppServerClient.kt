package rockopera.codex

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppServerClient(
    private val command: String,
    private val workspacePath: String,
    private val readTimeoutMs: Long = 5_000
) {
    private val log = LoggerFactory.getLogger(AppServerClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val requestIdCounter = AtomicInteger(0)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    val pid: String? get() = process?.pid()?.toString()
    val isAlive: Boolean get() = process?.isAlive == true

    fun start(): String {
        log.info("Launching Codex app-server: cmd={}, cwd={}", command, workspacePath)

        val pb = ProcessBuilder("bash", "-lc", command)
            .directory(File(workspacePath))
            .redirectErrorStream(false)

        process = pb.start()
        writer = process!!.outputStream.bufferedWriter()
        reader = process!!.inputStream.bufferedReader()

        val pidStr = process!!.pid().toString()
        log.info("Codex app-server started: pid={}", pidStr)
        return pidStr
    }

    suspend fun initialize(): JsonObject {
        val id = nextId()
        val request = buildJsonObject {
            put("id", id)
            put("method", "initialize")
            putJsonObject("params") {
                putJsonObject("clientInfo") {
                    put("name", "rockopera-orchestrator")
                    put("title", "RockOpera Orchestrator")
                    put("version", "0.1.0")
                }
                putJsonObject("capabilities") {
                    put("experimentalApi", true)
                }
            }
        }

        sendMessage(request)
        val response = readResponse(id)

        // Send initialized notification (no response expected)
        val initialized = buildJsonObject {
            put("method", "initialized")
            putJsonObject("params") {}
        }
        sendMessage(initialized)

        return response
    }

    suspend fun startThread(
        approvalPolicy: Any?,
        sandbox: String,
        cwd: String,
        dynamicTools: List<JsonObject>
    ): String {
        val id = nextId()
        val request = buildJsonObject {
            put("id", id)
            put("method", "thread/start")
            putJsonObject("params") {
                when (approvalPolicy) {
                    is String -> put("approvalPolicy", approvalPolicy)
                    is JsonElement -> put("approvalPolicy", approvalPolicy)
                    else -> put("approvalPolicy", "never")
                }
                put("sandbox", sandbox)
                put("cwd", cwd)
                putJsonArray("dynamicTools") {
                    dynamicTools.forEach { add(it) }
                }
            }
        }

        sendMessage(request)
        val response = readResponse(id)

        return response["result"]?.jsonObject
            ?.get("thread")?.jsonObject
            ?.get("id")?.jsonPrimitive?.content
            ?: throw CodexProtocolException("Missing thread.id in thread/start response")
    }

    suspend fun startTurn(
        threadId: String,
        prompt: String,
        cwd: String,
        title: String,
        approvalPolicy: Any?,
        sandboxPolicy: Map<String, Any?>?
    ): String {
        val id = nextId()
        val request = buildJsonObject {
            put("id", id)
            put("method", "turn/start")
            putJsonObject("params") {
                put("threadId", threadId)
                putJsonArray("input") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    })
                }
                put("cwd", cwd)
                put("title", title)
                when (approvalPolicy) {
                    is String -> put("approvalPolicy", approvalPolicy)
                    is JsonElement -> put("approvalPolicy", approvalPolicy)
                    else -> put("approvalPolicy", "never")
                }
                sandboxPolicy?.let { policy ->
                    putJsonObject("sandboxPolicy") {
                        policy.forEach { (k, v) ->
                            when (v) {
                                is String -> put(k, v)
                                is Boolean -> put(k, v)
                                is Number -> put(k, v.toLong())
                                is List<*> -> putJsonArray(k) {
                                    v.forEach { item -> if (item is String) add(item) }
                                }
                                else -> put(k, v?.toString() ?: "null")
                            }
                        }
                    }
                }
            }
        }

        sendMessage(request)
        val response = readResponse(id)

        return response["result"]?.jsonObject
            ?.get("turn")?.jsonObject
            ?.get("id")?.jsonPrimitive?.content
            ?: throw CodexProtocolException("Missing turn.id in turn/start response")
    }

    fun readLine(): String? {
        return reader?.readLine()
    }

    fun sendResponse(id: JsonElement, result: JsonObject) {
        val response = buildJsonObject {
            put("id", id)
            put("result", result)
        }
        sendMessageSync(response)
    }

    fun sendErrorResponse(id: JsonElement, code: Int, message: String) {
        val response = buildJsonObject {
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
        sendMessageSync(response)
    }

    fun stop() {
        process?.let { p ->
            try {
                writer?.close()
            } catch (_: Exception) {}
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                }
                log.info("Codex app-server stopped: pid={}", p.pid())
            }
        }
        process = null
        writer = null
        reader = null
    }

    private fun sendMessage(msg: JsonObject) {
        val line = msg.toString()
        log.debug("-> codex: {}", line.take(200))
        writer?.let {
            it.write(line)
            it.newLine()
            it.flush()
        } ?: throw CodexProtocolException("Writer not available")
    }

    private fun sendMessageSync(msg: JsonObject) {
        val line = msg.toString()
        log.debug("-> codex: {}", line.take(200))
        writer?.let {
            synchronized(it) {
                it.write(line)
                it.newLine()
                it.flush()
            }
        }
    }

    private suspend fun readResponse(expectedId: Int): JsonObject {
        val deadline = System.currentTimeMillis() + readTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = withContext(Dispatchers.IO) { reader?.readLine() }
                ?: throw CodexProtocolException("Subprocess closed stdout before responding")

            val parsed = try {
                json.parseToJsonElement(line).jsonObject
            } catch (e: Exception) {
                log.warn("Malformed line from codex: {}", line.take(200))
                continue
            }

            val responseId = parsed["id"]?.jsonPrimitive?.intOrNull
            if (responseId == expectedId) {
                parsed["error"]?.let { err ->
                    throw CodexProtocolException("JSON-RPC error: $err")
                }
                return parsed
            }
            // Not our response — it may be a notification, skip it
        }
        throw CodexProtocolException("Timeout waiting for response id=$expectedId")
    }

    private fun nextId(): Int = requestIdCounter.incrementAndGet()
}

class CodexProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
