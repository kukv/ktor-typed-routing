package jp.kukv.typedrouting.openapi

import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as ktorGet
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import jp.kukv.typedrouting.TypedRouting
import jp.kukv.typedrouting.get
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * README の「OpenAPI」節にある `Route.hide()` の言及を裏付けるテスト。
 *
 * 公式のジェネレータはルートツリー全体を列挙するため、`describeTypedEndpoints()` を
 * 呼んでも素の Ktor ルートは空の operation として文書に残る（[OpenApiBridgeTest] で確認済み）。
 * `hide()` を付けると、公式ジェネレータ自体がそのルートを対象から外す。
 */
class ReadmeOpenApiExampleTest {

    @Test
    fun `Route hide() removes a plain ktor route from the generated document`() = testApplication {
        lateinit var app: Application
        application {
            app = this
            install(TypedRouting)
            routing {
                route("/orgs/{orgId}/users") {
                    get<Unit, String> { handle { "ok" } }
                }
                ktorGet("/health") { call.respondText("ok") }.hide()
                describeTypedEndpoints()
            }
        }
        startApplication()

        val document = OpenApiDocSource.Routing()
            .read(app, OpenApiDoc(info = OpenApiInfo(title = "test", version = "1.0.0")))
            .content
        println(document)

        assertFalse(document.contains("\"/health\""), "hidden route is absent from the document: $document")
        assertTrue(document.contains("\"/orgs/{orgId}/users\""), "typed endpoint is still present: $document")
    }
}
