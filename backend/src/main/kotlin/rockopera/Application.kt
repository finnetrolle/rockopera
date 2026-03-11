package rockopera

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import rockopera.agent.AgentRunner
import rockopera.cli.Cli
import rockopera.config.WorkflowConfig
import rockopera.config.WorkflowStore
import rockopera.orchestrator.Orchestrator
import rockopera.orchestrator.OrchestratorMessage
import rockopera.tracker.*
import rockopera.observability.LogFileConfig
import rockopera.observability.StatusDashboard
import rockopera.web.HttpServer
import rockopera.workspace.WorkspaceManager

private val log = LoggerFactory.getLogger("rockopera.Application")

fun main(args: Array<String>) {
    val cliArgs = Cli.parse(args)

    if (!cliArgs.guardrailsAcknowledged) {
        Cli.printSafetyBanner()
        System.exit(1)
    }

    if (!cliArgs.workflowPath.toFile().exists()) {
        log.error("Workflow file not found: {}", cliArgs.workflowPath)
        System.exit(1)
    }

    // Configure log directory if specified
    cliArgs.logsRoot?.let { logsRoot ->
        LogFileConfig.configureLogDir(logsRoot)
    }

    log.info("Starting RockOpera")
    log.info("Workflow: {}", cliArgs.workflowPath)

    runBlocking {
        val supervisorJob = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + supervisorJob)

        // Start WorkflowStore
        val workflowStore = WorkflowStore(cliArgs.workflowPath, scope)
        workflowStore.startWatching()

        val initialConfig = workflowStore.current()
        val trackerKind = initialConfig.trackerKind.lowercase()
        log.info("Tracker kind: {}", trackerKind)

        // Build shared clients based on tracker kind
        var linearClient: LinearClient? = null
        var giteaClient: GiteaClient? = null

        when (trackerKind) {
            "linear" -> {
                if (!initialConfig.trackerApiKey.isNullOrBlank()) {
                    linearClient = LinearClient(initialConfig.trackerEndpoint, initialConfig.trackerApiKey)
                }
            }
            "gitea" -> {
                if (!initialConfig.trackerApiKey.isNullOrBlank()) {
                    giteaClient = GiteaClient(initialConfig.trackerEndpoint, initialConfig.trackerApiKey)
                }
            }
        }

        // Tracker provider (re-reads config each time for dynamic reload)
        val trackerProvider: () -> TrackerAdapter = {
            val config = workflowStore.current()
            when (config.trackerKind.lowercase()) {
                "gitea" -> GiteaAdapter(config, giteaClient
                    ?: GiteaClient(config.trackerEndpoint, config.trackerApiKey ?: ""))
                else -> LinearAdapter(config)
            }
        }

        // Agent runner provider — CLI-based agent with optional Gitea integration
        val agentRunnerProvider: (WorkflowConfig) -> AgentRunner = { config ->
            val wsManager = WorkspaceManager(config)
            AgentRunner(config, wsManager, giteaClient)
        }

        // Start Orchestrator
        val orchestrator = Orchestrator(workflowStore, trackerProvider, agentRunnerProvider, scope)
        orchestrator.start()

        // Start HTTP server if port is configured
        val port = cliArgs.port ?: workflowStore.current().serverPort
        var httpServer: HttpServer? = null
        if (port != null) {
            val host = workflowStore.current().serverHost
            httpServer = HttpServer(host, port, orchestrator)
            httpServer.start()
        }

        // Start terminal status dashboard if enabled
        val dashboardConfig = workflowStore.current()
        if (dashboardConfig.dashboardEnabled && port == null) {
            val dashboard = StatusDashboard(
                orchestrator = orchestrator,
                refreshMs = dashboardConfig.dashboardRefreshMs.toLong(),
                scope = scope
            )
            dashboard.start()
        }

        log.info("RockOpera is running")

        // Wait for shutdown signal
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Shutdown signal received")
            runBlocking {
                httpServer?.stop()
                orchestrator.inbox.send(OrchestratorMessage.Shutdown)
                delay(2000)
                supervisorJob.cancelAndJoin()
            }
            linearClient?.close()
            giteaClient?.close()
        })

        // Keep the main coroutine alive
        supervisorJob.join()
    }
}
