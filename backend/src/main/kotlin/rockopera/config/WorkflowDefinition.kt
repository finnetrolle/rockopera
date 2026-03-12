package rockopera.config

data class WorkflowDefinition(
    val config: Map<String, Any?>,
    val promptTemplate: String
)

data class PhaseConfig(
    val name: String,
    val triggerStates: List<String>,
    val command: String? = null,
    val promptTemplate: String? = null,
    val createsPr: Boolean = false,
    val needsPrDiff: Boolean = false,
    val verdictBased: Boolean = false,
    val onSuccess: String = "done",
    val onFailure: String? = null,
    val onApproved: String = "done",
    val onChangesRequested: String = "todo",
    val labelOnStart: String? = null
)

/**
 * Per-project configuration. Each project represents a single owner/repo
 * on the same Gitea instance. Fields that are null fall back to the
 * base WorkflowConfig values.
 */
data class ProjectConfig(
    val slug: String,
    val owner: String,
    val repo: String,
    val hookAfterCreate: String? = null,
    val hookBeforeRun: String? = null,
    val hookAfterRun: String? = null,
    val hookBeforeRemove: String? = null,
    val promptTemplate: String? = null,
    val activeStates: List<String>? = null,
    val terminalStates: List<String>? = null,
    val phases: List<PhaseConfig>? = null
)

data class WorkflowConfig(
    // Tracker
    val trackerKind: String = "linear",
    val trackerEndpoint: String = "https://api.linear.app/graphql",
    val trackerApiKey: String? = null,
    val trackerProjectSlug: String? = null,
    val trackerAssignee: String? = null,
    val activeStates: List<String> = listOf("Todo", "In Progress"),
    val terminalStates: List<String> = listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done"),

    // Multi-repo projects (Gitea only)
    val projects: List<ProjectConfig> = emptyList(),

    // Polling
    val pollingIntervalMs: Long = 30_000,

    // Workspace
    val workspaceRoot: String? = null,

    // Hooks
    val hookAfterCreate: String? = null,
    val hookBeforeRun: String? = null,
    val hookAfterRun: String? = null,
    val hookBeforeRemove: String? = null,
    val hookTimeoutMs: Long = 60_000,

    // Agent
    val maxConcurrentAgents: Int = 10,
    val maxTurns: Int = 20,
    val maxRetryBackoffMs: Long = 300_000,
    val maxConcurrentAgentsByState: Map<String, Int> = emptyMap(),
    val agentCommand: String = "claude -p --verbose --output-format stream-json --dangerously-skip-permissions",
    val agentTurnTimeoutMs: Long = 3_600_000,
    val agentStallTimeoutMs: Long = 300_000,

    // Phases
    val phases: List<PhaseConfig> = emptyList(),

    // Observability
    val dashboardEnabled: Boolean = true,
    val dashboardRefreshMs: Int = 1_000,
    val dashboardRenderIntervalMs: Int = 16,

    // Server
    val serverPort: Int? = null,
    val serverHost: String = "127.0.0.1",

    // Prompt
    val promptTemplate: String = ""
) {
    /**
     * Returns the effective list of projects. If [projects] is non-empty, returns it.
     * Otherwise, falls back to the legacy single [trackerProjectSlug].
     */
    fun effectiveProjects(): List<ProjectConfig> {
        if (projects.isNotEmpty()) return projects
        val slug = trackerProjectSlug ?: return emptyList()
        val parts = slug.split("/", limit = 2)
        if (parts.size != 2) return emptyList()
        return listOf(ProjectConfig(slug = slug, owner = parts[0], repo = parts[1]))
    }

    /**
     * Returns a WorkflowConfig with per-project overrides applied.
     * Hook, prompt, phase, and state overrides from the project take precedence.
     */
    fun effectiveConfigForProject(project: ProjectConfig): WorkflowConfig = copy(
        trackerProjectSlug = project.slug,
        hookAfterCreate = project.hookAfterCreate ?: hookAfterCreate,
        hookBeforeRun = project.hookBeforeRun ?: hookBeforeRun,
        hookAfterRun = project.hookAfterRun ?: hookAfterRun,
        hookBeforeRemove = project.hookBeforeRemove ?: hookBeforeRemove,
        promptTemplate = project.promptTemplate ?: promptTemplate,
        activeStates = project.activeStates ?: activeStates,
        terminalStates = project.terminalStates ?: terminalStates,
        phases = project.phases ?: phases
    )
}
