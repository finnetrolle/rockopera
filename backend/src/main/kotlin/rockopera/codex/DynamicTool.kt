package rockopera.codex

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import rockopera.tracker.GiteaClient
import rockopera.tracker.LinearClient

class DynamicTool private constructor(
    private val linearClient: LinearClient?,
    private val giteaClient: GiteaClient?,
    private val giteaOwner: String?,
    private val giteaRepo: String?
) {
    private val log = LoggerFactory.getLogger(DynamicTool::class.java)

    constructor(linearClient: LinearClient) : this(linearClient, null, null, null)

    constructor(giteaClient: GiteaClient, owner: String, repo: String) : this(null, giteaClient, owner, repo)

    companion object {
        val LINEAR_GRAPHQL_SPEC = buildJsonObject {
            put("name", "linear_graphql")
            put("description", "Execute a raw GraphQL query or mutation against Linear using RockOpera's configured auth.")
            putJsonObject("inputSchema") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonArray("required") { add("query") }
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "GraphQL query or mutation document")
                    }
                    putJsonObject("variables") {
                        putJsonArray("type") { add("object"); add("null") }
                        put("description", "Optional GraphQL variables")
                        put("additionalProperties", true)
                    }
                }
            }
        }

        val GITEA_API_SPEC = buildJsonObject {
            put("name", "gitea_api")
            put("description", "Execute an API call against the Gitea instance using RockOpera's configured auth. Supports GET, POST, PATCH, PUT, DELETE.")
            putJsonObject("inputSchema") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonArray("required") { add("method"); add("path") }
                putJsonObject("properties") {
                    putJsonObject("method") {
                        put("type", "string")
                        putJsonArray("enum") { add("GET"); add("POST"); add("PATCH"); add("PUT"); add("DELETE") }
                        put("description", "HTTP method")
                    }
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "API path (e.g., /api/v1/repos/{owner}/{repo}/issues)")
                    }
                    putJsonObject("body") {
                        putJsonArray("type") { add("string"); add("null") }
                        put("description", "Optional JSON body for POST/PATCH/PUT requests")
                    }
                }
            }
        }
    }

    fun toolSpec(): JsonObject = if (giteaClient != null) GITEA_API_SPEC else LINEAR_GRAPHQL_SPEC

    suspend fun execute(toolName: String, arguments: JsonObject): JsonObject {
        return when (toolName) {
            "linear_graphql" -> executeLinearGraphql(arguments)
            "gitea_api" -> executeGiteaApi(arguments)
            else -> errorResult("Unknown tool: $toolName")
        }
    }

    private suspend fun executeLinearGraphql(arguments: JsonObject): JsonObject {
        val client = linearClient ?: return errorResult("Linear client not configured")
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull
        if (query.isNullOrBlank()) {
            return errorResult("Missing or empty 'query' argument")
        }

        val variables = arguments["variables"]?.let {
            if (it is JsonNull) null else it.jsonObject
        }

        return try {
            val result = client.executeRawQuery(query, variables).getOrThrow()
            log.info("linear_graphql executed successfully")
            successResult(result.toString())
        } catch (e: Exception) {
            log.error("linear_graphql failed: {}", e.message)
            errorResult(buildJsonObject { put("error", e.message ?: "unknown error") }.toString())
        }
    }

    private suspend fun executeGiteaApi(arguments: JsonObject): JsonObject {
        val client = giteaClient ?: return errorResult("Gitea client not configured")
        val method = arguments["method"]?.jsonPrimitive?.contentOrNull
        if (method.isNullOrBlank()) {
            return errorResult("Missing or empty 'method' argument")
        }
        val path = arguments["path"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank()) {
            return errorResult("Missing or empty 'path' argument")
        }
        val body = arguments["body"]?.let {
            if (it is JsonNull) null else it.jsonPrimitive.contentOrNull
        }

        return try {
            val result = client.apiCall(method, path, body).getOrThrow()
            log.info("gitea_api executed successfully: {} {}", method, path)
            successResult(result.toString())
        } catch (e: Exception) {
            log.error("gitea_api failed: {}", e.message)
            errorResult(buildJsonObject { put("error", e.message ?: "unknown error") }.toString())
        }
    }

    private fun successResult(text: String): JsonObject = buildJsonObject {
        put("success", true)
        putJsonArray("contentItems") {
            add(buildJsonObject {
                put("type", "inputText")
                put("text", text)
            })
        }
    }

    private fun errorResult(text: String): JsonObject = buildJsonObject {
        put("success", false)
        putJsonArray("contentItems") {
            add(buildJsonObject {
                put("type", "inputText")
                put("text", text)
            })
        }
    }
}
