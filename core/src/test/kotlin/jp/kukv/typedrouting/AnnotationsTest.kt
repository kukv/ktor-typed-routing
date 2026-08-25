package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationsTest {
    @Serializable
    private data class Sample(
        @Path val orgId: Long,
        @Query("q") val keyword: String?,
        @Header("X-Trace-Id") val traceId: String?,
        @Body val payload: String,
    )

    @Test
    fun `annotations are readable from the serial descriptor`() {
        val descriptor = serializer<Sample>().descriptor

        assertTrue(descriptor.getElementAnnotations(0).any { it is Path })
        assertEquals("q", descriptor.getElementAnnotations(1).filterIsInstance<Query>().single().name)
        assertEquals("X-Trace-Id", descriptor.getElementAnnotations(2).filterIsInstance<Header>().single().name)
        assertTrue(descriptor.getElementAnnotations(3).any { it is Body })
    }

    @Test
    fun `violations carry path and message`() {
        val e = RequestBindingException(listOf(Violation("page", "must be an integer")))
        assertEquals(1, e.violations.size)
        assertEquals("page", e.violations.single().path)
    }
}
