package rockopera.tracker

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import rockopera.config.ProjectConfig
import rockopera.config.WorkflowConfig
import rockopera.model.BlockerRef
import rockopera.model.Issue
import rockopera.model.IssueComment
import java.time.Instant

/**
 * Gitea issue tracker adapter with multi-repository support.
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
 * Supports multiple projects via `tracker.projects` list in config.
 * Falls back to single `tracker.project_slug` for backward compatibility.
 */
class GiteaAdapter(
    private val config: WorkflowConfig,
    private val client: GiteaClient
) : TrackerAdapter {
    private val log = LoggerFactory.getLogger(GiteaAdapter::class.java)

    private val projects: List<ProjectConfig> = config.effectiveProjects().also {
        if (it.isEmpty()) {
            throw GiteaApiException(
                "missing_gitea_project",
                "tracker.projects or tracker.project_slug is required (format: owner/repo)"
            )
        }
    }

    private var resolvedAssigneeLogin: String? = null

    override suspend fun fetchCandidateIssues(): Result<List<Issue>> = runCatching {
        val allIssues = mutableListOf<Issue>()
        for (project in projects) {
            val effectiveConfig = config.effectiveConfigForProject(project)
            val rawIssues = client.listIssues(project.owner, project.repo, state = "open").getOrThrow()
            val issues = rawIssues.mapNotNull { normalizeIssue(it, project, effectiveConfig) }
            val filtered = filterByState(issues, effectiveConfig.activeStates)
            allIssues.addAll(filtered)
        }
        filterByAssignee(allIssues)
    }

    override suspend fun fetchIssuesByStates(stateNames: List<String>): Result<List<Issue>> = runCatching {
        val normalizedNames = stateNames.map { it.trim().lowercase() }
        val needsClosed = normalizedNames.any { it == "closed" }

        val allIssues = mutableListOf<Issue>()

        for (project in projects) {
            val effectiveConfig = config.effectiveConfigForProject(project)

            // Fetch open issues and filter by label
            val openRaw = client.listIssues(project.owner, project.repo, state = "open").getOrThrow()
            allIssues.addAll(openRaw.mapNotNull { normalizeIssue(it, project, effectiveConfig) })

            // Also fetch closed if any terminal state is "closed"
            if (needsClosed) {
                val closedRaw = client.listIssues(project.owner, project.repo, state = "closed").getOrThrow()
                allIssues.addAll(closedRaw.mapNotNull { normalizeIssue(it, project, effectiveConfig) })
            }
        }

        filterByState(allIssues, stateNames)
    }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Result<List<Issue>> = runCatching {
        val allIssues = mutableListOf<Issue>()

        // Group issue IDs by project slug: "owner/repo#number" -> (project, number)
        val grouped = issueIds.mapNotNull { compositeId ->
            val parsed = parseCompositeId(compositeId)
            if (parsed != null) parsed else {
                // Legacy format: plain number — try all projects
                val number = compositeId.toLongOrNull()
                if (number != null && projects.size == 1) {
                    Triple(projects[0], projects[0].slug, number)
                } else null
            }
        }.groupBy({ it.second }, { it.third })

        for ((slug, numbers) in grouped) {
            val project = projects.find { it.slug == slug } ?: continue
            val effectiveConfig = config.effectiveConfigForProject(project)
            val raw = client.getIssuesByNumbers(project.owner, project.repo, numbers).getOrThrow()
            allIssues.addAll(raw.mapNotNull { normalizeIssue(it, project, effectiveConfig) })
        }

        allIssues
    }

    override suspend fun fetchIssueComments(issueId: String): Result<List<IssueComment>> = runCatching {
        val parsed = parseCompositeId(issueId)
        val (project, _, number) = if (parsed != null) parsed else {
            // Legacy format
            val num = issueId.toLongOrNull()
                ?: throw GiteaApiException("invalid_issue_id", "Issue ID must be in 'owner/repo#number' format, got: $issueId")
            if (projects.size == 1) Triple(projects[0], projects[0].slug, num)
            else throw GiteaApiException("ambiguous_issue_id", "Cannot resolve plain issue number '$issueId' with multiple projects")
        }

        val result = client.apiCall("GET", "/api/v1/repos/${project.owner}/${project.repo}/issues/$number/comments")
        val commentsArray = result.getOrThrow().jsonArray
        commentsArray.mapNotNull { el ->
            val obj = el.jsonObject
            val body = obj.str("body") ?: return@mapNotNull null
            val author = obj["user"]?.jsonObject?.str("login") ?: "unknown"
            val createdAt = obj.str("created_at")?.let { parseInstant(it) }
            IssueComment(author = author, body = body, createdAt = createdAt)
        }
    }

    /**
     * Parse a composite issue ID in "owner/repo#number" format.
     * Returns Triple(ProjectConfig, slug, number) or null if format doesn't match.
     */
    private fun parseCompositeId(compositeId: String): Triple<ProjectConfig, String, Long>? {
        val hashIdx = compositeId.lastIndexOf('#')
        if (hashIdx <= 0) return null
        val slug = compositeId.substring(0, hashIdx)
        val number = compositeId.substring(hashIdx + 1).toLongOrNull() ?: return null
        val project = projects.find { it.slug == slug } ?: return null
        return Triple(project, slug, number)
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
     *
     * Issue ID format: "owner/repo#number" (globally unique across repos)
     * Issue identifier format: "owner/repo#number"
     */
    private fun normalizeIssue(
        node: JsonObject,
        project: ProjectConfig,
        effectiveConfig: WorkflowConfig
    ): Issue? {
        val number = node["number"]?.jsonPrimitive?.longOrNull ?: return null
        val title = node.str("title") ?: return null
        val giteaState = node.str("state") ?: "open" // "open" or "closed"

        // Globally unique composite ID and human-readable identifier
        val id = "${project.slug}#$number"
        val identifier = "${project.slug}#$number"

        // Extract labels
        val labelsArray = node["labels"]?.jsonArray ?: JsonArray(emptyList())
        val labelNames = labelsArray.mapNotNull { it.jsonObject.str("name")?.lowercase() }

        // Determine workflow state from labels using effective (per-project) config
        val activeNorm = effectiveConfig.activeStates.map { it.trim().lowercase() }
        val terminalNorm = effectiveConfig.terminalStates.map { it.trim().lowercase() }

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
            updatedAt = node.str("updated_at")?.let { parseInstant(it) },
            projectSlug = project.slug,
            repoOwner = project.owner,
            repoName = project.repo
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
