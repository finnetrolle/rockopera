package rockopera.web

import kotlinx.serialization.json.*
import rockopera.orchestrator.OrchestratorSnapshot
import rockopera.orchestrator.RetrySnapshot
import rockopera.orchestrator.RunningSnapshot
import java.time.Instant

object Presenter {

    fun stateResponse(snapshot: OrchestratorSnapshot): String {
        return buildJsonObject {
            put("generated_at", Instant.now().toString())
            putJsonObject("counts") {
                put("running", snapshot.running.size)
                put("retrying", snapshot.retrying.size)
            }
            putJsonArray("running") {
                snapshot.running.values.forEach { entry ->
                    add(runningToJson(entry))
                }
            }
            putJsonArray("retrying") {
                snapshot.retrying.values.forEach { entry ->
                    add(retryToJson(entry))
                }
            }
            putJsonObject("agent_totals") {
                put("input_tokens", snapshot.agentTotals.inputTokens)
                put("output_tokens", snapshot.agentTotals.outputTokens)
                put("total_tokens", snapshot.agentTotals.totalTokens)
                put("seconds_running", snapshot.agentTotals.secondsRunning)
            }
            put("rate_limits", snapshot.agentRateLimits?.let { JsonNull } ?: JsonNull)
        }.toString()
    }

    fun issueResponse(
        identifier: String,
        running: RunningSnapshot?,
        retrying: RetrySnapshot?
    ): String {
        return buildJsonObject {
            put("issue_identifier", identifier)
            put("issue_id", running?.issueId ?: retrying?.issueId ?: "")
            put("status", if (running != null) "running" else "retrying")
            running?.let {
                putJsonObject("running") {
                    put("session_id", it.sessionId)
                    put("turn_count", it.turnCount)
                    put("state", it.state)
                    put("started_at", it.startedAt)
                    put("last_event", it.lastEvent)
                    put("last_message", it.lastMessage)
                    put("last_event_at", it.lastEventAt)
                    putJsonObject("tokens") {
                        put("input_tokens", it.inputTokens)
                        put("output_tokens", it.outputTokens)
                        put("total_tokens", it.totalTokens)
                    }
                }
            } ?: put("running", JsonNull)
            retrying?.let {
                putJsonObject("retry") {
                    put("attempt", it.attempt)
                    put("due_at", it.dueAt)
                    put("error", it.error)
                }
            } ?: put("retry", JsonNull)
        }.toString()
    }

    private fun runningToJson(entry: RunningSnapshot): JsonObject = buildJsonObject {
        put("issue_id", entry.issueId)
        put("issue_identifier", entry.issueIdentifier)
        put("state", entry.state)
        put("project_slug", entry.projectSlug)
        put("session_id", entry.sessionId)
        put("turn_count", entry.turnCount)
        put("last_event", entry.lastEvent)
        put("last_message", entry.lastMessage)
        put("started_at", entry.startedAt)
        put("last_event_at", entry.lastEventAt)
        putJsonObject("tokens") {
            put("input_tokens", entry.inputTokens)
            put("output_tokens", entry.outputTokens)
            put("total_tokens", entry.totalTokens)
        }
        putJsonArray("activity_log") {
            entry.activityLog.forEach { add(it) }
        }
    }

    private fun retryToJson(entry: RetrySnapshot): JsonObject = buildJsonObject {
        put("issue_id", entry.issueId)
        put("issue_identifier", entry.issueIdentifier)
        put("project_slug", entry.projectSlug)
        put("attempt", entry.attempt)
        put("due_at", entry.dueAt)
        put("error", entry.error)
    }
}
