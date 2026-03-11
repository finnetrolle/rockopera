package rockopera.observability

import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory

object LogFileConfig {

    fun configureLogDir(logsRoot: String) {
        System.setProperty("ROCKOPERA_LOG_DIR", logsRoot)

        // Trigger logback re-initialization
        val context = LoggerFactory.getILoggerFactory()
        if (context is LoggerContext) {
            // The logback.xml already uses ${ROCKOPERA_LOG_DIR:-log}
            // Setting the system property before first log access is sufficient.
            // If already initialized, we trigger a reset + reload.
            context.reset()
            val configurator = ch.qos.logback.classic.joran.JoranConfigurator()
            configurator.context = context
            val configUrl = LogFileConfig::class.java.classLoader.getResource("logback.xml")
            if (configUrl != null) {
                configurator.doConfigure(configUrl)
            }
        }
    }
}
