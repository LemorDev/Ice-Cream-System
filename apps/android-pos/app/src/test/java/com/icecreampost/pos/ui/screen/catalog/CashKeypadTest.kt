package com.icecreampost.pos.ui.screen.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CashKeypadTest {
    @Test
    fun `keypad builds a peso amount and limits centavos`() {
        var value = ""
        listOf("1", "2", ".", "3", "4", "5").forEach { value = updateCashInput(value, it) }
        assertEquals("12.34", value)
    }

    @Test
    fun `keypad supports decimal-first entry and backspace`() {
        var value = updateCashInput("", ".")
        value = updateCashInput(value, "5")
        value = updateCashInput(value, "⌫")
        assertEquals("0.", value)
    }

    @Test
    fun `keypad prevents multiple decimal points`() {
        assertEquals("12.3", updateCashInput("12.3", "."))
    }
}
