package com.icecreampost.pos.ui.component

import com.icecreampost.pos.data.local.entity.SyncStateEntity
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationTest {
    @Test fun `stock balances suppress decimal zeroes and normalize pieces`() {
        assertEquals("598 g", formatStock(598.0, "g"))
        assertEquals("620 pcs", formatStock(620.0, "piece"))
        assertEquals("50 pcs", formatStock(50.0, "pieces"))
        assertEquals("10,730 g", formatStock(10730.0, "g"))
        assertEquals("0.125 ml", formatStock(0.125, "ml"))
    }

    @Test fun `receipt UTC time renders in business timezone without altering input`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val timestamp = "2026-09-30T10:03:46.829Z"
            assertEquals("Sep 30, 2026 · 6:03 PM", formatReceiptTime(timestamp))
            assertEquals("Sep 30, 2026 · 10:03 AM", formatReceiptTime(timestamp, ZoneId.of("UTC")))
            assertEquals("2026-09-30T10:03:46.829Z", timestamp)
            assertEquals("Date unavailable", formatReceiptTime("invalid"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `sync status distinguishes actual offline failure pending and recent success`() {
        val now = Instant.parse("2026-09-30T10:04:00Z")
        val success = SyncStateEntity("sync", "", status = "success", lastSyncAt = "2026-09-30T10:03:46Z")
        assertEquals("Synced just now", syncStatusLabel(success, 0, true, now))
        assertEquals("Offline · 3 transactions pending", syncStatusLabel(success, 3, false, now))
        assertEquals("3 transactions pending", syncStatusLabel(success, 3, true, now))
        assertEquals("Sync failed · 3 transactions pending", syncStatusLabel(success.copy(status = "error"), 3, true, now))
        assertEquals("Offline · 0 transactions pending", syncStatusLabel(success.copy(status = "error"), 0, false, now))
        assertEquals("Ready to sync", syncStatusLabel(null, 0, true, now))
    }
}
