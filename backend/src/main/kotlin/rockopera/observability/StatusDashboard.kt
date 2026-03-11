package rockopera.observability

import kotlinx.coroutines.*
import rockopera.orchestrator.OrchestratorMessage
import rockopera.orchestrator.OrchestratorSnapshot
import rockopera.orchestrator.Orchestrator
import java.time.Duration
import java.time.Instant

class StatusDashboard(
    private val orchestrator: Orchestrator,
    private val refreshMs: Long = 1_000,
    private val scope: CoroutineScope
) {
    private var lastRenderMs = 0L
    private val minRenderIntervalMs = 16L

    fun start(): Job = scope.launch {
        while (isActive) {
            delay(refreshMs)
            try {
                val deferred = CompletableDeferred<OrchestratorSnapshot>()
                orchestrator.inbox.send(OrchestratorMessage.SnapshotRequest(deferred))
                val snapshot = withTimeoutOrNull(2_000) { deferred.await() } ?: continue

                val now = System.currentTimeMillis()
                if (now - lastRenderMs < minRenderIntervalMs) continue
                lastRenderMs = now

                render(snapshot)
            } catch (_: CancellationException) {
                break
            } catch (_: Exception) {
                // Dashboard errors must not crash the orchestrator
            }
        }
    }

    private fun render(snapshot: OrchestratorSnapshot) {
        val sb = StringBuilder()
        sb.append("\u001b[2J\u001b[H") // Clear screen + cursor to top

        sb.appendLine("╔══════════════════════════════════════════════════════════════╗")
        sb.appendLine("║               RockOpera Status Dashboard                     ║")
        sb.appendLine("╚══════════════════════════════════════════════════════════════╝")
        sb.appendLine()

        // Metrics
        val totals = snapshot.agentTotals
        val runtime = formatDuration(totals.secondsRunning)
        sb.appendLine("  Running: ${snapshot.running.size}    Retrying: ${snapshot.retrying.size}    Tokens: ${totals.totalTokens}    Runtime: $runtime")
        sb.appendLine()

        // Running sessions
        if (snapshot.running.isNotEmpty()) {
            sb.appendLine("  ┌─ Running Sessions ────────────────────────────────────────┐")
            sb.appendLine("  │ %-12s %-14s %-6s %-10s %-16s │".format(
                "Issue", "State", "Turns", "Runtime", "Last Event"))
            sb.appendLine("  ├──────────────────────────────────────────────────────────┤")

            for ((_, entry) in snapshot.running) {
                val elapsed = try {
                    val started = Instant.parse(entry.startedAt)
                    formatDuration(Duration.between(started, Instant.now()).seconds.toDouble())
                } catch (_: Exception) { "?" }

                sb.appendLine("  │ %-12s %-14s %-6d %-10s %-16s │".format(
                    entry.issueIdentifier.take(12),
                    entry.state.take(14),
                    entry.turnCount,
                    elapsed,
                    (entry.lastEvent ?: "-").take(16)
                ))
            }
            sb.appendLine("  └──────────────────────────────────────────────────────────┘")
        }

        // Retry queue
        if (snapshot.retrying.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("  ┌─ Retry Queue ──────────────────────────────────────────────┐")
            sb.appendLine("  │ %-12s %-8s %-24s %-12s │".format("Issue", "Attempt", "Due At", "Error"))
            sb.appendLine("  ├──────────────────────────────────────────────────────────┤")

            for ((_, entry) in snapshot.retrying) {
                val dueAt = try {
                    Instant.parse(entry.dueAt).toString().takeLast(24)
                } catch (_: Exception) { entry.dueAt.take(24) }

                sb.appendLine("  │ %-12s %-8d %-24s %-12s │".format(
                    entry.issueIdentifier.take(12),
                    entry.attempt,
                    dueAt,
                    (entry.error ?: "-").take(12)
                ))
            }
            sb.appendLine("  └──────────────────────────────────────────────────────────┘")
        }

        // Rate limits
        snapshot.agentRateLimits?.let {
            sb.appendLine()
            sb.appendLine("  Rate Limits: $it")
        }

        print(sb)
    }

    private fun formatDuration(seconds: Double): String {
        val m = (seconds / 60).toInt()
        val s = (seconds % 60).toInt()
        return "${m}m ${s}s"
    }
}
