package jp.kukv.typedrouting.openapi

import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import jp.kukv.typedrouting.TypedRouting
import jp.kukv.typedrouting.get
import kotlin.test.Test
import kotlin.test.assertTrue

class TypedRoutingOpenApiTest {

    @Test
    fun `describes endpoints defined after the plugin was installed`() = testApplication {
        lateinit var app: Application
        application {
            app = this
            install(TypedRouting)
            // エンドポイントより前に install しても、走査は起動完了後なので間に合う。
            install(TypedRoutingOpenApi)
            routing {
                route("/orgs/{orgId}/users") {
                    get<Unit, String> {
                        summary = "Search users"
                        handle { "ok" }
                    }
                }
            }
        }
        startApplication()

        val document = OpenApiDocSource.Routing()
            .read(app, OpenApiDoc(info = OpenApiInfo(title = "test", version = "1.0.0")))
            .content

        assertTrue(document.contains("Search users"), document)
    }

    @Test
    fun `does nothing when the application has no routing`() = testApplication {
        application {
            install(TypedRouting)
            install(TypedRoutingOpenApi)
        }

        startApplication()
    }
}
