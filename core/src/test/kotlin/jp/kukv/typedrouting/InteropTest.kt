package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

class InteropTest {
    @Serializable
    data class Req(@Path val id: Long)

    @Serializable
    data class StandardBody(val value: String)

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `typed endpoints work inside authenticate`() = testApplication {
        application {
            install(TypedRouting)
            install(Authentication) {
                basic("test") {
                    validate { credentials ->
                        if (credentials.name == "user") UserIdPrincipal(credentials.name) else null
                    }
                }
            }
            routing {
                authenticate("test") {
                    route("/items/{id}") {
                        get<Req, String> { handle { req -> "item ${req.id}" } }
                    }
                }
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/items/1").status)

        val credentials = Base64.encode("user:pass".toByteArray())
        val response = client.get("/items/1") { header("Authorization", "Basic $credentials") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("\"item 1\"", response.bodyAsText())
    }

    @Test
    fun `route scoped ContentNegotiation can be installed alongside`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/items/{id}") {
                    install(ContentNegotiation) { json() }
                    get<Req, String> { handle { req -> "item ${req.id}" } }
                    get("/standard") { call.respond(StandardBody("standard")) }
                }
            }
        }

        val typed = client.get("/items/1")
        assertEquals(HttpStatusCode.OK, typed.status)
        assertEquals("\"item 1\"", typed.bodyAsText())

        // 標準の respond(obj) は ContentNegotiation がないと 406 Not Acceptable になる
        // （PROBE で確認済み）。install があるからこそ、こちらも正しく JSON で応答する。
        val standard = client.get("/items/1/standard")
        assertEquals(HttpStatusCode.OK, standard.status)
        assertEquals("""{"value":"standard"}""", standard.bodyAsText())
    }
}
