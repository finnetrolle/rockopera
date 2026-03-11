package rockopera.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import rockopera.agent.AgentEvent
import rockopera.agent.AgentRunner
import rockopera.config.WorkflowConfig
import rockopera.config.WorkflowStore
import rockopera.model.Issue
import rockopera.model.RetryEntry
import rockopera.model.RunningEntry
import rockopera.tracker.TrackerAdapter
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class Orchestrator(
    private val workflowStore: WorkflowStore,
    private val trackerProvider: () -> TrackerAdapter,
    private val agentRunnerProvider: (WorkflowConfig) -> AgentRunner,
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(Orchestrator::class.java)
    val inbox = Channel<OrchestratorMessage>(Channel.UNLIMITED)
    private var state = OrchestratorState(
        pollIntervalMs = 30_000,
        maxConcurrentAgents = 10
    )
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "orchestrator-scheduler").also { it.isDaemon = true }
    }

    fun start(): Job = scope.launch {
        log.info("Orchestrator starting")

        val config = workflowStore.current()
        state.pollIntervalMs = config.pollingIntervalMs
        state.maxConcurrentAgents = config.maxConcurrentAgents

        // Startup terminal workspace cleanup
        startupCleanup(config)

        // Schedule first tick immediately
        launch { inbox.send(OrchestratorMessage.Tick) }

        // Main message loop (actor pattern)
        for (msg in inbox) {
            try {
                handleMessage(msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Error handling orchestrator message: ${msg::class.simpleName}", e)
            }
        }
    }

    private suspend fun handleMessage(msg: OrchestratorMessage) {
        when (msg) {
            is OrchestratorMessage.Tick -> onTick()
            is OrchestratorMessage.WorkerExited -> onWorkerExited(msg)
            is OrchestratorMessage.AgentUpdate -> onAgentUpdate(msg)
            is OrchestratorMessage.RetryFired -> onRetryFired(msg)
            is OrchestratorMessage.SnapshotRequest -> onSnapshotRequest(msg)
            is OrchestratorMessage.RefreshRequest -> onRefreshRequest(msg)
            is OrchestratorMessage.Shutdown -> {
                log.info("Orchestrator shutting down")
                state.running.values.forEach { it.workerJob.cancel() }
                state.retryAttempts.values.forEach { it.timerHandle?.cancel(false) }
                scheduler.shutdown()
                inbox.close()
            }
        }
    }

    // --- TICK ---

    private suspend fun onTick() {
        val config = workflowStore.current()

        state.pollIntervalMs = config.pollingIntervalMs
        state.maxConcurrentAgents = config.maxConcurrentAgents

        // Part 1: Reconcile
        reconcile(config)

        // Part 2: Validate dispatch config
        val validationError = validateDispatchConfig(config)
        if (validationError != null) {
            log.warn("Dispatch validation failed: {}", validationError)
            scheduleTick()
            return
        }

        // Part 3: Fetch candidates
        val tracker = trackerProvider()
        val candidatesResult = tracker.fetchCandidateIssues()
        if (candidatesResult.isFailure) {
            log.error("Failed to fetch candidate issues: {}", candidatesResult.exceptionOrNull()?.message)
            scheduleTick()
            return
        }

        val candidates = candidatesResult.getOrThrow()

        // Part 4: Sort
        val sorted = sortForDispatch(candidates)

        // Part 5: Dispatch eligible
        for (issue in sorted) {
            if (availableSlots(config) <= 0) break
            if (shouldDispatch(issue, config)) {
                dispatchIssue(issue, config, attempt = null)
            }
        }

        log.debug("Tick done: running={}, retrying={}, claimed={}",
            state.running.size, state.retryAttempts.size, state.claimed.size)

        scheduleTick()
    }

    // --- DISPATCH ---

    private fun dispatchIssue(issue: Issue, config: WorkflowConfig, attempt: Int?) {
        log.info("Dispatching issue={}, attempt={}", issue.identifier, attempt)

        val workerJob = scope.launch(SupervisorJob()) {
            try {
                val runner = agentRunnerProvider(config)
                runner.run(issue, attempt) { event ->
                    inbox.send(OrchestratorMessage.AgentUpdate(issue.id, event.event, eventToMap(event)))
                }
                inbox.send(OrchestratorMessage.WorkerExited(issue.id, Result.success(Unit)))
            } catch (e: CancellationException) {
                // Normal cancellation (reconciliation)
            } catch (e: Exception) {
                log.error("Worker failed: issue={}, error={}", issue.identifier, e.message)
                inbox.send(OrchestratorMessage.WorkerExited(issue.id, Result.failure(e)))
            }
        }

        state.running[issue.id] = RunningEntry(
            workerJob = workerJob,
            identifier = issue.identifier,
            issue = issue,
            issueId = issue.id,
            state = issue.state,
            startedAt = Instant.now()
        )
        state.claimed.add(issue.id)
        cancelExistingRetry(issue.id)
    }

    private fun shouldDispatch(issue: Issue, config: WorkflowConfig): Boolean {
        if (issue.id.isBlank() || issue.identifier.isBlank() || issue.title.isBlank() || issue.state.isBlank()) return false

        val stateNorm = issue.state.trim().lowercase()

        if (config.activeStates.none { it.trim().lowercase() == stateNorm }) return false
        if (config.terminalStates.any { it.trim().lowercase() == stateNorm }) return false

        if (state.running.containsKey(issue.id)) return false
        if (state.claimed.contains(issue.id)) return false

        val perStateLimit = config.maxConcurrentAgentsByState[stateNorm]
        if (perStateLimit != null) {
            val currentInState = state.running.values.count { it.state.trim().lowercase() == stateNorm }
            if (currentInState >= perStateLimit) return false
        }

        if (stateNorm == "todo") {
            val hasNonTerminalBlocker = issue.blockedBy.any { blocker ->
                val blockerState = blocker.state?.trim()?.lowercase()
                blockerState == null || config.terminalStates.none { it.trim().lowercase() == blockerState }
            }
            if (hasNonTerminalBlocker) return false
        }

        return true
    }

    private fun availableSlots(config: WorkflowConfig): Int =
        (config.maxConcurrentAgents - state.running.size).coerceAtLeast(0)

    private fun sortForDispatch(issues: List<Issue>): List<Issue> {
        return issues.sortedWith(
            compareBy<Issue> { it.priority ?: Int.MAX_VALUE }
                .thenBy { it.createdAt ?: Instant.MAX }
                .thenBy { it.identifier }
        )
    }

    // --- WORKER EXIT ---

    private fun onWorkerExited(msg: OrchestratorMessage.WorkerExited) {
        val entry = state.running.remove(msg.issueId) ?: return

        log.info("Worker exited: issue={}, success={}", entry.identifier, msg.result.isSuccess)

        val duration = java.time.Duration.between(entry.startedAt, Instant.now())
        state.agentTotals.secondsRunning += duration.toMillis() / 1000.0

        accumulateTokenDeltas(entry)

        if (msg.result.isSuccess) {
            scheduleRetry(msg.issueId, entry.identifier, 1, 1_000, null)
        } else {
            val existingAttempt = state.retryAttempts[msg.issueId]?.attempt ?: 0
            val nextAttempt = existingAttempt + 1
            val delay = computeBackoff(nextAttempt)
            val error = msg.result.exceptionOrNull()?.message
            scheduleRetry(msg.issueId, entry.identifier, nextAttempt, delay, error)
        }
    }

    // --- AGENT UPDATE ---

    private fun onAgentUpdate(msg: OrchestratorMessage.AgentUpdate) {
        val entry = state.running[msg.issueId] ?: return
        val payload = msg.payload

        entry.lastAgentEvent = msg.event
        entry.lastAgentTimestamp = Instant.now()
        entry.lastAgentMessage = payload["type"] ?: msg.event

        if (msg.event == "status") {
            val message = payload["message"]?.toString()
            if (message != null) {
                entry.activityLog.add(message)
                while (entry.activityLog.size > 20) entry.activityLog.removeFirst()
            }
        }

        if (msg.event == "session_started") {
            entry.sessionId = "${msg.issueId}-${entry.startedAt.toEpochMilli()}"
            entry.agentPid = payload["agent_pid"]?.toString()
            entry.turnCount++
        }

        // Token accounting
        val usage = payload["usage"] as? Map<*, *>
        if (usage != null) {
            val input = (usage["input_tokens"] as? Number)?.toLong() ?: 0
            val output = (usage["output_tokens"] as? Number)?.toLong() ?: 0
            val total = (usage["total_tokens"] as? Number)?.toLong() ?: 0

            if (total >= entry.lastReportedTotalTokens) {
                val inputDelta = (input - entry.lastReportedInputTokens).coerceAtLeast(0)
                val outputDelta = (output - entry.lastReportedOutputTokens).coerceAtLeast(0)
                val totalDelta = (total - entry.lastReportedTotalTokens).coerceAtLeast(0)

                entry.agentInputTokens += inputDelta
                entry.agentOutputTokens += outputDelta
                entry.agentTotalTokens += totalDelta
                entry.lastReportedInputTokens = input
                entry.lastReportedOutputTokens = output
                entry.lastReportedTotalTokens = total

                state.agentTotals.inputTokens += inputDelta
                state.agentTotals.outputTokens += outputDelta
                state.agentTotals.totalTokens += totalDelta
            }
        }

        // Rate limits
        val rateLimits = payload["rate_limits"]
        if (rateLimits is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            state.agentRateLimits = rateLimits as Map<String, Any?>
        }
    }

    // --- RETRY ---

    private fun onRetryFired(msg: OrchestratorMessage.RetryFired) {
        val retryEntry = state.retryAttempts.remove(msg.issueId)
        if (retryEntry == null) {
            log.debug("Retry fired for unknown issue={}", msg.issueId)
            return
        }

        log.info("Retry fired: issue={}, attempt={}", retryEntry.identifier, retryEntry.attempt)

        scope.launch {
            try {
                val tracker = trackerProvider()
                val config = workflowStore.current()
                val result = tracker.fetchCandidateIssues()

                if (result.isFailure) {
                    log.error("Retry fetch failed for issue={}", retryEntry.identifier)
                    releaseClaim(msg.issueId)
                    return@launch
                }

                val candidates = result.getOrThrow()
                val issue = candidates.find { it.id == msg.issueId }

                if (issue == null) {
                    log.info("Issue {} no longer a candidate, releasing claim", retryEntry.identifier)
                    releaseClaim(msg.issueId)
                    return@launch
                }

                if (shouldDispatch(issue.copy(), config)) {
                    if (availableSlots(config) > 0) {
                        state.claimed.remove(msg.issueId)
                        dispatchIssue(issue, config, retryEntry.attempt)
                    } else {
                        scheduleRetry(msg.issueId, retryEntry.identifier, retryEntry.attempt,
                            computeBackoff(retryEntry.attempt), "no available orchestrator slots")
                    }
                } else {
                    log.info("Issue {} no longer eligible, releasing claim", retryEntry.identifier)
                    releaseClaim(msg.issueId)
                }
            } catch (e: Exception) {
                log.error("Retry handling error for issue={}: {}", retryEntry.identifier, e.message)
                releaseClaim(msg.issueId)
            }
        }
    }

    private fun scheduleRetry(issueId: String, identifier: String, attempt: Int, delayMs: Long, error: String?) {
        cancelExistingRetry(issueId)
        val dueAtMs = System.currentTimeMillis() + delayMs

        val timerHandle: ScheduledFuture<*> = scheduler.schedule({
            scope.launch { inbox.send(OrchestratorMessage.RetryFired(issueId)) }
        }, delayMs, TimeUnit.MILLISECONDS)

        state.retryAttempts[issueId] = RetryEntry(
            issueId = issueId,
            identifier = identifier,
            attempt = attempt,
            dueAtMs = dueAtMs,
            timerHandle = timerHandle,
            error = error
        )

        log.info("Scheduled retry: issue={}, attempt={}, delay={}ms", identifier, attempt, delayMs)
    }

    private fun cancelExistingRetry(issueId: String) {
        state.retryAttempts.remove(issueId)?.timerHandle?.cancel(false)
    }

    private fun releaseClaim(issueId: String) {
        state.claimed.remove(issueId)
        state.retryAttempts.remove(issueId)?.timerHandle?.cancel(false)
    }

    private fun computeBackoff(attempt: Int): Long {
        val config = workflowStore.current()
        val raw = 10_000L * (1L shl (attempt - 1).coerceAtMost(20))
        return raw.coerceAtMost(config.maxRetryBackoffMs)
    }

    // --- RECONCILIATION ---

    private suspend fun reconcile(config: WorkflowConfig) {
        if (state.running.isEmpty()) return

        // Part A: Stall detection
        if (config.agentStallTimeoutMs > 0) {
            val now = System.currentTimeMillis()
            val stalled = state.running.entries.filter { (_, entry) ->
                val lastActivity = entry.lastAgentTimestamp?.toEpochMilli()
                    ?: entry.startedAt.toEpochMilli()
                (now - lastActivity) > config.agentStallTimeoutMs
            }.map { it.key to it.value }

            for ((issueId, entry) in stalled) {
                log.warn("Stall detected: issue={}, killing worker", entry.identifier)
                entry.workerJob.cancel()
                state.running.remove(issueId)
                val attempt = state.retryAttempts[issueId]?.attempt ?: 0
                scheduleRetry(issueId, entry.identifier, attempt + 1,
                    computeBackoff(attempt + 1), "stalled: no activity for ${config.agentStallTimeoutMs}ms")
            }
        }

        // Part B: Tracker state refresh
        val runningIds = state.running.keys.toList()
        if (runningIds.isEmpty()) return

        val tracker = trackerProvider()
        val stateResult = tracker.fetchIssueStatesByIds(runningIds)

        if (stateResult.isFailure) {
            log.warn("Reconciliation state refresh failed: {}", stateResult.exceptionOrNull()?.message)
            return
        }

        val freshIssues = stateResult.getOrThrow().associateBy { it.id }

        for (issueId in runningIds) {
            val entry = state.running[issueId] ?: continue
            val fresh = freshIssues[issueId]

            if (fresh == null) {
                log.info("Reconciliation: issue={} not found, terminating worker", entry.identifier)
                entry.workerJob.cancel()
                state.running.remove(issueId)
                releaseClaim(issueId)
                continue
            }

            val stateNorm = fresh.state.trim().lowercase()
            val isTerminal = config.terminalStates.any { it.trim().lowercase() == stateNorm }
            val isActive = config.activeStates.any { it.trim().lowercase() == stateNorm }

            when {
                isTerminal -> {
                    log.info("Reconciliation: issue={} is terminal ({}), terminating + cleanup",
                        entry.identifier, fresh.state)
                    entry.workerJob.cancel()
                    state.running.remove(issueId)
                    releaseClaim(issueId)
                }
                isActive -> {
                    state.running[issueId] = entry.copy(
                        issue = fresh,
                        state = fresh.state
                    )
                }
                else -> {
                    log.info("Reconciliation: issue={} is non-active ({}), terminating worker",
                        entry.identifier, fresh.state)
                    entry.workerJob.cancel()
                    state.running.remove(issueId)
                    releaseClaim(issueId)
                }
            }
        }
    }

    // --- STARTUP CLEANUP ---

    private suspend fun startupCleanup(config: WorkflowConfig) {
        try {
            val tracker = trackerProvider()
            val terminalResult = tracker.fetchIssuesByStates(config.terminalStates)
            if (terminalResult.isFailure) {
                log.warn("Startup cleanup fetch failed: {}", terminalResult.exceptionOrNull()?.message)
                return
            }
            val terminalIssues = terminalResult.getOrThrow()
            for (issue in terminalIssues) {
                try {
                    val runner = agentRunnerProvider(config)
                    log.info("Startup cleanup: removing workspace for terminal issue={}", issue.identifier)
                } catch (e: Exception) {
                    log.warn("Startup cleanup failed for issue={}: {}", issue.identifier, e.message)
                }
            }
        } catch (e: Exception) {
            log.warn("Startup cleanup error: {}", e.message)
        }
    }

    // --- SNAPSHOT ---

    private fun onSnapshotRequest(msg: OrchestratorMessage.SnapshotRequest) {
        msg.response.complete(buildSnapshot())
    }

    private fun onRefreshRequest(msg: OrchestratorMessage.RefreshRequest) {
        scope.launch { inbox.send(OrchestratorMessage.Tick) }
        msg.response.complete(RefreshResult(queued = true, coalesced = false))
    }

    private fun buildSnapshot(): OrchestratorSnapshot {
        val activeRuntime = state.running.values.sumOf { entry ->
            java.time.Duration.between(entry.startedAt, Instant.now()).toMillis() / 1000.0
        }

        val running = state.running.map { (id, entry) ->
            id to RunningSnapshot(
                issueId = id,
                issueIdentifier = entry.identifier,
                state = entry.state,
                sessionId = entry.sessionId,
                turnCount = entry.turnCount,
                lastEvent = entry.lastAgentEvent,
                lastMessage = entry.lastAgentMessage?.toString(),
                startedAt = entry.startedAt.toString(),
                lastEventAt = entry.lastAgentTimestamp?.toString(),
                inputTokens = entry.agentInputTokens,
                outputTokens = entry.agentOutputTokens,
                totalTokens = entry.agentTotalTokens,
                activityLog = entry.activityLog.toList()
            )
        }.toMap()

        val retrying = state.retryAttempts.map { (id, entry) ->
            id to RetrySnapshot(
                issueId = id,
                issueIdentifier = entry.identifier,
                attempt = entry.attempt,
                dueAt = Instant.ofEpochMilli(entry.dueAtMs).toString(),
                error = entry.error
            )
        }.toMap()

        return OrchestratorSnapshot(
            running = running,
            retrying = retrying,
            agentTotals = state.agentTotals.copy(
                secondsRunning = state.agentTotals.secondsRunning + activeRuntime
            ),
            agentRateLimits = state.agentRateLimits
        )
    }

    // --- HELPERS ---

    private fun scheduleTick() {
        scope.launch {
            delay(state.pollIntervalMs)
            inbox.send(OrchestratorMessage.Tick)
        }
    }

    private fun validateDispatchConfig(config: WorkflowConfig): String? {
        if (config.trackerKind != "linear" && config.trackerKind != "memory" && config.trackerKind != "gitea")
            return "Unsupported tracker kind: ${config.trackerKind}"
        if (config.trackerApiKey.isNullOrBlank() && config.trackerKind in listOf("linear", "gitea"))
            return "Missing tracker API key"
        if (config.trackerProjectSlug.isNullOrBlank() && config.trackerKind in listOf("linear", "gitea"))
            return "Missing tracker project slug"
        if (config.agentCommand.isBlank())
            return "Missing agent command"
        return null
    }

    private fun accumulateTokenDeltas(entry: RunningEntry) {
        // Final flush — tokens already accumulated via onAgentUpdate; nothing extra needed
    }

    private fun eventToMap(event: AgentEvent): Map<String, Any?> {
        return buildMap {
            put("event", event.event)
            put("timestamp", event.timestamp.toString())
            put("agent_pid", event.agentPid)
            putAll(event.payload)
            event.usage?.let { put("usage", it) }
        }
    }

    // Helper to copy RunningEntry (which uses var fields)
    private fun RunningEntry.copy(
        issue: Issue = this.issue,
        state: String = this.state
    ): RunningEntry {
        val new = RunningEntry(
            workerJob = this.workerJob,
            identifier = this.identifier,
            issue = issue,
            issueId = this.issueId,
            state = state,
            startedAt = this.startedAt
        )
        new.sessionId = this.sessionId
        new.agentPid = this.agentPid
        new.lastAgentMessage = this.lastAgentMessage
        new.lastAgentEvent = this.lastAgentEvent
        new.lastAgentTimestamp = this.lastAgentTimestamp
        new.agentInputTokens = this.agentInputTokens
        new.agentOutputTokens = this.agentOutputTokens
        new.agentTotalTokens = this.agentTotalTokens
        new.lastReportedInputTokens = this.lastReportedInputTokens
        new.lastReportedOutputTokens = this.lastReportedOutputTokens
        new.lastReportedTotalTokens = this.lastReportedTotalTokens
        new.turnCount = this.turnCount
        new.activityLog = this.activityLog.toMutableList()
        return new
    }
}
