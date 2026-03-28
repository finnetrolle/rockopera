package rockopera.agent

import rockopera.config.LlmProfileConfig
import rockopera.config.PhaseConfig
import rockopera.config.WorkflowConfig
import rockopera.workspace.WorkspaceManager
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRunnerTest {

    @Test
    fun `phase command takes precedence over profile command`() {
        val config = WorkflowConfig(agentCommand = "claude -p")
        val runner = AgentRunner(config, WorkspaceManager(config))
        val phase = PhaseConfig(
            name = "review",
            triggerStates = listOf("review"),
            command = "claude -p --model review-model"
        )
        val profile = LlmProfileConfig(
            id = "glm",
            label = "GLM",
            command = "claude -p --model profile-model"
        )

        val command = runner.resolveAgentCommand(phase, profile)

        assertEquals("claude -p --model review-model", command)
    }
}
