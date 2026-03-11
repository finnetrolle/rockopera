package rockopera.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Path

object WorkflowLoader {
    private val log = LoggerFactory.getLogger(WorkflowLoader::class.java)
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun load(path: Path): WorkflowDefinition {
        val content = path.toFile().readText()
        return parse(content)
    }

    fun parse(content: String): WorkflowDefinition {
        val trimmed = content.trim()

        if (!trimmed.startsWith("---")) {
            return WorkflowDefinition(config = emptyMap(), promptTemplate = trimmed)
        }

        val secondDelimiter = trimmed.indexOf("---", 3)
        if (secondDelimiter == -1) {
            return WorkflowDefinition(config = emptyMap(), promptTemplate = trimmed)
        }

        val yamlBlock = trimmed.substring(3, secondDelimiter).trim()
        val promptBody = trimmed.substring(secondDelimiter + 3).trim()

        val config: Map<String, Any?> = if (yamlBlock.isNotEmpty()) {
            try {
                @Suppress("UNCHECKED_CAST")
                val parsed = yamlMapper.readValue(yamlBlock, Map::class.java) as? Map<String, Any?>
                parsed ?: throw IllegalArgumentException("YAML front matter must be a map/object")
            } catch (e: Exception) {
                log.error("Failed to parse YAML front matter: {}", e.message)
                throw e
            }
        } else {
            emptyMap()
        }

        return WorkflowDefinition(config = config, promptTemplate = promptBody)
    }

    fun buildConfig(definition: WorkflowDefinition): WorkflowConfig {
        val cfg = definition.config
        val tracker = cfg.getMap("tracker")
        val polling = cfg.getMap("polling")
        val workspace = cfg.getMap("workspace")
        val hooks = cfg.getMap("hooks")
        val agent = cfg.getMap("agent")
        val agentCfg = cfg.getMap("agent")
        val observability = cfg.getMap("observability")
        val server = cfg.getMap("server")

        val kind = tracker.getString("kind") ?: "linear"
        val isGitea = kind.equals("gitea", ignoreCase = true)

        val defaultEndpoint = if (isGitea) "http://gitea:3000" else "https://api.linear.app/graphql"
        val defaultApiKeyEnv = if (isGitea) "\$GITEA_TOKEN" else "\$LINEAR_API_KEY"
        val defaultActiveStates = if (isGitea) listOf("open", "todo", "in-progress", "review") else listOf("Todo", "In Progress")
        val defaultTerminalStates = if (isGitea) listOf("done", "closed") else listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done")

        val phases = parsePhases(cfg)

        return WorkflowConfig(
            trackerKind = kind,
            trackerEndpoint = tracker.getString("endpoint")?.let { resolveEnv(it) } ?: defaultEndpoint,
            trackerApiKey = resolveEnv(tracker.getString("api_key") ?: defaultApiKeyEnv),
            trackerProjectSlug = tracker.getString("project_slug")?.let { resolveEnv(it) },
            trackerAssignee = tracker.getString("assignee")?.let { resolveEnv(it) },
            activeStates = tracker.getStringList("active_states") ?: defaultActiveStates,
            terminalStates = tracker.getStringList("terminal_states") ?: defaultTerminalStates,
            pollingIntervalMs = polling.getLong("interval_ms") ?: 30_000,
            workspaceRoot = workspace.getString("root")?.let { expandPath(resolveEnv(it)) },
            hookAfterCreate = hooks.getString("after_create"),
            hookBeforeRun = hooks.getString("before_run"),
            hookAfterRun = hooks.getString("after_run"),
            hookBeforeRemove = hooks.getString("before_remove"),
            hookTimeoutMs = hooks.getLong("timeout_ms") ?: 60_000,
            maxConcurrentAgents = agentCfg.getInt("max_concurrent_agents") ?: 10,
            maxTurns = agentCfg.getInt("max_turns") ?: 20,
            maxRetryBackoffMs = agentCfg.getLong("max_retry_backoff_ms") ?: 300_000,
            maxConcurrentAgentsByState = agentCfg.getIntMap("max_concurrent_agents_by_state") ?: emptyMap(),
            agentCommand = agentCfg.getString("command")
                ?: "claude -p --verbose --output-format stream-json --dangerously-skip-permissions",
            agentTurnTimeoutMs = agentCfg.getLong("turn_timeout_ms") ?: 3_600_000,
            agentStallTimeoutMs = agentCfg.getLong("stall_timeout_ms") ?: 300_000,
            phases = phases,
            dashboardEnabled = observability.getBoolean("dashboard_enabled") ?: true,
            dashboardRefreshMs = observability.getInt("refresh_ms") ?: 1_000,
            dashboardRenderIntervalMs = observability.getInt("render_interval_ms") ?: 16,
            serverPort = server.getInt("port"),
            serverHost = server.getString("host") ?: "127.0.0.1",
            promptTemplate = definition.promptTemplate
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePhases(cfg: Map<String, Any?>): List<PhaseConfig> {
        val phasesMap = cfg["phases"] as? Map<String, Any?> ?: return emptyList()
        return phasesMap.mapNotNull { (name, value) ->
            val phaseMap = value as? Map<String, Any?> ?: return@mapNotNull null
            PhaseConfig(
                name = name,
                triggerStates = phaseMap.getStringList("trigger_states") ?: emptyList(),
                command = phaseMap.getString("command"),
                promptTemplate = phaseMap.getString("prompt_template"),
                createsPr = phaseMap.getBoolean("creates_pr") ?: false,
                needsPrDiff = phaseMap.getBoolean("needs_pr_diff") ?: false,
                verdictBased = phaseMap.getBoolean("verdict_based") ?: false,
                onSuccess = phaseMap.getString("on_success") ?: "done",
                onFailure = phaseMap.getString("on_failure"),
                onApproved = phaseMap.getString("on_approved") ?: "done",
                onChangesRequested = phaseMap.getString("on_changes_requested") ?: "todo",
                labelOnStart = phaseMap.getString("label_on_start")
            )
        }
    }

    private fun resolveEnv(value: String): String {
        if (!value.startsWith("$")) return value
        val varName = value.substring(1)
        if (!varName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return value
        return System.getenv(varName) ?: ""
    }

    private fun expandPath(path: String): String {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1)
        }
        return path
    }
}

// Extension helpers for safe map access
@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>?.getMap(key: String): Map<String, Any?> =
    (this?.get(key) as? Map<String, Any?>) ?: emptyMap()

private fun Map<String, Any?>.getString(key: String): String? =
    this[key]?.toString()

private fun Map<String, Any?>.getInt(key: String): Int? =
    when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

private fun Map<String, Any?>.getLong(key: String): Long? =
    when (val v = this[key]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

private fun Map<String, Any?>.getBoolean(key: String): Boolean? =
    when (val v = this[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.getStringList(key: String): List<String>? =
    when (val v = this[key]) {
        is List<*> -> v.map { it.toString() }
        is String -> v.split(",").map { it.trim() }
        else -> null
    }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.getIntMap(key: String): Map<String, Int>? =
    (this[key] as? Map<String, Any?>)?.mapValues { (_, v) ->
        when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            else -> 0
        }
    }
