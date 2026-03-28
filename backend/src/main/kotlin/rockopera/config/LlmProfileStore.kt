package rockopera.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class LlmProfileStore(
    private val configProvider: () -> WorkflowConfig
) {
    @Volatile
    private var activeProfileOverrideId: String? = null

    @Synchronized
    fun snapshot(): LlmProfileSnapshot {
        val config = configProvider()
        val profiles = config.llmProfiles

        val defaultId = config.defaultLlmProfileId?.takeIf { wanted ->
            profiles.any { it.id == wanted }
        }

        val activeId = activeProfileOverrideId?.takeIf { wanted ->
            profiles.any { it.id == wanted }
        } ?: defaultId

        return LlmProfileSnapshot(
            activeProfileId = activeId,
            defaultProfileId = defaultId,
            profiles = profiles.map { profile ->
                LlmProfileSummary(
                    id = profile.id,
                    label = profile.label,
                    active = profile.id == activeId
                )
            }
        )
    }

    @Synchronized
    fun setActive(profileId: String): LlmProfileSnapshot {
        val trimmed = profileId.trim()
        require(trimmed.isNotEmpty()) { "profile_id is required" }

        val config = configProvider()
        require(config.llmProfiles.any { it.id == trimmed }) { "Unknown LLM profile: $trimmed" }

        activeProfileOverrideId = trimmed
        return snapshot()
    }

    fun activeProfile(): LlmProfileConfig? {
        val snapshot = snapshot()
        val activeId = snapshot.activeProfileId ?: return null
        return configProvider().llmProfiles.find { it.id == activeId }
    }
}

@Serializable
data class LlmProfileSnapshot(
    @SerialName("active_profile_id")
    val activeProfileId: String?,
    @SerialName("default_profile_id")
    val defaultProfileId: String?,
    val profiles: List<LlmProfileSummary>
)

@Serializable
data class LlmProfileSummary(
    val id: String,
    val label: String,
    val active: Boolean
)
