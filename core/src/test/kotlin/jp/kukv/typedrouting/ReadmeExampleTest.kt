package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * README に載せるサンプルの動作確認。
 *
 * README にコピーするコードはここに書いたものと一致させる。ここで通らないコードは
 * README にも載せない。
 */
class ReadmeExampleTest {

    // --- 3. 最小の例 -------------------------------------------------

    @Serializable
    data class Greeting(val message: String)

    @Test
    fun `minimal example - install and one get`() = testApplication {
        application {
            install(TypedRouting)
            routing {
                get<Unit, Greeting>("/hello") {
                    handle { Greeting("hello") }
                }
            }
        }

        val response = client.get("/hello")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"message":"hello"}""", response.bodyAsText())
    }

    // --- 4. リクエストの宣言 -------------------------------------------

    @Serializable
    data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    data class ListItemsReq(
        @Path val orgId: Long,
        @Query val paging: Paging,
        @Query val tag: String?,
        @Header val requestId: String,
        @Cookie val sessionId: String?,
    )

    @Serializable
    data class ItemsResult(
        val orgId: Long,
        val page: Int,
        val limit: Int,
        val tag: String?,
        val requestId: String,
        val sessionId: String?,
    )

    @Serializable
    data class NewItem(val name: String)

    @Serializable
    data class CreateItemReq(
        @Path val orgId: Long,
        @Body val item: NewItem,
    )

    @Serializable
    data class Item(val id: Long, val name: String)

    private fun io.ktor.server.testing.ApplicationTestBuilder.installListItemsRoute(
        extraConfig: io.ktor.server.application.Application.() -> Unit = {},
    ) {
        application {
            install(TypedRouting)
            extraConfig()
            routing {
                route("/orgs/{orgId}/items") {
                    get<ListItemsReq, ItemsResult> {
                        handle { req ->
                            ItemsResult(
                                orgId = req.orgId,
                                page = req.paging.page,
                                limit = req.paging.limit,
                                tag = req.tag,
                                requestId = req.requestId,
                                sessionId = req.sessionId,
                            )
                        }
                    }
                    post<CreateItemReq, Item> {
                        handle { req -> Item(req.orgId, req.item.name) }
                    }
                }
            }
        }
    }

    @Test
    fun `request declaration - path query header cookie all bind from their own source`() = testApplication {
        installListItemsRoute()

        val response = client.get("/orgs/7/items?page=2&limit=50&tag=urgent") {
            header("requestId", "req-1")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"orgId":7,"page":2,"limit":50,"tag":"urgent","requestId":"req-1","sessionId":null}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `request declaration - missing values follow the default-nullable-required rules`() = testApplication {
        installListItemsRoute()

        // paging は丸ごと省略 -> グループの既定値で埋まる。tag は nullable -> null。cookie も未送信 -> null。
        val response = client.get("/orgs/7/items") {
            header("requestId", "req-1")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            """{"orgId":7,"page":1,"limit":20,"tag":null,"requestId":"req-1","sessionId":null}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `request declaration - a required header missing entirely is a binding failure`() = testApplication {
        installListItemsRoute {
            install(StatusPages) {
                exception<RequestBindingException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, cause.violations.joinToString { it.path })
                }
            }
        }

        val response = client.get("/orgs/7/items")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("requestId", response.bodyAsText())
    }

    @Test
    fun `request declaration - Body follows kotlinx serialization's normal rules`() = testApplication {
        installListItemsRoute()

        val response = client.post("/orgs/7/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"widget"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"id":7,"name":"widget"}""", response.bodyAsText())
    }

    // --- 5. バリデーション ---------------------------------------------

    @Serializable
    data class SearchReq(@Query val q: String, @Query val paging: Paging)

    @Test
    fun `validation - reject accumulates violations and StatusPages maps them`() = testApplication {
        application {
            install(TypedRouting)
            install(StatusPages) {
                exception<ValidationException> { call, cause ->
                    call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        cause.violations.joinToString { "${it.path}:${it.message}" },
                    )
                }
            }
            routing {
                get<SearchReq, String>("/search") {
                    validate { req ->
                        if (req.q.isBlank()) reject("q", "must not be blank")
                        if (req.paging.limit !in 1..100) reject("limit", "must be 1..100")
                    }
                    handle { req -> req.q }
                }
            }
        }

        val response = client.get("/search?q=&limit=999")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("q:must not be blank, limit:must be 1..100", response.bodyAsText())
    }

    // --- 6. エラー処理 ---------------------------------------------------

    @Serializable
    data class ErrorBody(val message: String, val violations: List<Violation> = emptyList())

    private class UserNotFound : Exception("user not found")

    @Test
    fun `error handling - StatusPages decides every status code, this library decides none`() = testApplication {
        application {
            install(TypedRouting)
            // call.respond(status, ErrorBody(...)) はエラーボディをシリアライズするために
            // ContentNegotiation を必要とする。型付きエンドポイント自身は経由しない（後述）が、
            // StatusPages 側の respond は経由する通常の Ktor の挙動である。
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<RequestBindingException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("invalid request", cause.violations))
                }
                exception<ValidationException> { call, cause ->
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody("validation failed", cause.violations))
                }
                exception<UserNotFound> { call, _ ->
                    call.respond(HttpStatusCode.NotFound, ErrorBody("not found"))
                }
                exception<Throwable> { call, _ ->
                    call.respond(HttpStatusCode.InternalServerError, ErrorBody("internal error"))
                }
            }
            routing {
                get<SearchReq, String>("/users") {
                    handle { throw UserNotFound() }
                }
            }
        }

        // 違反の構造（path / message）がそのまま利用者のエラーボディに届くことを確認する。
        val bindingFailure = client.get("/users?limit=20")
        assertEquals(HttpStatusCode.BadRequest, bindingFailure.status)
        assertEquals(
            """{"message":"invalid request","violations":[{"path":"q","message":"is required"}]}""",
            bindingFailure.bodyAsText(),
        )

        val response = client.get("/users?q=x&page=1&limit=20")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"message":"not found","violations":[]}""", response.bodyAsText())
    }

    // --- 7. around -------------------------------------------------------

    @Test
    fun `around observes the typed success body and must rethrow to reach StatusPages`() = testApplication {
        val logged = mutableListOf<String>()
        application {
            install(TypedRouting) {
                around { ctx, proceed ->
                    try {
                        val res = proceed()
                        logged += "success: $res"
                        res
                    } catch (cause: Throwable) {
                        logged += "failed: ${cause::class.simpleName}"
                        throw cause // 再スローしないと StatusPages に届かず、応答が返らなくなる。
                    }
                }
            }
            install(StatusPages) {
                exception<UserNotFound> { call, _ -> call.respond(HttpStatusCode.NotFound) }
            }
            routing {
                get<Unit, Greeting>("/hello") { handle { Greeting("hello") } }
                get<Unit, Unit>("/boom") { handle { throw UserNotFound() } }
            }
        }

        val ok = client.get("/hello")
        assertEquals(HttpStatusCode.OK, ok.status)

        val failed = client.get("/boom")
        assertEquals(HttpStatusCode.NotFound, failed.status)

        assertEquals(listOf("success: Greeting(message=hello)", "failed: UserNotFound"), logged)
    }
}
