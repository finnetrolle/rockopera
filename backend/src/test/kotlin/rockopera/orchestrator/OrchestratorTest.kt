package rockopera.orchestrator

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import rockopera.agent.AgentRunner
import rockopera.config.WorkflowConfig
import rockopera.config.WorkflowStore
import rockopera.model.Issue
import rockopera.tracker.MemoryAdapter
import rockopera.tracker.TrackerAdapter
import rockopera.workspace.WorkspaceManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestratorTest {

    private fun createTestConfig() = WorkflowConfig(
        trackerKind = "memory",
        trackerApiKey = "test-key",
        trackerProjectSlug = "test",
        pollingIntervalMs = 60_000, // long so tick doesn't auto-repeat during test
        maxConcurrentAgents = 2,
        agentCommand = "echo test"
    )

    @Test
    fun `snapshot returns empty state initially`() = runTest {
        val tempDir = Files.createTempDirectory("rockopera-test-workflow")
        val workflowFile = tempDir.resolve("WORKFLOW.md")
        Files.writeString(workflowFile, "Test prompt")

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store = WorkflowStore(workflowFile, scope)
        val memoryAdapter = MemoryAdapter()

        val orchestrator = Orchestrator(
            workflowStore = store,
            trackerProvider = { memoryAdapter },
            agentRunnerProvider = { config ->
                AgentRunner(config, WorkspaceManager(config))
            },
            scope = scope
        )
        orchestrator.start()

        // Give the actor a moment to process
        delay(100)

        val deferred = CompletableDeferred<OrchestratorSnapshot>()
        orchestrator.inbox.send(OrchestratorMessage.SnapshotRequest(deferred))
        val snapshot = deferred.await()

        assertTrue(snapshot.running.isEmpty())
        assertTrue(snapshot.retrying.isEmpty())
        assertEquals(0, snapshot.agentTotals.totalTokens)

        orchestrator.inbox.send(OrchestratorMessage.Shutdown)
        scope.cancel()

        // Cleanup
        Files.deleteIfExists(workflowFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `sortForDispatch orders by priority then createdAt then identifier`() {
        val issues = listOf(
            Issue(id = "3", identifier = "C-1", title = "C", state = "Todo", priority = 3),
            Issue(id = "1", identifier = "A-1", title = "A", state = "Todo", priority = 1),
            Issue(id = "2", identifier = "B-1", title = "B", state = "Todo", priority = 1),
            Issue(id = "4", identifier = "D-1", title = "D", state = "Todo", priority = null)
        )

        val sorted = issues.sortedWith(
            compareBy<Issue> { it.priority ?: Int.MAX_VALUE }
                .thenBy { it.createdAt ?: java.time.Instant.MAX }
                .thenBy { it.identifier }
        )

        assertEquals("A-1", sorted[0].identifier)
        assertEquals("B-1", sorted[1].identifier)
        assertEquals("C-1", sorted[2].identifier)
        assertEquals("D-1", sorted[3].identifier)
    }
}
