package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestDecoderCollectionTest {
    @Serializable
    private data class Payload(val name: String, val age: Int)

    @Serializable
    private data class WithList(
        @Query val tags: List<String>,
        @Query val ids: List<Int> = emptyList(),
    )

    @Serializable
    private data class WithBody(
        @Path val orgId: Long,
        @Body val payload: Payload,
    )

    private fun context(
        query: Map<String, List<String>> = emptyMap(),
        path: Map<String, List<String>> = emptyMap(),
        bodyText: String? = null,
    ) = BindingContext(
        sources = RequestSources(
            path = ParameterSource { path[it] },
            query = ParameterSource { query[it] },
            header = ParameterSource.Empty,
            cookie = ParameterSource.Empty,
        ),
        bodyText = bodyText,
        format = Json,
    )

    @Test
    fun `repeated query parameters become a list`() {
        val ctx = context(query = mapOf("tags" to listOf("a", "b", "c")))

        val result = ctx.decode(serializer<WithList>())

        assertEquals(listOf("a", "b", "c"), result.tags)
        assertEquals(emptyList(), result.ids)
    }

    @Test
    fun `list elements are converted to the element type`() {
        val ctx = context(query = mapOf("tags" to listOf("x"), "ids" to listOf("1", "2")))

        val result = ctx.decode(serializer<WithList>())

        assertEquals(listOf(1, 2), result.ids)
    }

    @Test
    fun `body is decoded from the prefetched text`() {
        val ctx = context(
            path = mapOf("orgId" to listOf("7")),
            bodyText = """{"name":"alice","age":30}""",
        )

        val result = ctx.decode(serializer<WithBody>())

        assertEquals(7L, result.orgId)
        assertEquals(Payload("alice", 30), result.payload)
    }
}
