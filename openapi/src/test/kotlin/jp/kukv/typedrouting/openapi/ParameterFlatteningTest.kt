package jp.kukv.typedrouting.openapi

import jp.kukv.typedrouting.Body
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
}
