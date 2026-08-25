package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointSpecTest {
    @Serializable
    private data class Paging(val page: Int = 1, val limit: Int = 20)

    @Serializable
    private data class Range(val page: Int = 0)

    @Serializable
    private data class BadGroup(@Body val payload: String)

    @Serializable
    private data class Colliding(
        @Query val paging: Paging,
        @Query val range: Range,
    )

    @Serializable
    private data class BodyInGroup(
        @Query val nested: BadGroup,
    )

    @Serializable
    private data class ScalarBody(
        @Query val page: Int,
        @Body val text: String,
    )

    @Serializable
    private enum class Kind { A, B }

    @Serializable
    private data class EnumBody(@Body val kind: Kind)

    @JvmInline
    @Serializable
    private value class Token(val raw: String)

    @Serializable
    private data class ValueClassBody(
        @Query val page: Int,
        @Body val token: Token,
    )

    @Serializable
    private data class Payload(val name: String)

    @Serializable
    private data class TwoBodies(
        @Body val first: Payload,
        @Body val second: Payload,
    )

    @Serializable
    private data class Fine(
        @Query val paging: Paging,
        @Query(prefix = "r.") val range: Range,
    )

    @Test
    fun `name collisions across groups are rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            serializer<Colliding>().descriptor.validateBindingShape()
        }
        assertTrue(e.message!!.contains("page"))
    }

    @Test
    fun `body inside a group is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            serializer<BodyInGroup>().descriptor.validateBindingShape()
        }
        assertTrue(e.message!!.contains("@Body"))
    }

    @Test
    fun `a scalar body is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            serializer<ScalarBody>().descriptor.validateBindingShape()
        }
        assertTrue(e.message!!.contains("text"))
        assertTrue(e.message!!.contains("structural"))
    }

    @Test
    fun `an enum body is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            serializer<EnumBody>().descriptor.validateBindingShape()
        }
        assertTrue(e.message!!.contains("kind"))
    }

    @Test
    fun `a value class body is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            serializer<ValueClassBody>().descriptor.validateBindingShape()
        }
        assertTrue(e.message!!.contains("token"))
        assertTrue(e.message!!.contains("structural"))
    }

    @Test
    fun `two body parameters are rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            serializer<TwoBodies>().descriptor.validateBindingShape()
        }
        assertTrue(e.message!!.contains("second"))
        assertTrue(e.message!!.contains("@Body"))
    }

    @Test
    fun `a prefix resolves the collision`() {
        serializer<Fine>().descriptor.validateBindingShape()
    }
}
