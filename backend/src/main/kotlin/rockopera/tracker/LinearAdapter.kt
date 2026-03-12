package rockopera.tracker

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import rockopera.config.WorkflowConfig
import rockopera.model.BlockerRef
import rockopera.model.Issue
import rockopera.model.IssueComment
import java.time.Instant

class LinearAdapter(
    private val config: WorkflowConfig
) : TrackerAdapter {
    private val log = LoggerFactory.getLogger(LinearAdapter::class.java)

    private val client: LinearClient by lazy {
        val apiKey = config.trackerApiKey
        require(!apiKey.isNullOrBlank()) { "missing_linear_api_token" }
        LinearClient(config.trackerEndpoint, apiKey)
    }

    private var resolvedAssigneeId: String? = null

    override suspend fun fetchCandidateIssues(): Result<List<Issue>> {
        val projectSlug = config.trackerProjectSlug
        if (projectSlug.isNullOrBlank()) {
            return Result.failure(LinearApiException("missing_linear_project_slug", "project_slug is required"))
        }

        return client.fetchCandidateIssues(projectSlug, config.activeStates)
            .map { nodes -> nodes.mapNotNull { normalizeIssue(it) } }
            .map { issues -> filterByAssignee(issues) }
    }

    override suspend fun fetchIssuesByStates(stateNames: List<String>): Result<List<Issue>> {
        val projectSlug = config.trackerProjectSlug
        if (projectSlug.isNullOrBlank()) {
            return Result.failure(LinearApiException("missing_linear_project_slug", "project_slug is required"))
        }

        return client.fetchCandidateIssues(projectSlug, stateNames)
            .map { nodes -> nodes.mapNotNull { normalizeIssue(it) } }
    }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Result<List<Issue>> {
        return client.fetchIssuesByIds(issueIds)
            .map { nodes -> nodes.mapNotNull { normalizeIssue(it) } }
    }

    override suspend fun fetchIssueComments(issueId: String): Result<List<IssueComment>> = runCatching {
        val query = """
            query IssueComments(${'$'}issueId: String!) {
                issue(id: ${'$'}issueId) {
                    comments(first: 50) {
                        nodes {
                            body
                            user { displayName }
                            createdAt
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject { put("issueId", issueId) }
        val response = client.executeRawQuery(query, variables).getOrThrow()
        val nodes = response.jsonObject["data"]
            ?.jsonObject?.get("issue")
            ?.jsonObject?.get("comments")
            ?.jsonObject?.get("nodes")
            ?.jsonArray ?: return@runCatching emptyList()

        nodes.mapNotNull { el ->
            val obj = el.jsonObject
            val body = obj.str("body") ?: return@mapNotNull null
            val author = obj["user"]?.jsonObject?.str("displayName") ?: "unknown"
            val createdAt = obj.str("createdAt")?.let { parseInstant(it) }
            IssueComment(author = author, body = body, createdAt = createdAt)
        }
    }

    private suspend fun filterByAssignee(issues: List<Issue>): List<Issue> {
        val assigneeConfig = config.trackerAssignee ?: return issues

        val targetId = resolveAssigneeId(assigneeConfig)
            ?: return issues // can't resolve, pass all through

        return issues.filter { it.assigneeId == targetId }
    }

    private suspend fun resolveAssigneeId(assigneeConfig: String): String? {
        if (resolvedAssigneeId != null) return resolvedAssigneeId

        val id = if (assigneeConfig.equals("me", ignoreCase = true)) {
            client.fetchViewerId().getOrElse {
                log.error("Failed to resolve viewer ID: {}", it.message)
                return null
            }
        } else {
            assigneeConfig
        }

        resolvedAssigneeId = id
        return id
    }

    private fun normalizeIssue(node: JsonObject): Issue? {
        val id = node.str("id") ?: return null
        val identifier = node.str("identifier") ?: return null
        val title = node.str("title") ?: return null
        val stateName = node.jsonObject["state"]?.jsonObject?.str("name") ?: return null

        val labels = node["labels"]?.jsonObject
            ?.get("nodes")?.jsonArray
            ?.mapNotNull { it.jsonObject.str("name")?.lowercase() }
            ?: emptyList()

        val blockedBy = node["inverseRelations"]?.jsonObject
            ?.get("nodes")?.jsonArray
            ?.filter { rel ->
                rel.jsonObject.str("type")?.equals("blocks", ignoreCase = true) == true
            }
            ?.mapNotNull { rel ->
                val issue = rel.jsonObject["issue"]?.jsonObject ?: return@mapNotNull null
                BlockerRef(
                    id = issue.str("id"),
                    identifier = issue.str("identifier"),
                    state = issue["state"]?.jsonObject?.str("name")
                )
            }
            ?: emptyList()

        val priority = node["priority"]?.let {
            if (it is JsonPrimitive && it.intOrNull != null) it.int else null
        }

        val assigneeId = node["assignee"]?.let {
            if (it is JsonNull) null else it.jsonObject.str("id")
        }

        val assignedToWorker = if (config.trackerAssignee != null) {
            assigneeId != null && assigneeId == resolvedAssigneeId
        } else true

        return Issue(
            id = id,
            identifier = identifier,
            title = title,
            description = node.str("description"),
            priority = priority,
            state = stateName,
            branchName = node.str("branchName"),
            url = node.str("url"),
            assigneeId = assigneeId,
            labels = labels,
            blockedBy = blockedBy,
            assignedToWorker = assignedToWorker,
            createdAt = node.str("createdAt")?.let { parseInstant(it) },
            updatedAt = node.str("updatedAt")?.let { parseInstant(it) }
        )
    }

    private fun parseInstant(s: String): Instant? = try {
        Instant.parse(s)
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.str(key: String): String? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    return el.jsonPrimitive.contentOrNull
}
