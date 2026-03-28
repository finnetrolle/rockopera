package rockopera.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmProfileStoreTest {

    @Test
    fun `uses workflow default profile when no override is set`() {
        val store = LlmProfileStore {
            WorkflowConfig(
                llmProfiles = listOf(
                    LlmProfileConfig(id = "glm", label = "GLM"),
                    LlmProfileConfig(id = "gpt41", label = "GPT-4.1")
                ),
                defaultLlmProfileId = "gpt41"
            )
        }

        val snapshot = store.snapshot()

        assertEquals("gpt41", snapshot.activeProfileId)
        assertEquals("gpt41", snapshot.defaultProfileId)
        assertEquals(listOf(false, true), snapshot.profiles.map { it.active })
    }

    @Test
    fun `setActive overrides workflow default`() {
        val store = LlmProfileStore {
            WorkflowConfig(
                llmProfiles = listOf(
                    LlmProfileConfig(id = "glm", label = "GLM"),
                    LlmProfileConfig(id = "o3", label = "o3")
                ),
                defaultLlmProfileId = "glm"
            )
        }

        val snapshot = store.setActive("o3")

        assertEquals("o3", snapshot.activeProfileId)
        assertEquals("o3", store.activeProfile()?.id)
    }

    @Test
    fun `setActive rejects unknown profile`() {
        val store = LlmProfileStore {
            WorkflowConfig(
                llmProfiles = listOf(LlmProfileConfig(id = "glm", label = "GLM")),
                defaultLlmProfileId = "glm"
            )
        }

        assertFailsWith<IllegalArgumentException> {
            store.setActive("missing")
        }
    }

    @Test
    fun `does not auto-activate first profile when workflow default is absent`() {
        val store = LlmProfileStore {
            WorkflowConfig(
                llmProfiles = listOf(
                    LlmProfileConfig(id = "glm", label = "GLM"),
                    LlmProfileConfig(id = "gpt41", label = "GPT-4.1")
                )
            )
        }

        val snapshot = store.snapshot()

        assertEquals(null, snapshot.defaultProfileId)
        assertEquals(null, snapshot.activeProfileId)
        assertEquals(listOf(false, false), snapshot.profiles.map { it.active })
    }
}
