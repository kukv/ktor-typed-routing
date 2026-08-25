package jp.kukv.typedrouting.openapi

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as ktorGet
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
import jp.kukv.typedrouting.TypedRouting
import jp.kukv.typedrouting.delete
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
    data class DeleteReq(@Path val orgId: Long, @Path val userId: Long)

    @Serializable
    data class ErrorBody(val message: String)

    @Serializable
    data class User(val id: Long, val name: String)

    /**
     * ルートツリーを組み立ててから公式のジェネレータでドキュメントを起こす。
     *
     * `ktor-server-routing-openapi` 3.5.2 にはドキュメント配信ルート（`openAPI(path)`）が
     * 含まれないため、`OpenApiDocSource.Routing` を直接呼んでいる。
     */
    private fun generateDocument(configure: io.ktor.server.routing.Route.() -> Unit): String {
        var document = ""
        testApplication {
            lateinit var app: Application
            application {
                app = this
                install(TypedRouting)
                routing { configure() }
            }
            startApplication()
            document = OpenApiDocSource.Routing()
                .read(app, OpenApiDoc(info = OpenApiInfo(title = "test", version = "1.0.0")))
                .content
        }
        return document
    }

    @Test
    fun `typed endpoints appear in the generated document`() {
        val document = generateDocument {
            route("/orgs/{orgId}/users") {
                get<SearchReq, List<User>> {
                    summary = "Search users"
                    description = "Search the users of an organization."
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
        println(document)

        assertTrue(document.contains("/orgs/{orgId}/users"), "path is present: $document")
        assertTrue(document.contains("\"summary\":\"Search users\""), "summary is present: $document")
        assertTrue(
            document.contains("\"description\":\"Search the users of an organization.\""),
            "description is present: $document",
        )
        assertTrue(
            document.contains("\"name\":\"orgId\",\"in\":\"path\",\"required\":true"),
            "path parameter is always required: $document",
        )
        // 既定値を持つグループの要素は required が出ない（= 任意）。
        assertTrue(
            document.contains("\"name\":\"page\",\"in\":\"query\""),
            "flattened group parameter is a query parameter: $document",
        )
        assertTrue(
            document.contains("\"name\":\"limit\",\"in\":\"query\""),
            "flattened group parameter is a query parameter: $document",
        )
        assertTrue(
            document.contains("\"requestBody\":{\"content\":{\"application/json\":" +
                "{\"schema\":{\"\$ref\":\"#/components/schemas/OpenApiBridgeTest.NewUser\"}}}," +
                "\"required\":true}"),
            "request body schema is present: $document",
        )
        assertTrue(
            document.contains("\"responses\":{\"201\":"),
            "configured success status is a key of responses: $document",
        )
        assertTrue(
            document.contains("\"409\":{\"description\":\"\",\"content\":{\"application/json\":" +
                "{\"schema\":{\"\$ref\":\"#/components/schemas/OpenApiBridgeTest.ErrorBody\"}}}}"),
            "declared error status is a key of responses with its schema: $document",
        )
    }

    @Test
    fun `an endpoint returning Unit still declares its success response`() {
        val document = generateDocument {
            route("/orgs/{orgId}/users/{userId}") {
                delete<DeleteReq, Unit> {
                    summary = "Delete a user"
                    handle { }
                }
            }
            describeTypedEndpoints()
        }
        println(document)

        // OAS 3.1 は Responses Object に最低 1 件を要求する。
        // Res = Unit（既定 204）でもスキーマ無しのレスポンスが出なければならない。
        assertTrue(
            document.contains("\"responses\":{\"204\":{\"description\":\"\"}}"),
            "the 204 response is declared without a schema: $document",
        )
    }

    @Test
    fun `plain ktor routes in the same tree are skipped silently`() {
        val document = generateDocument {
            route("/orgs/{orgId}/users") {
                get<SearchReq, List<User>> {
                    summary = "Search users"
                    handle { listOf(User(1, "alice")) }
                }
            }
            ktorGet("/health") { call.respondText("ok") }
            describeTypedEndpoints()
        }
        println(document)

        assertTrue(
            document.contains("\"summary\":\"Search users\""),
            "the typed endpoint is described: $document",
        )
        // 公式のジェネレータはルートツリー全体を列挙するため `/health` 自体は文書に現れる。
        // ブリッジが「黙って読み飛ばす」とは、例外を投げず、そこに何のメタデータも足さないこと。
        assertTrue(
            document.contains("\"/health\":{\"get\":{}}"),
            "the plain Ktor route gets no metadata from the bridge: $document",
        )
    }

    @Test
    fun `describeTypedEndpoints on the application covers the whole tree`() {
        var document = ""
        testApplication {
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
                    }
                }
                describeTypedEndpoints()
            }
            startApplication()
            document = OpenApiDocSource.Routing()
                .read(app, OpenApiDoc(info = OpenApiInfo(title = "test", version = "1.0.0")))
                .content
        }
        println(document)

        assertTrue(document.contains("\"summary\":\"Search users\""), "summary is present: $document")
        assertTrue(
            document.contains("\"name\":\"orgId\",\"in\":\"path\",\"required\":true"),
            "path parameter is present: $document",
        )
    }
}
