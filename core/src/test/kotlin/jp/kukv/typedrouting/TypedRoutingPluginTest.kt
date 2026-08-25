package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypedRoutingPluginTest {
    @Test
    fun `plugin exposes its resolved configuration`() = testApplication {
        application {
            install(TypedRouting) {
                json = Json { ignoreUnknownKeys = true }
                around { _, proceed -> proceed() }
            }
            routing {
                get("/config") {
                    val resolved = call.application.typedRoutingConfig()
                    call.respondText("${resolved.around.size}")
                }
            }
        }

        assertEquals("1", client.get("/config").bodyAsText())
    }

    @Test
    fun `missing plugin is reported clearly`() = testApplication {
        application {
            routing {
                get("/config") {
                    val message = assertFailsWith<IllegalStateException> {
                        call.application.typedRoutingConfig()
                    }.message
                    call.respondText(message.orEmpty())
                }
            }
        }

        val body = client.get("/config").bodyAsText()
        assertEquals(true, body.contains("install(TypedRouting)"))
    }
}
