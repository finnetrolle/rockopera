package rockopera.model

import kotlinx.coroutines.Job
import java.time.Instant

data class RunningEntry(
    val workerJob: Job,
    val identifier: String,
    val issue: Issue,
    val issueId: String,
    val state: String,
    val startedAt: Instant,
    var sessionId: String? = null,
    var agentPid: String? = null,
    var lastAgentMessage: Any? = null,
    var lastAgentEvent: String? = null,
    var lastAgentTimestamp: Instant? = null,
    var agentInputTokens: Long = 0,
    var agentOutputTokens: Long = 0,
    var agentTotalTokens: Long = 0,
    var lastReportedInputTokens: Long = 0,
    var lastReportedOutputTokens: Long = 0,
    var lastReportedTotalTokens: Long = 0,
    var turnCount: Int = 0,
    var activityLog: MutableList<String> = mutableListOf()
)
