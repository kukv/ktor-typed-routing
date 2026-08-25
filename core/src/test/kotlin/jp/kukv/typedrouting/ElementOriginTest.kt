package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ElementOriginTest {
    @Serializable
    private data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    private enum class Order { Asc, Desc }

    @JvmInline
    @Serializable
    private value class UserId(val raw: Long)

    @Serializable
    private data class Sample(
        @Path val orgId: Long,
        @Query("q") val keyword: String?,
        @Query val paging: Paging,
        @Query(prefix = "f.") val filters: Paging,
        @Query val tags: List<String>,
        @Query val order: Order,
        @Path val userId: UserId,
        @Header("X-Trace-Id") val traceId: String?,
        @Cookie val session: String?,
        @Body val payload: String,
    )

    @Serializable
    private data class NoAnnotation(val plain: String)

    @Serializable
    private data class PrefixedHeaderAndCookie(
        @Header(prefix = "h.") val meta: Paging,
        @Cookie(prefix = "c.") val prefs: Paging,
    )

    private val descriptor = serializer<Sample>().descriptor

    @Test
    fun `explicit name wins over property name`() {
        assertEquals(ElementOrigin(SourceKind.QUERY, "q", ""), descriptor.originOf(1))
    }

    @Test
    fun `property name is used when the annotation name is empty`() {
        assertEquals(ElementOrigin(SourceKind.PATH, "orgId", ""), descriptor.originOf(0))
    }

    @Test
    fun `prefix is carried on the origin`() {
        assertEquals("f.", descriptor.originOf(3).prefix)
    }

    @Test
    fun `every source kind is recognised`() {
        assertEquals(SourceKind.HEADER, descriptor.originOf(7).kind)
        assertEquals(SourceKind.COOKIE, descriptor.originOf(8).kind)
        assertEquals(SourceKind.BODY, descriptor.originOf(9).kind)
    }

    @Test
    fun `group children inherit the parent source kind`() {
        val child = serializer<Paging>().descriptor
        assertEquals(
            ElementOrigin(SourceKind.QUERY, "page", ""),
            child.originOf(0, inherited = SourceKind.QUERY),
        )
    }

    @Test
    fun `structure kinds are groups and everything else is scalar`() {
        assertTrue(descriptor.isGroup(2), "data class is a group")
        assertFalse(descriptor.isGroup(0), "primitive is scalar")
        assertFalse(descriptor.isGroup(4), "List is scalar")
        assertFalse(descriptor.isGroup(5), "enum is scalar")
        assertFalse(descriptor.isGroup(6), "value class is scalar")
        assertFalse(descriptor.isGroup(9), "body is never a group")
    }

    @Test
    fun `sourceFor returns the matching source and Empty for BODY`() {
        val path = ParameterSource { listOf("path-$it") }
        val query = ParameterSource { listOf("query-$it") }
        val header = ParameterSource { listOf("header-$it") }
        val cookie = ParameterSource { listOf("cookie-$it") }
        val sources = RequestSources(path, query, header, cookie)

        assertSame(path, sources.sourceFor(SourceKind.PATH))
        assertSame(query, sources.sourceFor(SourceKind.QUERY))
        assertSame(header, sources.sourceFor(SourceKind.HEADER))
        assertSame(cookie, sources.sourceFor(SourceKind.COOKIE))
        assertSame(ParameterSource.Empty, sources.sourceFor(SourceKind.BODY))
    }

    @Test
    fun `root element without a source annotation fails loudly`() {
        val noAnnotation = serializer<NoAnnotation>().descriptor
        val exception = assertFailsWith<IllegalStateException> { noAnnotation.originOf(0) }
        assertTrue(exception.message!!.contains("plain"), "message should mention the offending property")
    }

    @Test
    fun `header and cookie prefixes are carried on the origin`() {
        val d = serializer<PrefixedHeaderAndCookie>().descriptor
        assertEquals("h.", d.originOf(0).prefix)
        assertEquals("c.", d.originOf(1).prefix)
    }
}
