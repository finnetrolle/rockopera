package rockopera.tracker

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class GiteaClient(
    private val baseUrl: String,
    private val token: String
) {
    private val log = LoggerFactory.getLogger(GiteaClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val pageSize = 50

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

    /**
     * List issues for a repo, filtered by state and labels.
     * GET /api/v1/repos/{owner}/{repo}/issues
     */
    suspend fun listIssues(
        owner: String,
        repo: String,
        state: String = "open",
        labels: List<String>? = null,
        assignee: String? = null,
        page: Int = 1
    ): Result<List<JsonObject>> = runCatching {
        val allIssues = mutableListOf<JsonObject>()
        var currentPage = page

        do {
            val response = client.get("$baseUrl/api/v1/repos/$owner/$repo/issues") {
                header("Authorization", "token $token")
                parameter("state", state)
                parameter("type", "issues") // exclude pull requests
                parameter("limit", pageSize)
                parameter("page", currentPage)
                labels?.let { parameter("labels", it.joinToString(",")) }
                assignee?.let { parameter("assigned_by", it) }
            }

            if (response.status.value != 200) {
                throw GiteaApiException("gitea_api_status", "HTTP ${response.status.value}: ${response.bodyAsText()}")
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            allIssues.addAll(body.map { it.jsonObject })

            // Gitea pagination: if we got a full page, there might be more
            if (body.size < pageSize) break
            currentPage++
        } while (true)

        allIssues
    }

    /**
     * Get a single issue by number.
     * GET /api/v1/repos/{owner}/{repo}/issues/{index}
     */
    suspend fun getIssue(owner: String, repo: String, index: Long): Result<JsonObject> = runCatching {
        val response = client.get("$baseUrl/api/v1/repos/$owner/$repo/issues/$index") {
            header("Authorization", "token $token")
        }
        if (response.status.value != 200) {
            throw GiteaApiException("gitea_api_status", "HTTP ${response.status.value}")
        }
        json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /**
     * Get issues by their IDs (Gitea doesn't have batch by ID — fetch individually).
     */
    suspend fun getIssuesByNumbers(owner: String, repo: String, numbers: List<Long>): Result<List<JsonObject>> = runCatching {
        numbers.mapNotNull { num ->
            try {
                getIssue(owner, repo, num).getOrNull()
            } catch (e: Exception) {
                log.warn("Failed to fetch issue #{}: {}", num, e.message)
                null
            }
        }
    }

    /**
     * List available labels for a repo.
     * GET /api/v1/repos/{owner}/{repo}/labels
     */
    suspend fun listLabels(owner: String, repo: String): Result<List<JsonObject>> = runCatching {
        val response = client.get("$baseUrl/api/v1/repos/$owner/$repo/labels") {
            header("Authorization", "token $token")
        }
        if (response.status.value != 200) {
            throw GiteaApiException("gitea_api_status", "HTTP ${response.status.value}")
        }
        json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
    }

    /**
     * Get authenticated user info.
     * GET /api/v1/user
     */
    suspend fun getAuthenticatedUser(): Result<JsonObject> = runCatching {
        val response = client.get("$baseUrl/api/v1/user") {
            header("Authorization", "token $token")
        }
        if (response.status.value != 200) {
            throw GiteaApiException("gitea_api_status", "HTTP ${response.status.value}")
        }
        json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /**
     * Execute arbitrary API call (for dynamic tool).
     */
    suspend fun apiCall(method: String, path: String, body: String? = null): Result<JsonElement> = runCatching {
        val response = when (method.uppercase()) {
            "GET" -> client.get("$baseUrl$path") {
                header("Authorization", "token $token")
            }
            "POST" -> client.post("$baseUrl$path") {
                header("Authorization", "token $token")
                contentType(ContentType.Application.Json)
                body?.let { setBody(it) }
            }
            "PATCH" -> client.patch("$baseUrl$path") {
                header("Authorization", "token $token")
                contentType(ContentType.Application.Json)
                body?.let { setBody(it) }
            }
            "PUT" -> client.put("$baseUrl$path") {
                header("Authorization", "token $token")
                contentType(ContentType.Application.Json)
                body?.let { setBody(it) }
            }
            "DELETE" -> client.delete("$baseUrl$path") {
                header("Authorization", "token $token")
            }
            else -> throw GiteaApiException("gitea_unsupported_method", "Unsupported method: $method")
        }

        if (response.status.value !in 200..299) {
            throw GiteaApiException("gitea_api_status", "HTTP ${response.status.value}: ${response.bodyAsText()}")
        }

        val text = response.bodyAsText()
        if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
    }

    fun close() {
        client.close()
    }
}

class GiteaApiException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
