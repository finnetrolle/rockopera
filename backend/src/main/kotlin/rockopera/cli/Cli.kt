package rockopera.cli

import java.nio.file.Path

data class CliArgs(
    val workflowPath: Path,
    val logsRoot: String?,
    val port: Int?,
    val guardrailsAcknowledged: Boolean
)

object Cli {
    private const val GUARDRAILS_FLAG = "--i-understand-that-this-will-be-running-without-the-usual-guardrails"

    private val SAFETY_BANNER = """
        |╭──────────────────────────────────────────────────────────────────────────────────────────────────╮
        |│                                                                                                  │
        |│ This RockOpera implementation is a low key engineering preview.                                   │
        |│ The coding agent will run without any guardrails.                                                  │
        |│ RockOpera is not a supported product and is presented as-is.                                     │
        |│ To proceed, start with `$GUARDRAILS_FLAG`   │
        |│                                                                                                  │
        |╰──────────────────────────────────────────────────────────────────────────────────────────────────╯
    """.trimMargin()

    fun parse(args: Array<String>): CliArgs {
        var workflowPath: Path? = null
        var logsRoot: String? = null
        var port: Int? = null
        var guardrails = false

        val iter = args.iterator()
        while (iter.hasNext()) {
            when (val arg = iter.next()) {
                "--logs-root" -> logsRoot = if (iter.hasNext()) iter.next() else error("--logs-root requires a value")
                "--port" -> port = if (iter.hasNext()) iter.next().toIntOrNull()
                    ?: error("--port requires an integer") else error("--port requires a value")
                GUARDRAILS_FLAG -> guardrails = true
                else -> {
                    if (!arg.startsWith("--")) {
                        workflowPath = Path.of(arg).toAbsolutePath()
                    }
                }
            }
        }

        if (workflowPath == null) {
            workflowPath = Path.of("WORKFLOW.md").toAbsolutePath()
        }

        return CliArgs(
            workflowPath = workflowPath,
            logsRoot = logsRoot,
            port = port,
            guardrailsAcknowledged = guardrails
        )
    }

    fun printSafetyBanner() {
        println(SAFETY_BANNER)
    }
}
