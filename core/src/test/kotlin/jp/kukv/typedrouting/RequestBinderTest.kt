package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestBinderTest {
    @Serializable
    private data class Payload(val name: String)

    @Serializable
    private data class NeedsBody(@Body val payload: Payload)

    @Serializable
    private data class NoBody(@Query val q: String)

    @Serializable
    private data class Required(
        @Query val a: String,
        @Query val b: Int,
        @Query val c: String?,
    )

    @Test
    fun `body presence is detected from the descriptor`() {
        assertTrue(serializer<NeedsBody>().descriptor.hasBodyElement())
        assertFalse(serializer<NoBody>().descriptor.hasBodyElement())
    }

    @Test
    fun `missing required fields become violations`() {
        val ctx = BindingContext(
            sources = RequestSources(
                ParameterSource.Empty,
                ParameterSource.Empty,
                ParameterSource.Empty,
                ParameterSource.Empty,
            ),
            bodyText = null,
            format = Json,
        )

        val e = assertFailsWith<RequestBindingException> {
            ctx.decodeOrThrow(serializer<Required>())
        }

        assertEquals(setOf("a", "b"), e.violations.map { it.path }.toSet())
        assertTrue(e.violations.all { it.message == "is required" })
    }

    @Test
    fun `conversion violations are reported together`() {
        val query = mapOf("a" to listOf("ok"), "b" to listOf("nope"))
        val ctx = BindingContext(
            sources = RequestSources(
                ParameterSource.Empty,
                ParameterSource { query[it] },
                ParameterSource.Empty,
                ParameterSource.Empty,
            ),
            bodyText = null,
            format = Json,
        )

        val e = assertFailsWith<RequestBindingException> {
            ctx.decodeOrThrow(serializer<Required>())
        }

        assertEquals(listOf("b"), e.violations.map { it.path })
    }
}
