package rockopera.config

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class WorkflowStore(
    private val workflowPath: Path,
    private val scope: CoroutineScope
) {
    private val log = LoggerFactory.getLogger(WorkflowStore::class.java)

    @Volatile
    private var currentConfig: WorkflowConfig

    @Volatile
    private var currentDefinition: WorkflowDefinition

    private var lastMtime: Long = 0
    private var lastSize: Long = 0
    private var lastHash: ByteArray = ByteArray(0)
    private var watchJob: Job? = null

    init {
        val definition = WorkflowLoader.load(workflowPath)
        currentDefinition = definition
        currentConfig = WorkflowLoader.buildConfig(definition)
        updateFileStats()
        log.info("Loaded workflow from {}", workflowPath)
    }

    fun current(): WorkflowConfig = currentConfig

    fun currentDefinition(): WorkflowDefinition = currentDefinition

    fun startWatching(): Job {
        watchJob = scope.launch {
            while (isActive) {
                delay(1_000)
                checkForChanges()
            }
        }
        return watchJob!!
    }

    private fun checkForChanges() {
        try {
            val file = workflowPath.toFile()
            if (!file.exists()) return

            val mtime = file.lastModified()
            val size = file.length()

            if (mtime == lastMtime && size == lastSize) return

            val content = file.readText()
            val hash = sha256(content)

            if (hash.contentEquals(lastHash)) {
                lastMtime = mtime
                lastSize = size
                return
            }

            log.info("WORKFLOW.md changed, reloading")
            val definition = WorkflowLoader.parse(content)
            val config = WorkflowLoader.buildConfig(definition)

            currentDefinition = definition
            currentConfig = config
            lastMtime = mtime
            lastSize = size
            lastHash = hash

            log.info("Workflow reloaded successfully")
        } catch (e: Exception) {
            log.error("Failed to reload workflow, keeping last known good config: {}", e.message)
        }
    }

    private fun updateFileStats() {
        val file = workflowPath.toFile()
        if (file.exists()) {
            lastMtime = file.lastModified()
            lastSize = file.length()
            lastHash = sha256(file.readText())
        }
    }

    private fun sha256(content: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray())
    }
}
