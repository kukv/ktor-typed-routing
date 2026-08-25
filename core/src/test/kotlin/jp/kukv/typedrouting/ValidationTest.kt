package jp.kukv.typedrouting

import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationTest {
    @Test
    fun `rejections accumulate into one exception`() {
        val scope = ValidationScope()
        scope.reject("page", "must be >= 1")
        scope.reject("limit", "must be 1..100")

        assertEquals(2, scope.violations.size)
        assertEquals(listOf("page", "limit"), scope.violations.map { it.path })
    }

    @Test
    fun `no rejection means no violations`() {
        val scope = ValidationScope()
        assertEquals(0, scope.violations.size)
    }
}
