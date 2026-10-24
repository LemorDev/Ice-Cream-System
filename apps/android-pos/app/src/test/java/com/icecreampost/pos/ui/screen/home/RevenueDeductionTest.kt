package com.icecreampost.pos.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RevenueDeductionTest {
    @Test
    fun `zero or blank deduction does not require a reason`() {
        assertNull(validateRevenueDeduction("", "", 10_000))
        assertEquals("Enter a deduction greater than zero.", validateRevenueDeduction("0", "", 10_000))
    }

    @Test
    fun `positive deduction requires a reason`() {
        assertEquals(
            "Provide a reason for the deduction.",
            validateRevenueDeduction("12.50", "  ", 10_000),
        )
        assertNull(validateRevenueDeduction("12.50", "Customer refund", 10_000))
    }

    @Test
    fun `invalid or excessive deduction is rejected`() {
        assertEquals("Enter a valid deduction amount.", validateRevenueDeduction("1.001", "Reason", 10_000))
        assertEquals("Deduction cannot exceed gross sales.", validateRevenueDeduction("100.01", "Reason", 10_000))
    }
}
