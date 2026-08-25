package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class EndpointDslTest {
    @Serializable
    data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    data class SearchReq(
        @Path val orgId: Long,
        @Query val paging: Paging,
    )

    @Serializable
    data class NewUser(val name: String)

    @Serializable
    data class CreateReq(
        @Path val orgId: Long,
        @Body val user: NewUser,
    )

    @Serializable
    data class User(val id: Long, val name: String)

    @Test
    fun `get binds path and grouped query and returns the body`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    get<SearchReq, List<User>> {
                        handle { req ->
                            listOf(User(req.orgId, "page=${req.paging.page} limit=${req.paging.limit}"))
                        }
                    }
                }
            }
        }

        val response = client.get("/orgs/7/users?page=2&limit=50")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""[{"id":7,"name":"page=2 limit=50"}]""", response.bodyAsText())
    }

    @Test
    fun `post reads the body and honours the configured status`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    post<CreateReq, User> {
                        status = HttpStatusCode.Created
                        handle { req -> User(req.orgId, req.user.name) }
                    }
                }
            }
        }

        val response = client.post("/orgs/7/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("""{"id":7,"name":"alice"}""", response.bodyAsText())
    }

    @Test
    fun `Unit response defaults to 204 and Unit request needs no input`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                get<Unit, Unit>("/ping") { handle { } }
            }
        }

        val response = client.get("/ping")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `path argument nests under the current route`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    get<SearchReq, String>("/summary") { handle { req -> "org ${req.orgId}" } }
                }
            }
        }

        assertEquals("\"org 7\"", client.get("/orgs/7/users/summary").bodyAsText())
    }

    @Test
    fun `standard routing DSL still resolves alongside the typed one`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                get("/standard") {
                    call.respondText("standard")
                }
                get<Unit, String>("/typed") { handle { "typed" } }
            }
        }

        assertEquals("standard", client.get("/standard").bodyAsText())
        assertEquals("\"typed\"", client.get("/typed").bodyAsText())
    }
}
