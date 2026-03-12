package rockopera.agent

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import rockopera.config.PhaseConfig
import rockopera.config.WorkflowConfig
import rockopera.model.Issue
import rockopera.tracker.GiteaClient
import rockopera.workspace.WorkspaceManager
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

data class AgentEvent(
    val event: String,
    val timestamp: Instant = Instant.now(),
    val agentPid: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val usage: Map<String, Any?>? = null
)

data class ShellResult(val success: Boolean, val output: String, val exitCode: Int)

class AgentRunner(
    private val config: WorkflowConfig,
    private val workspaceManager: WorkspaceManager,
    private val giteaClient: GiteaClient? = null
) {
    private val log = LoggerFactory.getLogger(AgentRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val owner: String
    private val repo: String

    init {
        val slug = config.trackerProjectSlug ?: ""
        val parts = slug.split("/", limit = 2)
        owner = parts.getOrElse(0) { "" }
        repo = parts.getOrElse(1) { "" }
    }

    suspend fun run(
        issue: Issue,
        attempt: Int?,
        onEvent: suspend (AgentEvent) -> Unit
    ) {
        val phase = findPhaseForState(issue.state)
        if (phase != null) {
            runPhase(issue, attempt, phase, onEvent)
        } else {
            runPhase(issue, attempt, defaultCodingPhase(), onEvent)
        }
    }

    private fun findPhaseForState(state: String): PhaseConfig? {
        val stateNorm = state.trim().lowercase()
        return config.phases.find { phase ->
            phase.triggerStates.any { it.trim().lowercase() == stateNorm }
        }
    }

    private fun defaultCodingPhase() = PhaseConfig(
        name = "coding",
        triggerStates = config.activeStates,
        createsPr = giteaClient != null,
        onSuccess = "done",
        labelOnStart = if (giteaClient != null) "in-progress" else null
    )

    private suspend fun runPhase(
        issue: Issue,
        attempt: Int?,
        phase: PhaseConfig,
        onEvent: suspend (AgentEvent) -> Unit
    ) {
        log.info("Starting phase '{}': issue={}, attempt={}", phase.name, issue.identifier, attempt)

        // 1. Create/reuse workspace
        val workspace = workspaceManager.createOrReuse(issue.identifier)
        val wsPath = Path.of(workspace.path)

        // 2. Git setup
        if (giteaClient != null) {
            if (phase.needsPrDiff) {
                // Review-like phase: checkout existing PR branch
                if (workspace.createdNow) {
                    onEvent(statusEvent("Cloning repository for ${phase.name}..."))
                    gitClone(workspace.path, issue)
                } else {
                    onEvent(statusEvent("Updating repository for ${phase.name}..."))
                    runShellCommand(workspace.path, "git fetch origin")
                }
                val branchName = "rockopera/issue-${issue.id}"
                runShellCommand(workspace.path,
                    "git checkout $branchName 2>/dev/null || git checkout -b $branchName origin/$branchName")
            } else if (phase.createsPr) {
                // Coding-like phase: fresh branch from default
                if (workspace.createdNow) {
                    onEvent(statusEvent("Cloning repository..."))
                    gitClone(workspace.path, issue)
                } else {
                    onEvent(statusEvent("Updating repository..."))
                    val defaultBranch = getDefaultBranch()
                    runShellCommand(workspace.path, "git fetch origin && git reset --hard origin/$defaultBranch")
                }
                onEvent(statusEvent("Creating branch..."))
                gitCreateBranch(workspace.path, issue)
            }

            // Set label on start
            phase.labelOnStart?.let { label ->
                onEvent(statusEvent("Setting label '$label'..."))
                addLabel(issue, label)
            }
        }

        // 3. Fetch PR context if needed
        var prContext: PrContext? = null
        if (phase.needsPrDiff && giteaClient != null) {
            onEvent(statusEvent("Fetching PR diff..."))
            val pr = findPrForIssue(issue)
            if (pr == null) {
                log.warn("No open PR found for issue {}, skipping phase '{}'", issue.identifier, phase.name)
                commentOnIssue(issue, "${phase.name} skipped: no open PR found.")
                addLabel(issue, phase.onChangesRequested)
                removeLabel(issue, issue.state)
                return
            }
            val prNumber = pr["number"]?.jsonPrimitive?.longOrNull ?: return
            val prTitle = pr["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val diff = fetchPrDiff(prNumber)
            prContext = PrContext(number = prNumber, title = prTitle, diff = diff)
            log.info("Found PR #{} for issue {}", prNumber, issue.identifier)
        }

        // 4. Run before_run hook
        workspaceManager.runBeforeRunHook(wsPath)

        // 5. Build prompt and launch agent
        onEvent(statusEvent("${phase.name} agent is working..."))
        val promptTemplate = phase.promptTemplate ?: config.promptTemplate
        val prompt = PromptBuilder.render(promptTemplate, issue, attempt, prContext)
        val agentEnv = buildAgentEnv(issue, phase, prContext)
        val command = phase.command ?: config.agentCommand

        val client = CliAgentClient(
            command = command,
            workspacePath = workspace.path,
            env = agentEnv
        )

        try {
            val pid = client.start(prompt)

            onEvent(AgentEvent(
                event = "session_started",
                agentPid = pid,
                payload = mapOf(
                    "issue_id" to issue.id,
                    "issue_identifier" to issue.identifier,
                    "workspace" to workspace.path,
                    "phase" to phase.name
                )
            ))

            // 6. Stream output
            val output = client.readAllOutput(
                timeoutMs = config.agentTurnTimeoutMs,
                onLine = { line ->
                    val event = tryParseStreamEvent(line, pid)
                    if (event != null) {
                        onEvent(event)
                    }
                }
            )

            // 7. Handle result
            if (output.isSuccess) {
                log.info("Phase '{}' completed: issue={}", phase.name, issue.identifier)
                handlePhaseSuccess(issue, phase, output.stdout, prContext, onEvent, pid)
            } else {
                log.warn("Phase '{}' failed: issue={}, exit={}", phase.name, issue.identifier, output.exitCode)
                handlePhaseFailure(issue, phase, output, onEvent, pid)
            }
        } finally {
            client.stop()
            workspaceManager.runAfterRunHook(wsPath)
        }
    }

    private suspend fun handlePhaseSuccess(
        issue: Issue,
        phase: PhaseConfig,
        stdout: String,
        prContext: PrContext?,
        onEvent: suspend (AgentEvent) -> Unit,
        pid: String
    ) {
        if (giteaClient != null) {
            // PR creation for coding-like phases
            if (phase.createsPr) {
                val workspace = workspaceManager.createOrReuse(issue.identifier)
                if (gitHasChanges(workspace.path)) {
                    onEvent(statusEvent("Committing changes..."))
                    gitCommitAndPush(workspace.path, issue)

                    onEvent(statusEvent("Creating pull request..."))
                    val branchName = "rockopera/issue-${issue.id}"
                    createPullRequest(issue, branchName)
                }
            }

            // Verdict-based transition (review-like phases)
            if (phase.verdictBased) {
                log.info("Review stdout length: {} chars, lines: {}", stdout.length, stdout.lines().size)
                val resultText = extractAgentResultText(stdout)
                log.info("Extracted result text length: {} chars", resultText.length)
                if (resultText.isNotBlank()) {
                    log.info("Result text preview: {}", resultText.take(500))
                } else {
                    log.warn("Result text is empty. Stdout preview: {}", stdout.take(1000))
                }
                val review = parseStructuredReview(resultText)
                val verdict = review?.verdict ?: parseVerdict(stdout)
                log.info("Verdict for issue {}: {}", issue.identifier, verdict)

                // If no structured review, create one from the plain text result
                val effectiveReview = review ?: StructuredReview(verdict, resultText.ifBlank { "Review completed." }, emptyList())

                // Post structured review on PR via Gitea Reviews API
                if (prContext != null) {
                    onEvent(statusEvent("Posting review on PR..."))
                    submitGiteaReview(prContext.number, verdict, effectiveReview, resultText)
                }

                val nextLabel = if (verdict == Verdict.APPROVED) phase.onApproved else phase.onChangesRequested
                val verdictLabel = if (verdict == Verdict.APPROVED) "APPROVED" else "CHANGES REQUESTED"
                val statusMsg = "${phase.name} completed: $verdictLabel."
                onEvent(statusEvent(statusMsg))

                // Build detailed issue comment with review findings
                val commentBuilder = StringBuilder()
                commentBuilder.append("**Code Review: $verdictLabel**")
                prContext?.let { commentBuilder.append(" (PR #${it.number})") }
                commentBuilder.append("\n")

                if (effectiveReview.summary.isNotBlank()) {
                    commentBuilder.append("\n${effectiveReview.summary}\n")
                }
                if (effectiveReview.comments.isNotEmpty()) {
                    commentBuilder.append("\n**Inline comments:**\n")
                    for (c in effectiveReview.comments) {
                        commentBuilder.append("- `${c.path}:${c.line}` — ${c.body}\n")
                    }
                }
                commentOnIssue(issue, commentBuilder.toString())
                addLabel(issue, nextLabel)

                onEvent(AgentEvent(
                    event = "agent_completed",
                    agentPid = pid,
                    payload = mapOf("exit_code" to 0, "phase" to phase.name, "verdict" to verdict.name)
                ))
            } else {
                // Simple success transition
                onEvent(statusEvent("Posting results..."))
                commentOnIssue(issue, "${phase.name} completed successfully." +
                    if (phase.createsPr) " PR created." else "")
                addLabel(issue, phase.onSuccess)

                onEvent(AgentEvent(
                    event = "agent_completed",
                    agentPid = pid,
                    payload = mapOf("exit_code" to 0, "phase" to phase.name)
                ))
            }

            // Remove trigger state label
            for (triggerState in phase.triggerStates) {
                removeLabel(issue, triggerState)
            }
            phase.labelOnStart?.let { removeLabel(issue, it) }
        } else {
            onEvent(AgentEvent(
                event = "agent_completed",
                agentPid = pid,
                payload = mapOf("exit_code" to 0, "phase" to phase.name)
            ))
        }
    }

    private suspend fun handlePhaseFailure(
        issue: Issue,
        phase: PhaseConfig,
        output: AgentOutput,
        onEvent: suspend (AgentEvent) -> Unit,
        pid: String
    ) {
        if (giteaClient != null) {
            val errorMsg = output.stderr.take(500).ifBlank { "exit code ${output.exitCode}" }
            commentOnIssue(issue, "${phase.name} agent failed: $errorMsg")

            // If phase defines on_failure, transition to that state
            phase.onFailure?.let { failLabel ->
                addLabel(issue, failLabel)
                for (triggerState in phase.triggerStates) {
                    removeLabel(issue, triggerState)
                }
                phase.labelOnStart?.let { removeLabel(issue, it) }
            }
        }

        onEvent(AgentEvent(
            event = "agent_failed",
            agentPid = pid,
            payload = mapOf(
                "exit_code" to output.exitCode,
                "stderr" to output.stderr.take(1000),
                "phase" to phase.name
            )
        ))
        throw RuntimeException(
            "Phase '${phase.name}' exited with code ${output.exitCode} for ${issue.identifier}: ${output.stderr.take(500)}"
        )
    }

    // --- Status event helper ---

    private fun statusEvent(message: String): AgentEvent =
        AgentEvent(event = "status", payload = mapOf("message" to message))

    // --- Git operations (shell commands) ---

    private fun gitClone(workDir: String, issue: Issue) {
        val host = config.trackerEndpoint.removePrefix("http://").removePrefix("https://")
        val cloneUrl = "http://rockopera:${config.trackerApiKey}@$host/$owner/$repo.git"
        val result = runShellCommand(workDir, "git clone $cloneUrl .")
        if (!result.success) {
            log.error("git clone failed: {}", result.output)
            throw RuntimeException("git clone failed: ${result.output.take(500)}")
        }
        runShellCommand(workDir, "git config user.name 'RockOpera' && git config user.email 'rockopera@noreply'")

        val headCheck = runShellCommand(workDir, "git rev-parse HEAD 2>/dev/null")
        if (!headCheck.success) {
            log.info("Empty repository detected, initializing main branch")
            runShellCommand(workDir, "git checkout -b main && git commit --allow-empty -m 'Initial commit' && git push -u origin main")
        }
    }

    private fun gitCreateBranch(workDir: String, issue: Issue) {
        val branchName = "rockopera/issue-${issue.id}"
        val checkoutResult = runShellCommand(workDir, "git checkout $branchName 2>/dev/null || git checkout -b $branchName")
        if (!checkoutResult.success) {
            log.warn("Failed to create/checkout branch {}: {}", branchName, checkoutResult.output)
        }
    }

    private fun gitHasChanges(workDir: String): Boolean {
        val status = runShellCommand(workDir, "git status --porcelain")
        if (status.success && status.output.isNotBlank()) return true
        val defaultBranch = runShellCommand(workDir, "git rev-parse --verify origin/main 2>/dev/null || git rev-parse --verify origin/master 2>/dev/null")
        if (defaultBranch.success) {
            val baseRef = defaultBranch.output.trim()
            val diff = runShellCommand(workDir, "git diff --stat $baseRef HEAD")
            return diff.success && diff.output.isNotBlank()
        }
        return false
    }

    private fun gitCommitAndPush(workDir: String, issue: Issue) {
        val status = runShellCommand(workDir, "git status --porcelain")
        if (status.success && status.output.isNotBlank()) {
            val commitMsg = "[${issue.identifier}] ${issue.title}"
            val commitResult = runShellCommand(
                workDir,
                "git add -A && git commit -m ${shellQuote(commitMsg)}",
                timeoutMs = 60_000
            )
            if (!commitResult.success) {
                log.warn("git commit failed (agent may have committed already): {}", commitResult.output.take(200))
            }
        }
        val pushResult = runShellCommand(workDir, "git push -u origin HEAD", timeoutMs = 120_000)
        if (!pushResult.success) {
            log.error("git push failed: {}", pushResult.output)
            throw RuntimeException("git push failed: ${pushResult.output.take(500)}")
        }
    }

    // --- Gitea API operations ---

    private suspend fun getDefaultBranch(): String {
        if (giteaClient == null) return "main"
        return try {
            val result = giteaClient.apiCall("GET", "/api/v1/repos/$owner/$repo")
            val repoObj = result.getOrNull()?.jsonObject
            repoObj?.get("default_branch")?.jsonPrimitive?.contentOrNull ?: "main"
        } catch (e: Exception) {
            log.warn("Failed to get default branch, falling back to 'main': {}", e.message)
            "main"
        }
    }

    private suspend fun addLabel(issue: Issue, labelName: String) {
        if (giteaClient == null) return
        try {
            val labelId = findOrCreateLabel(labelName)
            if (labelId != null) {
                val body = buildJsonObject {
                    putJsonArray("labels") { add(labelId) }
                }.toString()
                giteaClient.apiCall("POST", "/api/v1/repos/$owner/$repo/issues/${issue.id}/labels", body)
            }
        } catch (e: Exception) {
            log.warn("Failed to add label '{}' to issue {}: {}", labelName, issue.identifier, e.message)
        }
    }

    private suspend fun removeLabel(issue: Issue, labelName: String) {
        if (giteaClient == null) return
        try {
            val labelId = findLabelId(labelName)
            if (labelId != null) {
                giteaClient.apiCall("DELETE", "/api/v1/repos/$owner/$repo/issues/${issue.id}/labels/$labelId")
            }
        } catch (e: Exception) {
            log.warn("Failed to remove label '{}' from issue {}: {}", labelName, issue.identifier, e.message)
        }
    }

    private suspend fun findOrCreateLabel(labelName: String): Long? {
        if (giteaClient == null) return null
        val existingId = findLabelId(labelName)
        if (existingId != null) return existingId

        val body = buildJsonObject {
            put("name", labelName)
            put("color", "#0075ca")
        }.toString()
        val result = giteaClient.apiCall("POST", "/api/v1/repos/$owner/$repo/labels", body)
        val created = result.getOrNull()?.jsonObject
        return created?.get("id")?.jsonPrimitive?.longOrNull
    }

    private suspend fun findLabelId(labelName: String): Long? {
        if (giteaClient == null) return null
        val result = giteaClient.apiCall("GET", "/api/v1/repos/$owner/$repo/labels")
        val labels = result.getOrNull()?.jsonArray ?: return null
        for (label in labels) {
            val obj = label.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            if (name == labelName) {
                return obj["id"]?.jsonPrimitive?.longOrNull
            }
        }
        return null
    }

    private suspend fun createPullRequest(issue: Issue, branchName: String) {
        if (giteaClient == null) return
        try {
            val defaultBranch = getDefaultBranch()
            val body = buildJsonObject {
                put("title", "[${issue.identifier}] ${issue.title}")
                put("body", "Automated PR for issue ${issue.identifier}.\n\nCreated by RockOpera agent.")
                put("head", branchName)
                put("base", defaultBranch)
            }.toString()
            giteaClient.apiCall("POST", "/api/v1/repos/$owner/$repo/pulls", body)
        } catch (e: Exception) {
            log.warn("Failed to create pull request for issue {}: {}", issue.identifier, e.message)
        }
    }

    private suspend fun commentOnIssue(issue: Issue, message: String) {
        if (giteaClient == null) return
        try {
            val body = buildJsonObject {
                put("body", message)
            }.toString()
            giteaClient.apiCall("POST", "/api/v1/repos/$owner/$repo/issues/${issue.id}/comments", body)
        } catch (e: Exception) {
            log.warn("Failed to comment on issue {}: {}", issue.identifier, e.message)
        }
    }

    // --- PR helpers ---

    private suspend fun findPrForIssue(issue: Issue): JsonObject? {
        if (giteaClient == null) return null
        val branchName = "rockopera/issue-${issue.id}"
        val result = giteaClient.apiCall("GET",
            "/api/v1/repos/$owner/$repo/pulls?state=open&head=$owner:$branchName")
        val pulls = result.getOrNull()?.jsonArray ?: return null
        return pulls.firstOrNull()?.jsonObject
    }

    private suspend fun fetchPrDiff(prNumber: Long): String {
        if (giteaClient == null) return ""
        return try {
            val result = giteaClient.apiCall("GET",
                "/api/v1/repos/$owner/$repo/pulls/$prNumber.diff")
            result.getOrNull()?.jsonPrimitive?.contentOrNull ?: fetchPrDiffViaFiles(prNumber)
        } catch (e: Exception) {
            log.warn("Failed to fetch PR diff directly, falling back to files endpoint: {}", e.message)
            fetchPrDiffViaFiles(prNumber)
        }
    }

    private suspend fun fetchPrDiffViaFiles(prNumber: Long): String {
        if (giteaClient == null) return ""
        return try {
            val result = giteaClient.apiCall("GET",
                "/api/v1/repos/$owner/$repo/pulls/$prNumber/files")
            val files = result.getOrNull()?.jsonArray ?: return ""
            files.joinToString("\n\n") { file ->
                val obj = file.jsonObject
                val filename = obj["filename"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val patch = obj["patch"]?.jsonPrimitive?.contentOrNull ?: ""
                "--- $filename ---\n$patch"
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch PR files: {}", e.message)
            ""
        }
    }

    // --- Structured review parsing ---

    private data class ReviewComment(val path: String, val line: Int, val body: String)
    private data class StructuredReview(
        val verdict: Verdict,
        val summary: String,
        val comments: List<ReviewComment>
    )
    private enum class Verdict { APPROVED, CHANGES_REQUESTED }

    private fun extractAgentResultText(stdout: String): String {
        val jsonLines = stdout.lines().filter { it.isNotBlank() && it.trimStart().startsWith("{") }
        log.info("extractAgentResultText: total lines={}, json lines={}", stdout.lines().size, jsonLines.size)

        // Log all event types found
        val types = jsonLines.mapNotNull { line ->
            try {
                json.parseToJsonElement(line).jsonObject["type"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
        }
        log.info("extractAgentResultText: event types found: {}", types)

        for (line in stdout.lines().asReversed()) {
            if (line.isBlank() || !line.trimStart().startsWith("{")) continue
            val obj = try {
                json.parseToJsonElement(line).jsonObject
            } catch (_: Exception) {
                continue
            }
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "result") {
                val result = obj["result"]?.jsonPrimitive?.contentOrNull ?: ""
                log.info("extractAgentResultText: found result event, text length={}", result.length)
                return result
            }
        }
        log.warn("extractAgentResultText: no 'result' event found in stdout")
        return ""
    }

    private fun parseStructuredReview(resultText: String): StructuredReview? {
        // Extract JSON between ROCKOPERA_REVIEW_START and ROCKOPERA_REVIEW_END markers
        val startMarker = "ROCKOPERA_REVIEW_START"
        val endMarker = "ROCKOPERA_REVIEW_END"

        val startIdx = resultText.indexOf(startMarker)
        val endIdx = resultText.indexOf(endMarker)

        if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
            log.warn("No structured review block found in agent output")
            return null
        }

        val jsonStr = resultText.substring(startIdx + startMarker.length, endIdx).trim()

        return try {
            val obj = json.parseToJsonElement(jsonStr).jsonObject

            val verdictStr = obj["verdict"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "CHANGES_REQUESTED"
            val verdict = if (verdictStr == "APPROVED") Verdict.APPROVED else Verdict.CHANGES_REQUESTED

            val summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: ""

            val comments = obj["comments"]?.jsonArray?.mapNotNull { el ->
                val c = el.jsonObject
                val path = c["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val line = c["line"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val body = c["body"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ReviewComment(path, line, body)
            } ?: emptyList()

            StructuredReview(verdict, summary, comments)
        } catch (e: Exception) {
            log.warn("Failed to parse structured review JSON: {}", e.message)
            null
        }
    }

    private suspend fun submitGiteaReview(
        prNumber: Long,
        verdict: Verdict,
        review: StructuredReview?,
        fallbackText: String
    ) {
        if (giteaClient == null) return

        val summary = review?.summary ?: fallbackText.take(5000)
        val event = if (verdict == Verdict.APPROVED) "APPROVED" else "REQUEST_CHANGES"

        try {
            val body = buildJsonObject {
                put("body", summary)
                put("event", event)
                putJsonArray("comments") {
                    review?.comments?.forEach { comment ->
                        add(buildJsonObject {
                            put("path", comment.path)
                            put("new_position", comment.line)
                            put("body", comment.body)
                        })
                    }
                }
            }.toString()

            val result = giteaClient.apiCall("POST",
                "/api/v1/repos/$owner/$repo/pulls/$prNumber/reviews", body)
            log.info("Submitted Gitea review on PR #{}: {} with {} inline comments. API response: {}",
                prNumber, event, review?.comments?.size ?: 0, result.getOrNull()?.toString()?.take(500))
        } catch (e: Exception) {
            log.warn("Failed to submit review on PR #{}: {}", prNumber, e.message)
            // Fallback: post as a regular comment
            try {
                val fallback = buildJsonObject {
                    put("body", "**Code Review: ${verdict.name}**\n\n$summary")
                }.toString()
                giteaClient.apiCall("POST",
                    "/api/v1/repos/$owner/$repo/pulls/$prNumber/comments", fallback)
            } catch (e2: Exception) {
                log.warn("Fallback comment on PR #{} also failed: {}", prNumber, e2.message)
            }
        }
    }

    private fun parseVerdict(agentOutput: String): Verdict {
        val lines = agentOutput.lines().asReversed()
        for (line in lines) {
            val trimmed = line.trim().uppercase()
            if (trimmed.contains("VERDICT:APPROVED") || trimmed.contains("VERDICT: APPROVED")
                || trimmed.contains("\"APPROVED\"")) {
                return Verdict.APPROVED
            }
            if (trimmed.contains("VERDICT:CHANGES_REQUESTED") || trimmed.contains("VERDICT: CHANGES_REQUESTED")
                || trimmed.contains("\"CHANGES_REQUESTED\"")) {
                return Verdict.CHANGES_REQUESTED
            }
        }
        log.warn("No explicit verdict found in output, defaulting to CHANGES_REQUESTED")
        return Verdict.CHANGES_REQUESTED
    }

    // --- Shell command helper ---

    private fun runShellCommand(workDir: String, cmd: String, timeoutMs: Long = 60_000): ShellResult {
        val process = ProcessBuilder("sh", "-c", cmd)
            .directory(File(workDir))
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            return ShellResult(false, "timeout", -1)
        }
        val output = process.inputStream.bufferedReader().readText()
        return ShellResult(process.exitValue() == 0, output, process.exitValue())
    }

    private fun shellQuote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    // --- Agent environment ---

    private fun buildAgentEnv(
        issue: Issue,
        phase: PhaseConfig,
        prContext: PrContext?
    ): Map<String, String> {
        return buildMap {
            put("ROCKOPERA_ISSUE_ID", issue.id)
            put("ROCKOPERA_ISSUE_IDENTIFIER", issue.identifier)
            put("ROCKOPERA_ISSUE_TITLE", issue.title)
            put("ROCKOPERA_PHASE", phase.name)
            issue.url?.let { put("ROCKOPERA_ISSUE_URL", it) }
            prContext?.let {
                put("ROCKOPERA_PR_NUMBER", it.number.toString())
            }
        }
    }

    // --- Stream event parsing ---

    private fun tryParseStreamEvent(line: String, pid: String): AgentEvent? {
        if (line.isBlank() || !line.trimStart().startsWith("{")) return null

        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (_: Exception) {
            return null
        }

        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null

        val usage = extractUsage(obj)

        return when (type) {
            "result" -> AgentEvent(
                event = "agent_result",
                agentPid = pid,
                payload = mapOf(
                    "type" to type,
                    "result" to (obj["result"]?.jsonPrimitive?.contentOrNull ?: "")
                ),
                usage = usage
            )
            "assistant" -> AgentEvent(
                event = "notification",
                agentPid = pid,
                payload = mapOf("type" to type),
                usage = usage
            )
            else -> AgentEvent(
                event = "notification",
                agentPid = pid,
                payload = mapOf("type" to type),
                usage = usage
            )
        }
    }

    private fun extractUsage(obj: JsonObject): Map<String, Any?>? {
        val usage = obj["usage"]?.jsonObject
            ?: obj["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null

        val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L

        return mapOf(
            "input_tokens" to input,
            "output_tokens" to output,
            "total_tokens" to (input + output)
        )
    }
}
