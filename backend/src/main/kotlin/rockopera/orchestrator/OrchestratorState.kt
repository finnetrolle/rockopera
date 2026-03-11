package rockopera.orchestrator

import rockopera.model.RetryEntry
import rockopera.model.RunningEntry

data class OrchestratorState(
    var pollIntervalMs: Long,
    var maxConcurrentAgents: Int,
    var nextPollDueAtMs: Long = 0,
    var pollCheckInProgress: Boolean = false,
    val running: MutableMap<String, RunningEntry> = mutableMapOf(),
    val claimed: MutableSet<String> = mutableSetOf(),
    val retryAttempts: MutableMap<String, RetryEntry> = mutableMapOf(),
    val completed: MutableSet<String> = mutableSetOf(),
    var agentTotals: AgentTotals = AgentTotals(),
    var agentRateLimits: Map<String, Any?>? = null
)

data class AgentTotals(
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var totalTokens: Long = 0,
    var secondsRunning: Double = 0.0
)
