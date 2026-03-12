package rockopera.orchestrator

import kotlinx.coroutines.CompletableDeferred

sealed class OrchestratorMessage {
    data object Tick : OrchestratorMessage()
    data class WorkerExited(val issueId: String, val result: Result<Unit>) : OrchestratorMessage()
    data class AgentUpdate(val issueId: String, val event: String, val payload: Map<String, Any?>) : OrchestratorMessage()
    data class RetryFired(val issueId: String) : OrchestratorMessage()
    data class SnapshotRequest(val response: CompletableDeferred<OrchestratorSnapshot>) : OrchestratorMessage()
    data class RefreshRequest(val response: CompletableDeferred<RefreshResult>) : OrchestratorMessage()
    data object Shutdown : OrchestratorMessage()
}

data class OrchestratorSnapshot(
    val running: Map<String, RunningSnapshot>,
    val retrying: Map<String, RetrySnapshot>,
    val agentTotals: AgentTotals,
    val agentRateLimits: Map<String, Any?>?
)

data class RunningSnapshot(
    val issueId: String,
    val issueIdentifier: String,
    val state: String,
    val projectSlug: String = "",
    val sessionId: String?,
    val turnCount: Int,
    val lastEvent: String?,
    val lastMessage: String?,
    val startedAt: String,
    val lastEventAt: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val activityLog: List<String> = emptyList()
)

data class RetrySnapshot(
    val issueId: String,
    val issueIdentifier: String,
    val projectSlug: String = "",
    val attempt: Int,
    val dueAt: String,
    val error: String?
)

data class RefreshResult(
    val queued: Boolean,
    val coalesced: Boolean
)
