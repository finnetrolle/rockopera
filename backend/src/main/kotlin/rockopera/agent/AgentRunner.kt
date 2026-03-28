package rockopera.agent

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import rockopera.config.PhaseConfig
import rockopera.config.LlmProfileConfig
import rockopera.config.WorkflowConfig
import rockopera.model.Issue
import rockopera.model.IssueComment
import rockopera.tracker.GiteaClient
import rockopera.tracker.TrackerAdapter
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
    private val giteaClient: GiteaClient? = null,
    private val trackerAdapter: TrackerAdapter? = null,
    private val activeLlmProfileProvider: () -> LlmProfileConfig? = { null }
) {
    private val log = LoggerFactory.getLogger(AgentRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extract the Gitea issue number from a composite issue ID.
     * Composite format: "owner/repo#number" → returns "number"
     * Legacy format: plain number → returns as-is
     */
    private fun issueNumber(issue: Issue): String {
        val id = issue.id
        val hashIdx = id.lastIndexOf('#')
        return if (hashIdx >= 0) id.substring(hashIdx + 1) else id
    }

    /**
     * Get owner for the issue's repository.
     * Uses issue.repoOwner if set, otherwise falls back to config.trackerProjectSlug.
     */
    private fun ownerOf(issue: Issue): String {
        if (issue.repoOwner.isNotBlank()) return issue.repoOwner
        val slug = config.trackerProjectSlug ?: ""
        return slug.split("/", limit = 2).getOrElse(0) { "" }
    }

    /**
     * Get repo name for the issue's repository.
     * Uses issue.repoName if set, otherwise falls back to config.trackerProjectSlug.
     */
    private fun repoOf(issue: Issue): String {
        if (issue.repoName.isNotBlank()) return issue.repoName
        val slug = config.trackerProjectSlug ?: ""
        return slug.split("/", limit = 2).getOrElse(1) { "" }
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
                val branchName = "rockopera/issue-${issueNumber(issue)}"
                runShellCommand(workspace.path,
                    "git checkout $branchName 2>/dev/null || git checkout -b $branchName origin/$branchName")
            } else if (phase.createsPr) {
                // Coding-like phase
                if (workspace.createdNow) {
                    onEvent(statusEvent("Cloning repository..."))
                    gitClone(workspace.path, issue)
                } else {
                    onEvent(statusEvent("Updating repository..."))
                    runShellCommand(workspace.path, "git fetch origin")
                }
                // Check if the issue branch already exists on remote (rework after review)
                val branchName = "rockopera/issue-${issueNumber(issue)}"
                val remoteBranchCheck = runShellCommand(workspace.path,
                    "git rev-parse --verify origin/$branchName 2>/dev/null")
                if (remoteBranchCheck.success) {
                    // Rework: branch exists from previous coding+review cycle — reuse it
                    log.info("REWORK: Reusing existing branch {} for issue {}", branchName, issue.identifier)
                    onEvent(statusEvent("Resuming work on existing branch..."))
                    val checkoutRes = runShellCommand(workspace.path,
                        "git checkout $branchName 2>/dev/null || git checkout -b $branchName origin/$branchName")
                    log.info("REWORK: checkout result: success={}, output={}", checkoutRes.success, checkoutRes.output.take(200))
                    val resetRes = runShellCommand(workspace.path, "git reset --hard origin/$branchName")
                    log.info("REWORK: reset --hard result: success={}, output={}", resetRes.success, resetRes.output.take(200))
                    val headAfterReset = runShellCommand(workspace.path, "git rev-parse HEAD").output.trim()
                    log.info("REWORK: HEAD after reset: {}", headAfterReset.take(12))
                } else {
                    // Fresh start: create new branch from default
                    val defaultBranch = getDefaultBranch(issue)
                    runShellCommand(workspace.path, "git reset --hard origin/$defaultBranch")
                    onEvent(statusEvent("Creating branch..."))
                    gitCreateBranch(workspace.path, issue)
                }
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
            val diff = fetchPrDiff(issue, prNumber)
            prContext = PrContext(number = prNumber, title = prTitle, diff = diff)
            log.info("Found PR #{} for issue {}", prNumber, issue.identifier)
        }

        // 4. Run before_run hook
        workspaceManager.runBeforeRunHook(wsPath)

        // 5. Fetch review comments for context (useful when reworking after review)
        val reviewComments = fetchReviewComments(issue)

        // 6. Build prompt and launch agent
        onEvent(statusEvent("${phase.name} agent is working..."))
        val promptTemplate = phase.promptTemplate ?: config.promptTemplate
        val prompt = PromptBuilder.render(promptTemplate, issue, attempt, prContext, reviewComments)
        val llmProfile = activeLlmProfileProvider()
        val agentEnv = buildAgentEnv(issue, phase, prContext, llmProfile)
        val command = resolveAgentCommand(phase, llmProfile)

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
        // Log agent output summary for all phases
        val agentLines = stdout.lines()
        val agentJsonLines = agentLines.filter { it.isNotBlank() && it.trimStart().startsWith("{") }
        val agentEventTypes = agentJsonLines.mapNotNull { line ->
            try { json.parseToJsonElement(line).jsonObject["type"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
        }
        log.info("AGENT-OUTPUT: phase={}, stdout_length={}, lines={}, json_lines={}, event_types={}",
            phase.name, stdout.length, agentLines.size, agentJsonLines.size, agentEventTypes)

        // Log result text from agent
        val agentResultText = extractAgentResultText(stdout)
        if (agentResultText.isNotBlank()) {
            log.info("AGENT-OUTPUT: result text preview ({}ch): {}", agentResultText.length, agentResultText.take(1000))
        } else {
            log.warn("AGENT-OUTPUT: no result text found. Raw stdout preview: {}", stdout.take(1500))
        }

        // Log tool usage (edits, writes, bash commands)
        val toolUses = agentJsonLines.mapNotNull { line ->
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "assistant") {
                    val content = obj["message"]?.jsonObject?.get("content")?.jsonArray
                    content?.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
                        ?.map { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "unknown" }
                } else null
            } catch (_: Exception) { null }
        }.flatten()
        if (toolUses.isNotEmpty()) {
            log.info("AGENT-OUTPUT: tools used: {}", toolUses)
        } else {
            log.warn("AGENT-OUTPUT: agent used NO tools during phase '{}'", phase.name)
        }

        if (giteaClient != null) {
            // PR creation/update for coding-like phases
            if (phase.createsPr) {
                val workspace = workspaceManager.createOrReuse(issue.identifier)

                // Detailed state logging for debugging rework issues
                val currentBranchLog = runShellCommand(workspace.path, "git rev-parse --abbrev-ref HEAD").output.trim()
                val headLog = runShellCommand(workspace.path, "git rev-parse HEAD").output.trim()
                val statusLog = runShellCommand(workspace.path, "git status --porcelain").output.trim()
                val remoteHeadLog = runShellCommand(workspace.path, "git rev-parse origin/$currentBranchLog 2>/dev/null")
                val aheadLog = runShellCommand(workspace.path, "git rev-list origin/$currentBranchLog..HEAD --count 2>/dev/null")
                log.info("POST-AGENT state: branch={}, HEAD={}, remoteHEAD={}, ahead={}, uncommitted_files={}",
                    currentBranchLog, headLog.take(12),
                    if (remoteHeadLog.success) remoteHeadLog.output.trim().take(12) else "N/A",
                    if (aheadLog.success) aheadLog.output.trim() else "N/A",
                    statusLog.lines().filter { it.isNotBlank() }.size)
                if (statusLog.isNotBlank()) {
                    log.info("POST-AGENT uncommitted changes:\n{}", statusLog.take(500))
                }

                if (gitHasChanges(workspace.path)) {
                    log.info("POST-AGENT: changes detected, proceeding to commit and push")
                    onEvent(statusEvent("Committing changes..."))
                    val pushed = gitCommitAndPush(workspace.path, issue)

                    val branchName = "rockopera/issue-${issueNumber(issue)}"
                    val existingPr = findPrForIssue(issue)
                    if (existingPr != null) {
                        val prNumber = existingPr["number"]?.jsonPrimitive?.longOrNull
                        if (pushed) {
                            log.info("PR #{} updated with new commit for branch {}", prNumber, branchName)
                            onEvent(statusEvent("PR #$prNumber updated with new changes."))
                        } else {
                            log.warn("PR #{} exists but no new changes were pushed for branch {}. " +
                                "Agent may not have made any modifications during rework.", prNumber, branchName)
                            onEvent(statusEvent("PR #$prNumber exists but no new changes to push."))
                        }
                    } else {
                        if (pushed) {
                            onEvent(statusEvent("Creating pull request..."))
                            createPullRequest(issue, branchName)
                        } else {
                            log.warn("No PR found and no changes pushed for branch {}", branchName)
                        }
                    }
                } else {
                    log.warn("POST-AGENT: NO changes detected after agent run for issue {}. " +
                        "The agent may not have made any modifications.", issue.identifier)
                    onEvent(statusEvent("No changes detected after agent run."))
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
                    submitGiteaReview(issue, prContext.number, verdict, effectiveReview, resultText)
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
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
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
        val branchName = "rockopera/issue-${issueNumber(issue)}"
        val checkoutResult = runShellCommand(workDir, "git checkout $branchName 2>/dev/null || git checkout -b $branchName")
        if (!checkoutResult.success) {
            log.warn("Failed to create/checkout branch {}: {}", branchName, checkoutResult.output)
        }
    }

    private fun gitHasChanges(workDir: String): Boolean {
        // Check 1: uncommitted changes in working tree
        val status = runShellCommand(workDir, "git status --porcelain")
        val hasUncommitted = status.success && status.output.isNotBlank()
        log.info("HAS-CHANGES check 1 (uncommitted): {}, files={}", hasUncommitted,
            if (hasUncommitted) status.output.lines().filter { it.isNotBlank() }.size else 0)
        if (hasUncommitted) return true

        // Check 2: local commits that differ from the remote tracking branch
        // This catches cases where the agent committed changes itself
        val currentBranch = runShellCommand(workDir, "git rev-parse --abbrev-ref HEAD")
        if (currentBranch.success) {
            val branch = currentBranch.output.trim()
            val remoteDiff = runShellCommand(workDir, "git diff --stat origin/$branch HEAD 2>/dev/null")
            val hasDiff = remoteDiff.success && remoteDiff.output.isNotBlank()
            log.info("HAS-CHANGES check 2a (diff vs remote): {}", hasDiff)
            if (hasDiff) return true
            // Also check if there are new local commits not on remote
            val ahead = runShellCommand(workDir, "git rev-list origin/$branch..HEAD --count 2>/dev/null")
            val aheadCount = if (ahead.success) ahead.output.trim().toIntOrNull() ?: 0 else 0
            log.info("HAS-CHANGES check 2b (ahead of remote): commits={}", aheadCount)
            if (aheadCount > 0) return true
        }

        // Check 3: branch differs from default branch (main/master)
        val defaultBranch = runShellCommand(workDir, "git rev-parse --verify origin/main 2>/dev/null || git rev-parse --verify origin/master 2>/dev/null")
        if (defaultBranch.success) {
            val baseRef = defaultBranch.output.trim()
            val diff = runShellCommand(workDir, "git diff --stat $baseRef HEAD")
            val diffFromDefault = diff.success && diff.output.isNotBlank()
            log.info("HAS-CHANGES check 3 (diff vs default branch): {}", diffFromDefault)
            return diffFromDefault
        }
        log.warn("HAS-CHANGES: all checks failed, reporting no changes")
        return false
    }

    /**
     * Commit any uncommitted changes and push to remote.
     * Uses --force-with-lease to handle cases where the agent amended commits.
     * Returns true if new changes were actually pushed, false if nothing to push.
     */
    private fun gitCommitAndPush(workDir: String, issue: Issue): Boolean {
        // Record the remote HEAD before push to detect if we actually pushed something
        val currentBranch = runShellCommand(workDir, "git rev-parse --abbrev-ref HEAD").output.trim()
        val remoteHeadBefore = runShellCommand(workDir, "git rev-parse origin/$currentBranch 2>/dev/null")
            .let { if (it.success) it.output.trim() else null }
        val localHeadBefore = runShellCommand(workDir, "git rev-parse HEAD").output.trim()
        log.info("COMMIT-PUSH: branch={}, localHEAD={}, remoteHEAD={}",
            currentBranch, localHeadBefore.take(12), (remoteHeadBefore ?: "none").take(12))

        // Commit uncommitted changes if any
        val status = runShellCommand(workDir, "git status --porcelain")
        log.info("COMMIT-PUSH: uncommitted files count={}", status.output.lines().filter { it.isNotBlank() }.size)
        if (status.success && status.output.isNotBlank()) {
            log.info("COMMIT-PUSH: staging and committing uncommitted changes")
            val commitMsg = "[${issue.identifier}] ${issue.title}"
            val commitResult = runShellCommand(
                workDir,
                "git add -A && git commit -m ${shellQuote(commitMsg)}",
                timeoutMs = 60_000
            )
            if (!commitResult.success) {
                log.warn("COMMIT-PUSH: git commit failed (agent may have committed already): {}", commitResult.output.take(200))
            } else {
                log.info("COMMIT-PUSH: commit succeeded: {}", commitResult.output.take(200))
            }
        } else {
            log.info("COMMIT-PUSH: no uncommitted changes, checking for agent-made commits")
        }

        val localHeadAfter = runShellCommand(workDir, "git rev-parse HEAD").output.trim()
        log.info("COMMIT-PUSH: localHEAD after commit={}", localHeadAfter.take(12))

        // Check if there's anything new to push
        if (remoteHeadBefore != null && localHeadAfter == remoteHeadBefore) {
            log.info("COMMIT-PUSH: NOTHING TO PUSH — HEAD {} matches remote. Agent made no changes.", localHeadAfter.take(12))
            return false
        }
        log.info("COMMIT-PUSH: will push, local differs from remote ({} vs {})",
            localHeadAfter.take(12), (remoteHeadBefore ?: "none").take(12))

        // Use --force-with-lease to handle cases where the agent amended commits
        // This is safe: it only force-pushes if the remote hasn't been updated by someone else
        val pushResult = runShellCommand(workDir, "git push --force-with-lease -u origin HEAD", timeoutMs = 120_000)
        if (!pushResult.success) {
            // Fallback to regular push in case --force-with-lease is not supported
            log.warn("COMMIT-PUSH: force-with-lease push failed, trying regular push: {}", pushResult.output.take(200))
            val regularPush = runShellCommand(workDir, "git push -u origin HEAD", timeoutMs = 120_000)
            if (!regularPush.success) {
                log.error("COMMIT-PUSH: git push FAILED: {}", regularPush.output)
                throw RuntimeException("git push failed: ${regularPush.output.take(500)}")
            }
            log.info("COMMIT-PUSH: regular push succeeded")
        } else {
            log.info("COMMIT-PUSH: force-with-lease push succeeded: {}", pushResult.output.take(200))
        }

        // Verify remote was updated
        val remoteHeadAfterPush = runShellCommand(workDir, "git rev-parse origin/$currentBranch 2>/dev/null")
        log.info("COMMIT-PUSH: PUSHED OK. remote HEAD after push: {}",
            if (remoteHeadAfterPush.success) remoteHeadAfterPush.output.trim().take(12) else "unknown")
        return true
    }

    // --- Gitea API operations ---

    private suspend fun getDefaultBranch(issue: Issue): String {
        if (giteaClient == null) return "main"
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
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
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
        val number = issueNumber(issue)
        try {
            val labelId = findOrCreateLabel(issue, labelName)
            if (labelId != null) {
                val body = buildJsonObject {
                    putJsonArray("labels") { add(labelId) }
                }.toString()
                giteaClient.apiCall("POST", "/api/v1/repos/$owner/$repo/issues/$number/labels", body)
            }
        } catch (e: Exception) {
            log.warn("Failed to add label '{}' to issue {}: {}", labelName, issue.identifier, e.message)
        }
    }

    private suspend fun removeLabel(issue: Issue, labelName: String) {
        if (giteaClient == null) return
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
        val number = issueNumber(issue)
        try {
            val labelId = findLabelId(issue, labelName)
            if (labelId != null) {
                giteaClient.apiCall("DELETE", "/api/v1/repos/$owner/$repo/issues/$number/labels/$labelId")
            }
        } catch (e: Exception) {
            log.warn("Failed to remove label '{}' from issue {}: {}", labelName, issue.identifier, e.message)
        }
    }

    private suspend fun findOrCreateLabel(issue: Issue, labelName: String): Long? {
        if (giteaClient == null) return null
        val existingId = findLabelId(issue, labelName)
        if (existingId != null) return existingId

        val owner = ownerOf(issue)
        val repo = repoOf(issue)
        val body = buildJsonObject {
            put("name", labelName)
            put("color", "#0075ca")
        }.toString()
        val result = giteaClient.apiCall("POST", "/api/v1/repos/$owner/$repo/labels", body)
        val created = result.getOrNull()?.jsonObject
        return created?.get("id")?.jsonPrimitive?.longOrNull
    }

    private suspend fun findLabelId(issue: Issue, labelName: String): Long? {
        if (giteaClient == null) return null
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
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
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
        try {
            val defaultBranch = getDefaultBranch(issue)
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
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
        val number = issueNumber(issue)
        try {
            val body = buildJsonObject {
                put("body", message)
            }.toString()
            giteaClient.apiCall("POST", "/api/v1/repos/$owner/$repo/issues/$number/comments", body)
        } catch (e: Exception) {
            log.warn("Failed to comment on issue {}: {}", issue.identifier, e.message)
        }
    }

    // --- PR helpers ---

    private suspend fun findPrForIssue(issue: Issue): JsonObject? {
        if (giteaClient == null) return null
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
        val branchName = "rockopera/issue-${issueNumber(issue)}"
        val result = giteaClient.apiCall("GET",
            "/api/v1/repos/$owner/$repo/pulls?state=open&head=$owner:$branchName")
        val pulls = result.getOrNull()?.jsonArray ?: return null
        return pulls.firstOrNull()?.jsonObject
    }

    private suspend fun fetchPrDiff(issue: Issue, prNumber: Long): String {
        if (giteaClient == null) return ""
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
        return try {
            val result = giteaClient.apiCall("GET",
                "/api/v1/repos/$owner/$repo/pulls/$prNumber.diff")
            result.getOrNull()?.jsonPrimitive?.contentOrNull ?: fetchPrDiffViaFiles(issue, prNumber)
        } catch (e: Exception) {
            log.warn("Failed to fetch PR diff directly, falling back to files endpoint: {}", e.message)
            fetchPrDiffViaFiles(issue, prNumber)
        }
    }

    private suspend fun fetchPrDiffViaFiles(issue: Issue, prNumber: Long): String {
        if (giteaClient == null) return ""
        val owner = ownerOf(issue)
        val repo = repoOf(issue)
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
        issue: Issue,
        prNumber: Long,
        verdict: Verdict,
        review: StructuredReview?,
        fallbackText: String
    ) {
        if (giteaClient == null) return
        val owner = ownerOf(issue)
        val repo = repoOf(issue)

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
        log.debug("SHELL> [{}] {}", workDir.substringAfterLast('/'), cmd)
        val process = ProcessBuilder("sh", "-c", cmd)
            .directory(File(workDir))
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            log.warn("SHELL> TIMEOUT after {}ms: {}", timeoutMs, cmd)
            return ShellResult(false, "timeout", -1)
        }
        val output = process.inputStream.bufferedReader().readText()
        val result = ShellResult(process.exitValue() == 0, output, process.exitValue())
        if (cmd.startsWith("git ")) {
            if (result.success) {
                log.info("GIT> {} → OK{}", cmd,
                    if (result.output.isNotBlank()) " | ${result.output.trim().take(300)}" else "")
            } else {
                log.warn("GIT> {} → FAILED (exit={}) | {}", cmd, result.exitCode, result.output.trim().take(300))
            }
        }
        return result
    }

    private fun shellQuote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    // --- Review comments ---

    private suspend fun fetchReviewComments(issue: Issue): List<IssueComment> {
        if (trackerAdapter == null) return issue.comments
        return try {
            val comments = trackerAdapter.fetchIssueComments(issue.id).getOrElse {
                log.warn("Failed to fetch comments for issue {}: {}", issue.identifier, it.message)
                return issue.comments
            }
            if (comments.isNotEmpty()) {
                log.info("Fetched {} comments for issue {}", comments.size, issue.identifier)
            }
            comments
        } catch (e: Exception) {
            log.warn("Error fetching comments for issue {}: {}", issue.identifier, e.message)
            issue.comments
        }
    }

    // --- Agent environment ---

    private fun buildAgentEnv(
        issue: Issue,
        phase: PhaseConfig,
        prContext: PrContext?,
        llmProfile: LlmProfileConfig?
    ): Map<String, String> {
        return buildMap {
            putAll(llmProfile?.env.orEmpty())
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

    internal fun resolveAgentCommand(
        phase: PhaseConfig,
        llmProfile: LlmProfileConfig?
    ): String = phase.command ?: llmProfile?.command ?: config.agentCommand

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
            "assistant" -> {
                val summary = extractAssistantSummary(obj)
                if (summary != null) {
                    AgentEvent(
                        event = "status",
                        agentPid = pid,
                        payload = mapOf("type" to type, "message" to summary),
                        usage = usage
                    )
                } else {
                    AgentEvent(
                        event = "notification",
                        agentPid = pid,
                        payload = mapOf("type" to type),
                        usage = usage
                    )
                }
            }
            else -> AgentEvent(
                event = "notification",
                agentPid = pid,
                payload = mapOf("type" to type),
                usage = usage
            )
        }
    }

    /**
     * Extract a human-readable summary from a Claude stream-json "assistant" event.
     *
     * Claude's stream-json format emits assistant messages with content blocks:
     * - tool_use: {"type": "tool_use", "name": "write_to_file", "input": {"path": "src/main.kt", ...}}
     * - text: {"type": "text", "text": "I'll now implement..."}
     *
     * We extract tool names + key parameters and short text snippets to show
     * what the agent is doing in the activity log.
     */
    private fun extractAssistantSummary(obj: JsonObject): String? {
        val message = obj["message"]?.jsonObject ?: return null
        val content = message["content"]?.jsonArray ?: return null
        if (content.isEmpty()) return null

        val parts = mutableListOf<String>()

        for (block in content) {
            val blockObj = block.jsonObject
            val blockType = blockObj["type"]?.jsonPrimitive?.contentOrNull ?: continue

            when (blockType) {
                "tool_use" -> {
                    val toolName = blockObj["name"]?.jsonPrimitive?.contentOrNull ?: "unknown_tool"
                    val input = blockObj["input"]?.jsonObject
                    val detail = extractToolDetail(toolName, input)
                    parts.add(detail)
                }
                "text" -> {
                    val text = blockObj["text"]?.jsonPrimitive?.contentOrNull ?: continue
                    // Take first meaningful line as a summary, skip empty/whitespace
                    val firstLine = text.lines()
                        .map { it.trim() }
                        .firstOrNull { it.isNotBlank() && it.length > 3 }
                    if (firstLine != null) {
                        val truncated = if (firstLine.length > 120) firstLine.take(117) + "..." else firstLine
                        parts.add(truncated)
                    }
                }
            }
        }

        return if (parts.isNotEmpty()) parts.joinToString(" → ") else null
    }

    /**
     * Format a tool call into a concise human-readable string.
     */
    private fun extractToolDetail(toolName: String, input: JsonObject?): String {
        if (input == null) return "🔧 $toolName"

        return when (toolName) {
            "write_to_file", "create_file" -> {
                val path = input["path"]?.jsonPrimitive?.contentOrNull ?: "?"
                "📝 Write $path"
            }
            "edit_file", "apply_diff" -> {
                val path = input["path"]?.jsonPrimitive?.contentOrNull
                    ?: input["file_path"]?.jsonPrimitive?.contentOrNull ?: "?"
                "✏️ Edit $path"
            }
            "read_file" -> {
                val path = input["path"]?.jsonPrimitive?.contentOrNull
                    ?: input["files"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull ?: "?"
                "📖 Read $path"
            }
            "list_files" -> {
                val path = input["path"]?.jsonPrimitive?.contentOrNull ?: "."
                "📂 List $path"
            }
            "search_files", "grep", "ripgrep" -> {
                val pattern = input["regex"]?.jsonPrimitive?.contentOrNull
                    ?: input["pattern"]?.jsonPrimitive?.contentOrNull ?: "?"
                val path = input["path"]?.jsonPrimitive?.contentOrNull ?: ""
                "🔍 Search ${if (path.isNotBlank()) "$path " else ""}\"$pattern\""
            }
            "execute_command", "bash", "shell" -> {
                val cmd = input["command"]?.jsonPrimitive?.contentOrNull
                    ?: input["cmd"]?.jsonPrimitive?.contentOrNull ?: "?"
                val truncated = if (cmd.length > 80) cmd.take(77) + "..." else cmd
                "⚡ Run: $truncated"
            }
            "delete_file" -> {
                val path = input["path"]?.jsonPrimitive?.contentOrNull ?: "?"
                "🗑️ Delete $path"
            }
            else -> {
                // Generic tool — show name and first string parameter
                val firstParam = input.entries.firstOrNull { (_, v) ->
                    v is JsonPrimitive && v.isString
                }
                if (firstParam != null) {
                    val value = firstParam.value.jsonPrimitive.content
                    val truncated = if (value.length > 60) value.take(57) + "..." else value
                    "🔧 $toolName($truncated)"
                } else {
                    "🔧 $toolName"
                }
            }
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
