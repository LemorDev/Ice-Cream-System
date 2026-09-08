package com.icecreampost.pos.ui.screen.checkout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentScreenTest {
    @Test
    fun `cash input converts pesos to cents`() {
        assertEquals(2_950L, "29.50".toCentsOrNull())
        assertEquals(10_000L, "100".toCentsOrNull())
        assertEquals(5L, ".05".toCentsOrNull())
    }

    @Test
    fun `invalid and over-precision cash input is rejected`() {
        assertNull("".toCentsOrNull())
        assertNull("abc".toCentsOrNull())
        assertNull("1.001".toCentsOrNull())
    }

    @Test
    fun `quick cash includes exact total and useful rounded amounts`() {
        assertEquals(
            listOf(2_950L, 5_000L, 10_000L, 20_000L),
            buildSuggestedCashAmounts(2_950L),
        )
    }

    @Test
    fun `quick cash removes duplicate rounded amounts`() {
        assertEquals(
            listOf(10_000L, 20_000L, 50_000L, 100_000L),
            buildSuggestedCashAmounts(10_000L),
        )
    }
}
