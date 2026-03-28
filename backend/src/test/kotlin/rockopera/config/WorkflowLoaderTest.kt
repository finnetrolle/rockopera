package rockopera.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowLoaderTest {

    @Test
    fun `parse file with no front matter`() {
        val content = "Just a prompt template"
        val def = WorkflowLoader.parse(content)
        assertTrue(def.config.isEmpty())
        assertEquals("Just a prompt template", def.promptTemplate)
    }

    @Test
    fun `parse file with front matter and prompt`() {
        val content = """
            ---
            tracker:
              kind: linear
              project_slug: my-project
            polling:
              interval_ms: 5000
            ---
            You are working on {{ issue.title }}
        """.trimIndent()

        val def = WorkflowLoader.parse(content)
        assertTrue(def.config.containsKey("tracker"))
        assertTrue(def.config.containsKey("polling"))
        assertEquals("You are working on {{ issue.title }}", def.promptTemplate)
    }

    @Test
    fun `buildConfig applies defaults`() {
        val def = WorkflowDefinition(config = emptyMap(), promptTemplate = "test")
        val cfg = WorkflowLoader.buildConfig(def)

        assertEquals("linear", cfg.trackerKind)
        assertEquals(30_000, cfg.pollingIntervalMs)
        assertEquals(10, cfg.maxConcurrentAgents)
        assertEquals(20, cfg.maxTurns)
        assertEquals(300_000, cfg.maxRetryBackoffMs)
        assertEquals("claude -p --verbose --output-format stream-json --dangerously-skip-permissions", cfg.agentCommand)
    }

    @Test
    fun `buildConfig reads nested tracker values`() {
        val config = mapOf(
            "tracker" to mapOf(
                "kind" to "linear",
                "project_slug" to "test-slug",
                "active_states" to listOf("Todo", "Working")
            ),
            "agent" to mapOf(
                "max_concurrent_agents" to 5,
                "max_turns" to 10
            )
        )
        val def = WorkflowDefinition(config = config, promptTemplate = "")
        val cfg = WorkflowLoader.buildConfig(def)

        assertEquals("test-slug", cfg.trackerProjectSlug)
        assertEquals(listOf("Todo", "Working"), cfg.activeStates)
        assertEquals(5, cfg.maxConcurrentAgents)
        assertEquals(10, cfg.maxTurns)
    }

    @Test
    fun `buildConfig parses phases`() {
        val config = mapOf(
            "phases" to mapOf(
                "coding" to mapOf(
                    "trigger_states" to listOf("todo"),
                    "creates_pr" to true,
                    "on_success" to "review",
                    "label_on_start" to "in-progress"
                ),
                "review" to mapOf(
                    "trigger_states" to listOf("review"),
                    "needs_pr_diff" to true,
                    "verdict_based" to true,
                    "on_approved" to "done",
                    "on_changes_requested" to "todo",
                    "prompt_template" to "Review this PR: {{ pr.title }}"
                )
            )
        )
        val def = WorkflowDefinition(config = config, promptTemplate = "")
        val cfg = WorkflowLoader.buildConfig(def)

        assertEquals(2, cfg.phases.size)

        val coding = cfg.phases.find { it.name == "coding" }!!
        assertEquals(listOf("todo"), coding.triggerStates)
        assertTrue(coding.createsPr)
        assertEquals("review", coding.onSuccess)
        assertEquals("in-progress", coding.labelOnStart)

        val review = cfg.phases.find { it.name == "review" }!!
        assertEquals(listOf("review"), review.triggerStates)
        assertTrue(review.needsPrDiff)
        assertTrue(review.verdictBased)
        assertEquals("done", review.onApproved)
        assertEquals("todo", review.onChangesRequested)
        assertEquals("Review this PR: {{ pr.title }}", review.promptTemplate)
    }

    @Test
    fun `buildConfig with no phases returns empty list`() {
        val def = WorkflowDefinition(config = emptyMap(), promptTemplate = "test")
        val cfg = WorkflowLoader.buildConfig(def)
        assertTrue(cfg.phases.isEmpty())
    }

    @Test
    fun `CSV string parsed as list for active_states`() {
        val config = mapOf(
            "tracker" to mapOf(
                "active_states" to "Todo, In Progress, Review"
            )
        )
        val def = WorkflowDefinition(config = config, promptTemplate = "")
        val cfg = WorkflowLoader.buildConfig(def)

        assertEquals(listOf("Todo", "In Progress", "Review"), cfg.activeStates)
    }

    @Test
    fun `buildConfig parses llm profiles`() {
        val config = mapOf(
            "agent" to mapOf(
                "llm_profiles" to mapOf(
                    "default" to "glm",
                    "items" to listOf(
                        mapOf(
                            "id" to "glm",
                            "label" to "GLM-5",
                            "env" to mapOf(
                                "ANTHROPIC_MODEL" to "sonnet",
                                "ANTHROPIC_DEFAULT_SONNET_MODEL" to "glm-5"
                            )
                        ),
                        mapOf(
                            "id" to "o3",
                            "label" to "o3",
                            "command" to "claude -p --model opus",
                            "env" to mapOf(
                                "ANTHROPIC_MODEL" to "opus",
                                "ANTHROPIC_DEFAULT_OPUS_MODEL" to "rockopera-opus"
                            )
                        )
                    )
                )
            )
        )

        val def = WorkflowDefinition(config = config, promptTemplate = "")
        val cfg = WorkflowLoader.buildConfig(def)

        assertEquals("glm", cfg.defaultLlmProfileId)
        assertEquals(2, cfg.llmProfiles.size)
        assertEquals("GLM-5", cfg.llmProfiles[0].label)
        assertEquals("glm-5", cfg.llmProfiles[0].env["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("claude -p --model opus", cfg.llmProfiles[1].command)
        assertEquals("opus", cfg.llmProfiles[1].env["ANTHROPIC_MODEL"])
    }
}
