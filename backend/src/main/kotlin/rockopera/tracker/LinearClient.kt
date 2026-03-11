package rockopera.tracker

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class LinearClient(
    private val endpoint: String,
    private val apiKey: String
) {
    private val log = LoggerFactory.getLogger(LinearClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val pageSize = 50
    private val relationPageSize = 25

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        engine {
            maxConnectionsCount = 20
        }
    }

    private val issueFields = """
        id
        identifier
        title
        description
        priority
        state { name }
        branchName
        url
        assignee { id }
        labels { nodes { name } }
        inverseRelations(first: ${'$'}relationFirst) {
            nodes { type issue { id identifier state { name } } }
        }
        createdAt
        updatedAt
    """.trimIndent()

    companion object {
        private val CANDIDATE_QUERY = """
            query SymphonyLinearPoll(${'$'}projectSlug: String!, ${'$'}stateNames: [String!]!, ${'$'}first: Int!, ${'$'}relationFirst: Int!, ${'$'}after: String) {
                issues(filter: {project: {slugId: {eq: ${'$'}projectSlug}}, state: {name: {in: ${'$'}stateNames}}}, first: ${'$'}first, after: ${'$'}after) {
                    nodes {
                        id identifier title description priority
                        state { name }
                        branchName url
                        assignee { id }
                        labels { nodes { name } }
                        inverseRelations(first: ${'$'}relationFirst) {
                            nodes { type issue { id identifier state { name } } }
                        }
                        createdAt updatedAt
                    }
                    pageInfo { hasNextPage endCursor }
                }
            }
        """.trimIndent()

        private val ISSUES_BY_ID_QUERY = """
            query SymphonyLinearIssuesById(${'$'}ids: [ID!]!, ${'$'}first: Int!, ${'$'}relationFirst: Int!) {
                issues(filter: {id: {in: ${'$'}ids}}, first: ${'$'}first) {
                    nodes {
                        id identifier title description priority
                        state { name }
                        branchName url
                        assignee { id }
                        labels { nodes { name } }
                        inverseRelations(first: ${'$'}relationFirst) {
                            nodes { type issue { id identifier state { name } } }
                        }
                        createdAt updatedAt
                    }
                }
            }
        """.trimIndent()

        private const val VIEWER_QUERY = "query SymphonyLinearViewer { viewer { id } }"
    }

    suspend fun fetchCandidateIssues(
        projectSlug: String,
        stateNames: List<String>
    ): Result<List<JsonObject>> = runCatching {
        val allNodes = mutableListOf<JsonObject>()
        var cursor: String? = null

        do {
            val variables = buildJsonObject {
                put("projectSlug", projectSlug)
                putJsonArray("stateNames") { stateNames.forEach { add(it) } }
                put("first", pageSize)
                put("relationFirst", relationPageSize)
                cursor?.let { put("after", it) }
            }

            val response = executeQuery(CANDIDATE_QUERY, variables)
            val issues = response.jsonObject["data"]
                ?.jsonObject?.get("issues")
                ?.jsonObject ?: throw LinearApiException("linear_unknown_payload", "Missing issues in response")

            val nodes = issues["nodes"]?.jsonArray
                ?: throw LinearApiException("linear_unknown_payload", "Missing nodes in response")
            allNodes.addAll(nodes.map { it.jsonObject })

            val pageInfo = issues["pageInfo"]?.jsonObject
            val hasNext = pageInfo?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull ?: false
            cursor = if (hasNext) {
                pageInfo?.get("endCursor")?.jsonPrimitive?.contentOrNull
                    ?: throw LinearApiException("linear_missing_end_cursor", "hasNextPage=true but endCursor is null")
            } else null
        } while (cursor != null)

        allNodes
    }

    suspend fun fetchIssuesByIds(ids: List<String>): Result<List<JsonObject>> = runCatching {
        if (ids.isEmpty()) return@runCatching emptyList()

        val variables = buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(it) } }
            put("first", ids.size.coerceAtMost(250))
            put("relationFirst", relationPageSize)
        }

        val response = executeQuery(ISSUES_BY_ID_QUERY, variables)
        val nodes = response.jsonObject["data"]
            ?.jsonObject?.get("issues")
            ?.jsonObject?.get("nodes")
            ?.jsonArray ?: throw LinearApiException("linear_unknown_payload", "Missing nodes in response")

        nodes.map { it.jsonObject }
    }

    suspend fun fetchViewerId(): Result<String> = runCatching {
        val response = executeQuery(VIEWER_QUERY, null)
        response.jsonObject["data"]
            ?.jsonObject?.get("viewer")
            ?.jsonObject?.get("id")
            ?.jsonPrimitive?.content
            ?: throw LinearApiException("linear_unknown_payload", "Missing viewer.id")
    }

    suspend fun executeRawQuery(query: String, variables: JsonObject?): Result<JsonElement> = runCatching {
        executeQuery(query, variables)
    }

    private suspend fun executeQuery(query: String, variables: JsonObject?): JsonElement {
        val body = buildJsonObject {
            put("query", query)
            variables?.let { put("variables", it) }
        }

        val response: HttpResponse = try {
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                header("Authorization", apiKey)
                setBody(body.toString())
            }
        } catch (e: Exception) {
            throw LinearApiException("linear_api_request", "Transport error: ${e.message}", e)
        }

        if (response.status.value != 200) {
            val text = response.bodyAsText()
            throw LinearApiException("linear_api_status", "HTTP ${response.status.value}: $text")
        }

        val responseText = response.bodyAsText()
        val parsed = json.parseToJsonElement(responseText)

        // Check for GraphQL errors
        val errors = parsed.jsonObject["errors"]
        if (errors != null && errors is JsonArray && errors.isNotEmpty()) {
            throw LinearApiException("linear_graphql_errors", errors.toString())
        }

        return parsed
    }

    fun close() {
        client.close()
    }
}

class LinearApiException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
