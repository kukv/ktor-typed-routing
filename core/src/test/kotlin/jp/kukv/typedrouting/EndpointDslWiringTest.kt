package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * DSL とその周辺（around / validate / EndpointSpec）の配線を固定するテスト。
 *
 * ここが落ちないと、配線を壊しても振る舞いのテストだけは通ってしまう。
 */
class EndpointDslWiringTest {
    @Serializable
    data class NameReq(@Query val name: String)

    @Test
    fun `application-wide around wraps the endpoint-level one`() = testApplication {
        val trace = mutableListOf<String>()

        application {
            install(TypedRouting) {
                around { _, proceed ->
                    trace += "app-in"
                    try {
                        proceed()
                    } finally {
                        trace += "app-out"
                    }
                }
            }
            routing {
                get<Unit, String>("/traced") {
                    around { _, proceed ->
                        trace += "endpoint-in"
                        try {
                            proceed()
                        } finally {
                            trace += "endpoint-out"
                        }
                    }
                    handle {
                        trace += "handler"
                        "ok"
                    }
                }
            }
        }

        client.get("/traced")

        assertEquals(
            listOf("app-in", "endpoint-in", "handler", "endpoint-out", "app-out"),
            trace,
        )
    }

    @Test
    fun `validate is wired into the request path and its exception propagates`() = testApplication {
        // テストホストは未処理例外を 500 に変換してしまうため、素通しされてくる例外そのものを
        // around で捕まえて記録し、そのまま再スローして確認する。
        var escaped: Throwable? = null
        var handlerRan = false

        application {
            install(TypedRouting)
            routing {
                get<NameReq, String>("/validated") {
                    around { _, proceed ->
                        try {
                            proceed()
                        } catch (cause: Throwable) {
                            escaped = cause
                            throw cause
                        }
                    }
                    validate { req ->
                        if (req.name.isEmpty()) reject("name", "must not be empty")
                    }
                    handle { req ->
                        handlerRan = true
                        req.name
                    }
                }
            }
        }

        assertEquals("\"alice\"", client.get("/validated?name=alice").bodyAsText())
        assertEquals(null, escaped)

        handlerRan = false
        assertEquals(HttpStatusCode.InternalServerError, client.get("/validated?name=").status)

        val failure = assertIs<ValidationException>(escaped)
        assertEquals(listOf(Violation("name", "must not be empty")), failure.violations)
        assertFalse(handlerRan, "the handler must not run when validation rejects")
    }

    @Test
    fun `EndpointSpecKey is attached to the generated route`() = testApplication {
        var endpoint: Route? = null

        application {
            install(TypedRouting)
            routing {
                route("/orgs") {
                    endpoint = post<NameReq, String>("/members") {
                        summary = "add a member"
                        status = HttpStatusCode.Created
                        handle { req -> req.name }
                    }
                }
            }
        }

        startApplication()

        val spec = endpoint!!.attributes[EndpointSpecKey]
        assertEquals(HttpMethod.Post, spec.method)
        assertEquals(HttpStatusCode.Created, spec.status)
        assertEquals("add a member", spec.summary)
    }

    @Test
    fun `a missing handle fails at route declaration time and names the endpoint`() {
        val failure = assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    install(TypedRouting)
                    routing {
                        route("/orgs/{orgId}/users") {
                            get<Unit, String> { }
                        }
                    }
                }
                startApplication()
            }
        }

        val message = failure.message.orEmpty()
        assertTrue("GET" in message, "message should name the method, was: $message")
        assertTrue(
            "/orgs/{orgId}/users" in message,
            "message should name the endpoint even without an explicit path, was: $message",
        )
    }
}
