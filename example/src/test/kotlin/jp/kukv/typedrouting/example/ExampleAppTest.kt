package jp.kukv.typedrouting.example

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** サンプルアプリが README に書いたとおりに振る舞うことを確かめる。 */
class ExampleAppTest {

    private fun exampleApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { module() }
            block()
        }

    @Test
    fun `create returns 201 with the created user`() = exampleApp {
        val response = client.post("/orgs/1/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice","email":"alice@example.com","tags":["admin"]}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(
            """{"id":1,"orgId":1,"name":"alice","email":"alice@example.com","tags":["admin"]}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `list binds the query group and defaults to page 1 limit 20`() = exampleApp {
        client.post("/orgs/1/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice","email":"alice@example.com","tags":["admin"]}""")
        }
        client.post("/orgs/1/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bob","email":"bob@example.com"}""")
        }

        val defaults = client.get("/orgs/1/users")
        assertEquals(HttpStatusCode.OK, defaults.status)
        assertTrue(defaults.bodyAsText().contains(""""page":1,"limit":20,"total":2"""), defaults.bodyAsText())

        val paged = client.get("/orgs/1/users?page=2&limit=1")
        assertTrue(paged.bodyAsText().contains(""""name":"bob""""), paged.bodyAsText())

        val tagged = client.get("/orgs/1/users?tag=admin")
        assertTrue(tagged.bodyAsText().contains(""""total":1"""), tagged.bodyAsText())
    }

    @Test
    fun `validation failures are collected and reported as 422`() = exampleApp {
        val response = client.post("/orgs/1/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  ","email":"not-an-email"}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(""""path":"name""""), body)
        assertTrue(body.contains(""""path":"email""""), body)
    }

    @Test
    fun `binding failures are reported as 400`() = exampleApp {
        val response = client.get("/orgs/not-a-number/users")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("invalid request"), response.bodyAsText())
    }

    @Test
    fun `malformed body is reported as 400`() = exampleApp {
        val response = client.post("/orgs/1/users") {
            contentType(ContentType.Application.Json)
            setBody("{ broken")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("malformed body"), response.bodyAsText())
    }

    @Test
    fun `get and delete a single user`() = exampleApp {
        client.post("/orgs/1/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice","email":"alice@example.com"}""")
        }

        assertEquals(HttpStatusCode.OK, client.get("/orgs/1/users/1").status)

        val deleted = client.delete("/orgs/1/users/1")
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, client.get("/orgs/1/users/1").status)
    }

    @Test
    fun `swagger serves a document describing the typed endpoints`() = exampleApp {
        // 文書のソースを指定していないので、ルートツリーから生成されたものが返る。
        val document = client.get("/swagger/documentation.yaml").bodyAsText()

        assertTrue(document.contains("/orgs/{orgId}/users:"), document)
        assertTrue(document.contains("ユーザーを一覧する"), document)
        // install(TypedRoutingOpenApi) だけで、describeTypedEndpoints() の明示呼び出しは要らない。
        assertTrue(document.contains("orgId"), document)
        // hide() した素の Ktor ルートと Swagger 自身のルートは載らない。
        assertTrue(!document.contains("/health:"), document)
        assertTrue(!document.contains("/swagger"), document)

        assertEquals(HttpStatusCode.OK, client.get("/swagger").status)
    }
}
