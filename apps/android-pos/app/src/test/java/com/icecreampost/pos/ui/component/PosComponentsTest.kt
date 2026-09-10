package com.icecreampost.pos.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class PosComponentsTest {
    @Test
    fun `sales amounts can be hidden without changing their source value`() {
        val value = 12_345L

        assertEquals("₱123.45", formatMoney(value))
        assertEquals("₱••••", formatMoney(value, visible = false))
        assertEquals(12_345L, value)
    }
}
