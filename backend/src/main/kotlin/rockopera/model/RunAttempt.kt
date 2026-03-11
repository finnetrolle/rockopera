package rockopera.model

import java.time.Instant

data class RunAttempt(
    val issueId: String,
    val issueIdentifier: String,
    val attempt: Int?,
    val workspacePath: String,
    val startedAt: Instant,
    val status: RunStatus,
    val error: String? = null
)

enum class RunStatus {
    PREPARING_WORKSPACE,
    BUILDING_PROMPT,
    LAUNCHING_AGENT_PROCESS,
    INITIALIZING_SESSION,
    STREAMING_TURN,
    FINISHING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    STALLED,
    CANCELED_BY_RECONCILIATION
}
