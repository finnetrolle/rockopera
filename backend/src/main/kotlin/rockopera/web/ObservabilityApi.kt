package rockopera.web

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import rockopera.orchestrator.Orchestrator
import rockopera.orchestrator.OrchestratorMessage
import rockopera.orchestrator.OrchestratorSnapshot
import java.time.Instant

fun Route.observabilityApi(orchestrator: Orchestrator) {

    get("/state") {
        val deferred = CompletableDeferred<OrchestratorSnapshot>()
        orchestrator.inbox.send(OrchestratorMessage.SnapshotRequest(deferred))

        val snapshot = withTimeoutOrNull(5_000) { deferred.await() }

        if (snapshot == null) {
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("generated_at", Instant.now().toString())
                putJsonObject("error") {
                    put("code", "snapshot_timeout")
                    put("message", "Snapshot timed out")
                }
            }.toString())
            return@get
        }

        call.respond(Presenter.stateResponse(snapshot))
    }

    get("/{issueIdentifier}") {
        val identifier = call.parameters["issueIdentifier"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        val deferred = CompletableDeferred<OrchestratorSnapshot>()
        orchestrator.inbox.send(OrchestratorMessage.SnapshotRequest(deferred))

        val snapshot = withTimeoutOrNull(5_000) { deferred.await() }
        if (snapshot == null) {
            call.respond(HttpStatusCode.InternalServerError)
            return@get
        }

        val running = snapshot.running.values.find { it.issueIdentifier == identifier }
        val retrying = snapshot.retrying.values.find { it.issueIdentifier == identifier }

        if (running == null && retrying == null) {
            call.respond(HttpStatusCode.NotFound, buildJsonObject {
                putJsonObject("error") {
                    put("code", "issue_not_found")
                    put("message", "Issue not found")
                }
            }.toString())
            return@get
        }

        call.respond(Presenter.issueResponse(identifier, running, retrying))
    }

    post("/refresh") {
        val deferred = CompletableDeferred<rockopera.orchestrator.RefreshResult>()
        orchestrator.inbox.send(OrchestratorMessage.RefreshRequest(deferred))

        val result = withTimeoutOrNull(5_000) { deferred.await() }
        if (result == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, buildJsonObject {
                putJsonObject("error") {
                    put("code", "orchestrator_unavailable")
                    put("message", "Orchestrator unavailable")
                }
            }.toString())
            return@post
        }

        call.respond(HttpStatusCode.Accepted, buildJsonObject {
            put("queued", result.queued)
            put("coalesced", result.coalesced)
            put("requested_at", Instant.now().toString())
            putJsonArray("operations") {
                add("poll")
                add("reconcile")
            }
        }.toString())
    }
}
