package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestDecoderTest {
    @Serializable
    private data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    private enum class Order { Asc, Desc }

    @JvmInline
    @Serializable
    private value class UserId(val raw: Long)

    @Serializable
    private data class Scalars(
        @Query val name: String,
        @Query val count: Int,
        @Query val ratio: Double,
        @Query val enabled: Boolean,
        @Query val order: Order,
        @Query val userId: UserId,
        @Query val optional: String?,
        @Query val withDefault: Int = 7,
    )

    @Serializable
    private data class Grouped(
        @Query val paging: Paging,
        @Query(prefix = "f.") val filters: Paging,
    )

    private fun context(vararg pairs: Pair<String, List<String>>): BindingContext {
        val query = ParameterSource { name -> pairs.firstOrNull { it.first == name }?.second }
        return BindingContext(
            sources = RequestSources(
                path = ParameterSource.Empty,
                query = query,
                header = ParameterSource.Empty,
                cookie = ParameterSource.Empty,
            ),
            bodyText = null,
            format = Json,
        )
    }

    @Test
    fun `decodes every scalar kind`() {
        val ctx = context(
            "name" to listOf("alice"),
            "count" to listOf("3"),
            "ratio" to listOf("1.5"),
            "enabled" to listOf("true"),
            "order" to listOf("Desc"),
            "userId" to listOf("42"),
            "optional" to listOf("here"),
            "withDefault" to listOf("9"),
        )

        val result = ctx.decode(serializer<Scalars>())

        assertEquals("alice", result.name)
        assertEquals(3, result.count)
        assertEquals(1.5, result.ratio)
        assertEquals(true, result.enabled)
        assertEquals(Order.Desc, result.order)
        assertEquals(UserId(42), result.userId)
        assertEquals("here", result.optional)
        assertEquals(9, result.withDefault)
    }

    @Test
    fun `absent nullable becomes null and absent default keeps the default`() {
        val ctx = context(
            "name" to listOf("alice"),
            "count" to listOf("3"),
            "ratio" to listOf("1.5"),
            "enabled" to listOf("true"),
            "order" to listOf("Asc"),
            "userId" to listOf("1"),
        )

        val result = ctx.decode(serializer<Scalars>())

        assertNull(result.optional)
        assertEquals(7, result.withDefault)
    }

    @Test
    fun `conversion failures are collected instead of thrown`() {
        val ctx = context(
            "name" to listOf("alice"),
            "count" to listOf("abc"),
            "ratio" to listOf("xyz"),
            "enabled" to listOf("true"),
            "order" to listOf("Asc"),
            "userId" to listOf("1"),
        )

        ctx.decode(serializer<Scalars>())

        assertEquals(listOf("count", "ratio"), ctx.violations.map { it.path })
        assertTrue(ctx.violations.all { it.message.isNotBlank() })
    }

    @Test
    fun `groups are flattened without a prefix by default`() {
        val ctx = context(
            "page" to listOf("2"),
            "limit" to listOf("50"),
            "f.page" to listOf("3"),
        )

        val result = ctx.decode(serializer<Grouped>())

        assertEquals(Paging(page = 2, limit = 50), result.paging)
        assertEquals(Paging(page = 3, limit = 20), result.filters)
    }
}
