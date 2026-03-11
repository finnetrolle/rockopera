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

data class WorkflowConfig(
    // Tracker
    val trackerKind: String = "linear",
    val trackerEndpoint: String = "https://api.linear.app/graphql",
    val trackerApiKey: String? = null,
    val trackerProjectSlug: String? = null,
    val trackerAssignee: String? = null,
    val activeStates: List<String> = listOf("Todo", "In Progress"),
    val terminalStates: List<String> = listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done"),

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
)
