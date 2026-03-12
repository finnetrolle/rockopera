package rockopera.model

import java.util.concurrent.ScheduledFuture

data class RetryEntry(
    val issueId: String,
    val identifier: String,
    val projectSlug: String = "",
    val attempt: Int,
    val dueAtMs: Long,
    val timerHandle: ScheduledFuture<*>? = null,
    val error: String? = null
)
