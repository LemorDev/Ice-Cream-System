package com.icecreampost.pos.ui.screen.home

import com.icecreampost.pos.data.local.entity.TransactionEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAnalyticsTest {
    private fun sale(id: String, occurredAt: String, stall: String = "stall", status: String = "completed") =
        TransactionEntity(id = id, stallId = stall, totalCents = 4000, createdAt = occurredAt,
            occurredAt = occurredAt, status = status)

    @Test fun `seven day chart counts completed sales in Manila business days`() {
        val today = LocalDate.parse("2026-10-01")
        val rows = listOf(
            sale("local-today", "2026-09-30T16:30:00Z"), // Oct 1, 12:30 AM in Manila
            sale("earlier", "2026-09-29T16:30:00Z"),
            sale("voided", "2026-09-30T17:00:00Z", status = "voided"),
            sale("other-stall", "2026-09-30T17:00:00Z", stall = "other"),
            sale("deleted", "2026-09-30T17:00:00Z").copy(deletedAt = "2026-10-01"),
        )
        val points = salesAnalytics(rows, "stall", today, 7)
        assertEquals(7, points.size)
        assertEquals(today, points.last().start)
        assertEquals(4000L, points.last().revenueCents)
        assertEquals(1, points.last().orders)
        assertEquals(4000L, points[5].revenueCents)
        assertEquals(0, points.first().orders)
    }

    @Test fun `thirty day chart groups five business days per selectable bar`() {
        val today = LocalDate.parse("2026-10-01")
        val rows = listOf(sale("first", "2026-09-02T12:00:00Z"), sale("last", "2026-10-01T12:00:00Z"))
        val points = salesAnalytics(rows, "stall", today, 30)
        assertEquals(6, points.size)
        assertEquals(LocalDate.parse("2026-09-02"), points.first().start)
        assertEquals(today, points.last().end)
        assertEquals(1, points.first().orders)
        assertEquals(1, points.last().orders)
    }
}
