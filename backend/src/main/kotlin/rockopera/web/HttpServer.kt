package rockopera.web

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rockopera.orchestrator.Orchestrator

class HttpServer(
    private val host: String,
    private val port: Int,
    private val orchestrator: Orchestrator
) {
    private val log = LoggerFactory.getLogger(HttpServer::class.java)
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        server = embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
            }
            routing {
                route("/api/v1") {
                    observabilityApi(orchestrator)
                }
                staticResources("/", "static") {
                    default("index.html")
                }
            }
        }
        server!!.start(wait = false)
        log.info("HTTP server started on {}:{}", host, port)
    }

    fun stop() {
        server?.stop(1000, 5000)
        log.info("HTTP server stopped")
    }
}
