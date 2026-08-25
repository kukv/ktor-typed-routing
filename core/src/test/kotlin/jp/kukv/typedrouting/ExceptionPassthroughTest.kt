package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class ExceptionPassthroughTest {
    @Serializable
    data class Req(@Query val count: Int)

    private class DomainFailure : Exception("domain failure")

    private fun io.ktor.server.testing.ApplicationTestBuilder.withStatusPages(
        configure: io.ktor.server.routing.Route.() -> Unit,
    ) = application {
        install(TypedRouting)
        install(StatusPages) {
            exception<RequestBindingException> { call, cause ->
                call.respondText("binding:" + cause.violations.joinToString { it.path }, status = HttpStatusCode.BadRequest)
            }
            exception<ValidationException> { call, cause ->
                call.respondText("validation:" + cause.violations.joinToString { it.path }, status = HttpStatusCode.UnprocessableEntity)
            }
            exception<DomainFailure> { call, _ ->
                call.respondText("domain", status = HttpStatusCode.InternalServerError)
            }
        }
        routing(configure)
    }

    @Test
    fun `binding failures reach StatusPages`() = testApplication {
        withStatusPages {
            get<Req, String>("/x") { handle { "ok" } }
        }

        val response = client.get("/x?count=abc")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("binding:count", response.bodyAsText())
    }

    @Test
    fun `missing required parameters reach StatusPages`() = testApplication {
        withStatusPages {
            get<Req, String>("/x") { handle { "ok" } }
        }

        val response = client.get("/x")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("binding:count", response.bodyAsText())
    }

    @Test
    fun `validation failures reach StatusPages`() = testApplication {
        withStatusPages {
            get<Req, String>("/x") {
                validate { if (it.count < 1) reject("count", "must be >= 1") }
                handle { "ok" }
            }
        }

        val response = client.get("/x?count=0")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("validation:count", response.bodyAsText())
    }

    @Test
    fun `handler exceptions reach StatusPages`() = testApplication {
        withStatusPages {
            get<Req, String>("/x") { handle { throw DomainFailure() } }
        }

        val response = client.get("/x?count=1")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("domain", response.bodyAsText())
    }

    @Test
    fun `an around that rethrows does not block StatusPages`() = testApplication {
        val seen = mutableListOf<String>()
        application {
            install(TypedRouting) {
                around { _, proceed ->
                    try {
                        proceed()
                    } catch (cause: Throwable) {
                        seen += cause::class.simpleName.orEmpty()
                        throw cause
                    }
                }
            }
            install(StatusPages) {
                exception<DomainFailure> { call, _ ->
                    call.respondText("domain", status = HttpStatusCode.InternalServerError)
                }
            }
            routing {
                get<Req, String>("/x") { handle { throw DomainFailure() } }
            }
        }

        val response = client.get("/x?count=1")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(listOf("DomainFailure"), seen)
    }
}
