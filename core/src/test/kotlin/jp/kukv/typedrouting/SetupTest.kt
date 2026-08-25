package jp.kukv.typedrouting

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupTest {
    @Test
    fun `standard routing works in the test harness`() = testApplication {
        application {
            routing {
                get("/ping") { call.respondText("pong") }
            }
        }

        val response = client.get("/ping")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("pong", response.bodyAsText())
    }
}
