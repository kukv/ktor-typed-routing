package jp.kukv.typedrouting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** グループ要素の「値が 1 つも無い」場合の挙動（spec 6.7.1）を固定する。 */
class RequestDecoderGroupTest {
    @Serializable
    private data class Paging(val page: Int = 1, val limit: Int = 20)

    /** 子に既定値が無いグループ。展開されると必須欠落になる。 */
    @Serializable
    private data class Strict(val a: String)

    @Serializable
    private data class Plain(@Query val paging: Paging)

    @Serializable
    private data class WithDefault(@Query val paging: Paging = Paging(page = 9, limit = 99))

    @Serializable
    private data class Nullable(@Query val paging: Paging?)

    /** 直前の nullable スカラーが未指定のまま、次のグループに値がある並び。 */
    @Serializable
    private data class AfterAbsentNullable(
        @Query val q: String?,
        @Query val paging: Paging?,
    )

    /** グループの中に更にグループがある形。外側の存在判定は内側まで再帰する必要がある。 */
    @Serializable
    private data class Middle(@Query(prefix = "i.") val inner: Strict?)

    @Serializable
    private data class Nested(@Query(prefix = "m.") val middle: Middle?)

    /** 直前のスカラーに値があり、次の nullable グループには値が無い並び。 */
    @Serializable
    private data class AfterPresentScalar(
        @Query val q: String?,
        @Query val strict: Strict?,
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
    fun `plain group with no values falls back to each child default`() {
        val result = context().decode(serializer<Plain>())

        assertEquals(Paging(page = 1, limit = 20), result.paging)
    }

    @Test
    fun `group with a default and no values keeps the whole group default`() {
        val result = context().decode(serializer<WithDefault>())

        assertEquals(Paging(page = 9, limit = 99), result.paging)
    }

    @Test
    fun `group with a default is still expanded when a value is present`() {
        val result = context("page" to listOf("2")).decode(serializer<WithDefault>())

        assertEquals(Paging(page = 2, limit = 20), result.paging)
    }

    @Test
    fun `nullable group with no values becomes null`() {
        val ctx = context()

        val result = ctx.decode(serializer<Nullable>())

        assertNull(result.paging)
        assertTrue(ctx.violations.isEmpty())
    }

    @Test
    fun `nullable group is expanded when a value is present`() {
        val result = context("page" to listOf("2")).decode(serializer<Nullable>())

        assertEquals(Paging(page = 2, limit = 20), result.paging)
    }

    @Test
    fun `nullable group after an absent nullable scalar is still expanded`() {
        val ctx = context("page" to listOf("2"))

        val result = ctx.decode(serializer<AfterAbsentNullable>())

        assertNull(result.q)
        assertEquals(Paging(page = 2, limit = 20), result.paging)
    }

    @Test
    fun `nullable group containing only an empty nested group becomes null`() {
        val ctx = context()

        val result = ctx.decode(serializer<Nested>())

        assertNull(result.middle)
        assertTrue(ctx.violations.isEmpty())
    }

    @Test
    fun `nested group is expanded when a descendant has a value`() {
        val result = context("m.i.a" to listOf("x")).decode(serializer<Nested>())

        assertEquals(Strict("x"), result.middle?.inner)
    }

    @Test
    fun `nullable group with no values after a present scalar becomes null`() {
        val ctx = context("q" to listOf("hello"))

        val result = ctx.decode(serializer<AfterPresentScalar>())

        assertEquals("hello", result.q)
        assertNull(result.strict)
    }
}
