package rockopera.tracker

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import rockopera.config.WorkflowConfig
import rockopera.model.BlockerRef
import rockopera.model.Issue
import java.time.Instant

/**
 * Gitea issue tracker adapter.
 *
 * Gitea issues don't have built-in "states" like Linear. Instead, we use:
 * - Open/Closed as the base state
 * - Labels to represent workflow states (e.g., "todo", "in-progress", "review")
 *
 * Convention:
 * - `active_states` labels on open issues → eligible for dispatch
 * - `terminal_states` labels OR closed issues → terminal
 * - Issue `state` field = first matching active/terminal label, or "open"/"closed"
 *
 * The `tracker.project_slug` format is "owner/repo" (e.g., "rockopera/myproject").
 */
class GiteaAdapter(
    private val config: WorkflowConfig,
    private val client: GiteaClient
) : TrackerAdapter {
    private val log = LoggerFactory.getLogger(GiteaAdapter::class.java)

    private val owner: String
    private val repo: String

    private var resolvedAssigneeLogin: String? = null

    init {
        val slug = config.trackerProjectSlug
            ?: throw GiteaApiException("missing_gitea_project", "tracker.project_slug is required (format: owner/repo)")
        val parts = slug.split("/", limit = 2)
        require(parts.size == 2) { "tracker.project_slug must be in 'owner/repo' format, got: $slug" }
        owner = parts[0]
        repo = parts[1]
    }

    override suspend fun fetchCandidateIssues(): Result<List<Issue>> = runCatching {
        val rawIssues = client.listIssues(owner, repo, state = "open").getOrThrow()
        val issues = rawIssues.mapNotNull { normalizeIssue(it) }
        val filtered = filterByState(issues, config.activeStates)
        filterByAssignee(filtered)
    }

    override suspend fun fetchIssuesByStates(stateNames: List<String>): Result<List<Issue>> = runCatching {
        // For terminal states, we also need closed issues
        val normalizedNames = stateNames.map { it.trim().lowercase() }
        val needsClosed = normalizedNames.any { it == "closed" }

        val allIssues = mutableListOf<Issue>()

        // Fetch open issues and filter by label
        val openRaw = client.listIssues(owner, repo, state = "open").getOrThrow()
        allIssues.addAll(openRaw.mapNotNull { normalizeIssue(it) })

        // Also fetch closed if any terminal state is "closed"
        if (needsClosed) {
            val closedRaw = client.listIssues(owner, repo, state = "closed").getOrThrow()
            allIssues.addAll(closedRaw.mapNotNull { normalizeIssue(it) })
        }

        filterByState(allIssues, stateNames)
    }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Result<List<Issue>> = runCatching {
        val numbers = issueIds.mapNotNull { it.toLongOrNull() }
        val raw = client.getIssuesByNumbers(owner, repo, numbers).getOrThrow()
        raw.mapNotNull { normalizeIssue(it) }
    }

    private fun filterByState(issues: List<Issue>, stateNames: List<String>): List<Issue> {
        val normalized = stateNames.map { it.trim().lowercase() }
        return issues.filter { issue ->
            issue.state.trim().lowercase() in normalized
        }
    }

    private suspend fun filterByAssignee(issues: List<Issue>): List<Issue> {
        val assigneeConfig = config.trackerAssignee
        if (assigneeConfig.isNullOrBlank()) return issues

        val targetLogin = resolveAssigneeLogin(assigneeConfig) ?: return issues
        return issues.filter { it.assigneeId == targetLogin }
    }

    private suspend fun resolveAssigneeLogin(assigneeConfig: String): String? {
        if (resolvedAssigneeLogin != null) return resolvedAssigneeLogin

        val login = if (assigneeConfig.equals("me", ignoreCase = true)) {
            client.getAuthenticatedUser().getOrElse {
                log.error("Failed to resolve authenticated user: {}", it.message)
                return null
            }["login"]?.jsonPrimitive?.contentOrNull
        } else {
            assigneeConfig
        }

        resolvedAssigneeLogin = login
        return login
    }

    /**
     * Normalize a Gitea issue JSON into our Issue model.
     *
     * State mapping:
     * - Look at labels for active/terminal state names
     * - If closed → "closed"
     * - If no matching label → "open" (for open issues)
     */
    private fun normalizeIssue(node: JsonObject): Issue? {
        val id = node["number"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
        val title = node.str("title") ?: return null
        val giteaState = node.str("state") ?: "open" // "open" or "closed"

        // Build identifier as "owner/repo#number"
        val number = node["number"]?.jsonPrimitive?.long ?: return null
        val identifier = "#$number"

        // Extract labels
        val labelsArray = node["labels"]?.jsonArray ?: JsonArray(emptyList())
        val labelNames = labelsArray.mapNotNull { it.jsonObject.str("name")?.lowercase() }

        // Determine workflow state from labels
        val activeNorm = config.activeStates.map { it.trim().lowercase() }
        val terminalNorm = config.terminalStates.map { it.trim().lowercase() }

        val state = when {
            giteaState == "closed" -> "closed"
            labelNames.any { it in terminalNorm } -> labelNames.first { it in terminalNorm }
            labelNames.any { it in activeNorm } -> labelNames.first { it in activeNorm }
            else -> "open"
        }

        // Assignee
        val assignee = node["assignee"]?.let {
            if (it is JsonNull) null else it.jsonObject
        }
        val assigneeLogin = assignee?.str("login")

        // Priority: use label convention "priority:N" or "P0/P1/P2/P3"
        val priority = labelNames.firstNotNullOfOrNull { label ->
            when {
                label.startsWith("priority:") -> label.removePrefix("priority:").toIntOrNull()
                label == "p0" -> 0
                label == "p1" -> 1
                label == "p2" -> 2
                label == "p3" -> 3
                else -> null
            }
        }

        // Blocked by: Gitea doesn't have native relations — use label "blocked"
        val isBlocked = labelNames.any { it == "blocked" }
        val blockedBy = if (isBlocked) {
            listOf(BlockerRef(id = null, identifier = null, state = "open"))
        } else emptyList()

        val description = node.str("body")
        val url = node.str("html_url")
        val branchName = node.str("ref") // Gitea issue ref branch if set

        return Issue(
            id = id,
            identifier = identifier,
            title = title,
            description = description,
            priority = priority,
            state = state,
            branchName = branchName,
            url = url,
            assigneeId = assigneeLogin,
            labels = labelNames,
            blockedBy = blockedBy,
            assignedToWorker = true,
            createdAt = node.str("created_at")?.let { parseInstant(it) },
            updatedAt = node.str("updated_at")?.let { parseInstant(it) }
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
