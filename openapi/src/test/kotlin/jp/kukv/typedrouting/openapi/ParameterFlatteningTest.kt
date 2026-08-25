package jp.kukv.typedrouting.openapi

import jp.kukv.typedrouting.Body
import jp.kukv.typedrouting.Cookie
import jp.kukv.typedrouting.Header
import jp.kukv.typedrouting.Path
import jp.kukv.typedrouting.Query
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParameterFlatteningTest {
    @Serializable
    data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    data class Payload(val name: String)

    @Serializable
    data class Req(
        @Path val orgId: Long,
        @Query val paging: Paging,
        @Query(prefix = "f.") val filters: Paging,
        @Query val q: String?,
        @Header("X-Trace-Id") val traceId: String?,
        @Body val payload: Payload,
    )

    @Serializable
    data class NoBody(@Query val q: String)

    @Serializable
    enum class Order { Asc, Desc }

    @JvmInline
    @Serializable
    value class UserId(val raw: Long)

    /** data class ではない `@Serializable class`。`:core` ではグループとして展開される。 */
    @Serializable
    class Window(val from: String, val to: String)

    @Serializable
    data class Scalars(
        @Query val order: Order,
        @Query val userId: UserId,
        @Cookie(prefix = "c.") val prefs: Paging,
        @Query val window: Window,
    )

    @Serializable
    data class OptionalGroups(
        @Query val paging: Window = Window("a", "b"),
        @Query(prefix = "n.") val nested: Window?,
    )

    @Serializable
    data class Inner(@Query(prefix = "i.") val window: Window)

    @Serializable
    data class Outer(@Query(prefix = "o.") val inner: Inner)

    @Test
    fun `groups are flattened with their prefix in declaration order`() {
        val flat = typeOf<Req>().flattenParameters()

        assertEquals(
            listOf("orgId", "page", "limit", "f.page", "f.limit", "q", "X-Trace-Id"),
            flat.map { it.name },
        )
    }

    @Test
    fun `locations are carried through`() {
        val flat = typeOf<Req>().flattenParameters().associateBy { it.name }

        assertEquals(ParameterIn.PATH, flat.getValue("orgId").location)
        assertEquals(ParameterIn.QUERY, flat.getValue("f.page").location)
        assertEquals(ParameterIn.HEADER, flat.getValue("X-Trace-Id").location)
    }

    @Test
    fun `required is false for defaults and nullables`() {
        val flat = typeOf<Req>().flattenParameters().associateBy { it.name }

        assertEquals(true, flat.getValue("orgId").required)
        assertEquals(false, flat.getValue("page").required, "has a default")
        assertEquals(false, flat.getValue("q").required, "is nullable")
    }

    @Test
    fun `element types are carried through for schema inference`() {
        val flat = typeOf<Req>().flattenParameters().associateBy { it.name }

        assertEquals(typeOf<Long>(), flat.getValue("orgId").type)
        assertEquals(typeOf<Int>(), flat.getValue("page").type)
        assertEquals(typeOf<String?>(), flat.getValue("q").type)
    }

    @Test
    fun `body type is found when present`() {
        assertEquals(typeOf<Payload>(), typeOf<Req>().bodyParameterType())
        assertNull(typeOf<NoBody>().bodyParameterType())
        assertNotNull(typeOf<Req>().bodyParameterType())
    }

    @Test
    fun `value classes and enums stay scalar`() {
        val flat = typeOf<Scalars>().flattenParameters().associateBy { it.name }

        assertEquals(typeOf<Order>(), flat.getValue("order").type)
        assertEquals(typeOf<UserId>(), flat.getValue("userId").type)
    }

    @Test
    fun `non-data serializable classes are expanded as groups`() {
        val flat = typeOf<Scalars>().flattenParameters()

        assertEquals(
            listOf("order", "userId", "c.page", "c.limit", "from", "to"),
            flat.map { it.name },
        )
    }

    @Test
    fun `cookie groups carry their location`() {
        val flat = typeOf<Scalars>().flattenParameters().associateBy { it.name }

        assertEquals(ParameterIn.COOKIE, flat.getValue("c.page").location)
        assertEquals(ParameterIn.COOKIE, flat.getValue("c.limit").location)
    }

    @Test
    fun `children of an optional or nullable group are optional`() {
        val flat = typeOf<OptionalGroups>().flattenParameters().associateBy { it.name }

        assertEquals(false, flat.getValue("from").required, "group has a default")
        assertEquals(false, flat.getValue("to").required, "group has a default")
        assertEquals(false, flat.getValue("n.from").required, "group is nullable")
        assertEquals(false, flat.getValue("n.to").required, "group is nullable")
    }

    @Test
    fun `nested group prefixes are composed`() {
        val flat = typeOf<Outer>().flattenParameters()

        assertEquals(listOf("o.i.from", "o.i.to"), flat.map { it.name })
        assertEquals(true, flat.all { it.required })
    }
}
