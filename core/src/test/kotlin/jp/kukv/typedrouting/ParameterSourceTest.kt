package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parametersOf
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParameterSourceTest {
    @Test
    fun `parameters source returns all values and null when absent`() {
        val source = ParameterSource.of(parametersOf("tags", listOf("a", "b")))

        assertEquals(listOf("a", "b"), source.getAll("tags"))
        assertNull(source.getAll("missing"))
    }

    @Test
    fun `parameters source distinguishes empty string from absent`() {
        val source = ParameterSource.of(parametersOf("tags", listOf("")))

        assertEquals(listOf(""), source.getAll("tags"))
        assertNull(source.getAll("missing"))
    }

    @Test
    fun `parameters source handles multiple values including empty`() {
        val source = ParameterSource.of(parametersOf("tags", listOf("a", "", "b")))

        assertEquals(listOf("a", "", "b"), source.getAll("tags"))
    }

    @Test
    fun `request sources read path query header and cookie`() = testApplication {
        application {
            routing {
                route("/orgs/{orgId}") {
                    get {
                        val sources = call.requestSources()
                        call.respondText(
                            buildString {
                                append(sources.path.getAll("orgId"))
                                append("|").append(sources.query.getAll("q"))
                                append("|").append(sources.header.getAll("X-Trace-Id"))
                                append("|").append(sources.cookie.getAll("session"))
                            },
                        )
                    }
                }
            }
        }

        val response = client.get("/orgs/42?q=hello") {
            header("X-Trace-Id", "t-1")
            header("Cookie", "session=abc")
        }
        assertEquals("[42]|[hello]|[t-1]|[abc]", response.bodyAsText())
    }

    @Test
    fun `request sources distinguish empty query value from absent`() = testApplication {
        application {
            routing {
                route("/test") {
                    get {
                        val sources = call.requestSources()
                        call.respondText(
                            buildString {
                                append("present=").append(sources.query.getAll("present"))
                                append("|absent=").append(sources.query.getAll("absent"))
                            },
                        )
                    }
                }
            }
        }

        val response = client.get("/test?present=")
        val result = response.bodyAsText()
        assertEquals("present=[]|absent=null", result)
    }

    @Test
    fun `request sources distinguish empty header value from absent`() = testApplication {
        application {
            routing {
                route("/test") {
                    get {
                        val sources = call.requestSources()
                        call.respondText(
                            buildString {
                                append("present=").append(sources.header.getAll("X-Present"))
                                append("|absent=").append(sources.header.getAll("X-Absent"))
                            },
                        )
                    }
                }
            }
        }

        val response = client.get("/test") {
            header("X-Present", "")
        }
        val result = response.bodyAsText()
        assertEquals("present=[]|absent=null", result)
    }
}
