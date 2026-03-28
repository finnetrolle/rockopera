package rockopera.web

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import rockopera.agent.AgentRunner
import rockopera.config.LlmProfileConfig
import rockopera.config.LlmProfileStore
import rockopera.config.WorkflowConfig
import rockopera.config.WorkflowStore
import rockopera.orchestrator.Orchestrator
import rockopera.tracker.MemoryAdapter
import rockopera.workspace.WorkspaceManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservabilityApiTest {

    @Test
    fun `post llm active returns 400 for invalid json`() = testApplication {
        application {
            installTestApi()
        }

        val response = client.post("/api/v1/llm/active") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `post llm active returns 400 when profile_id is missing`() = testApplication {
        application {
            installTestApi()
        }

        val response = client.post("/api/v1/llm/active") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

private fun Application.installTestApi() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    val tempDir = Files.createTempDirectory("rockopera-observability-api-test")
    val workflowFile = tempDir.resolve("WORKFLOW.md")
    Files.writeString(workflowFile, "Test prompt")

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val workflowStore = WorkflowStore(workflowFile, scope)
    val llmProfileStore = LlmProfileStore {
        WorkflowConfig(
            llmProfiles = listOf(LlmProfileConfig(id = "glm", label = "GLM"))
        )
    }
    val memoryAdapter = MemoryAdapter()
    val orchestrator = Orchestrator(
        workflowStore = workflowStore,
        trackerProvider = { memoryAdapter },
        agentRunnerProvider = { config -> AgentRunner(config, WorkspaceManager(config)) },
        scope = scope
    )

    routing {
        route("/api/v1") {
            observabilityApi(orchestrator, llmProfileStore)
        }
    }
}
