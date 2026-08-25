package jp.kukv.typedrouting.openapi

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
import jp.kukv.typedrouting.TypedRouting
import jp.kukv.typedrouting.get
import jp.kukv.typedrouting.post
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenApiBridgeTest {
    @Serializable
    data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    data class SearchReq(@Path val orgId: Long, @Query val paging: Paging)

    @Serializable
    data class NewUser(val name: String)

    @Serializable
    data class CreateReq(@Path val orgId: Long, @Body val user: NewUser)

    @Serializable
    data class ErrorBody(val message: String)

    @Serializable
    data class User(val id: Long, val name: String)

    @Test
    fun `typed endpoints appear in the generated document`() = testApplication {
        lateinit var app: Application
        application {
            app = this
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    get<SearchReq, List<User>> {
                        summary = "Search users"
                        handle { listOf(User(1, "alice")) }
                    }
                    post<CreateReq, User> {
                        summary = "Create a user"
                        status = HttpStatusCode.Created
                        error<ErrorBody>(HttpStatusCode.Conflict)
                        handle { req -> User(req.orgId, req.user.name) }
                    }
                }
                describeTypedEndpoints()
            }
        }
        startApplication()

        val document = OpenApiDocSource.Routing().read(app, OpenApiDoc(info = OpenApiInfo(title = "test", version = "1.0.0"))).content
        println(document)

        assertTrue(document.contains("/orgs/{orgId}/users"), "path is present: $document")
        assertTrue(document.contains("Search users"), "summary is present: $document")
        assertTrue(
            document.contains("\"name\":\"orgId\",\"in\":\"path\",\"required\":true"),
            "path parameter is always required: $document",
        )
        assertTrue(document.contains("\"page\""), "flattened group parameter is present: $document")
        assertTrue(document.contains("\"limit\""), "flattened group parameter is present: $document")
        assertTrue(document.contains("NewUser"), "request body schema is present: $document")
        assertTrue(document.contains("201"), "configured success status is present: $document")
        assertTrue(document.contains("409"), "declared error status is present: $document")
    }
}
