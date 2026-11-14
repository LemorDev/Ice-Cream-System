package com.icecreampost.pos.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PosUpdateRepositoryTest {
    private val applicationId = "com.icecreampost.pos.dev"
    private val release = PosRelease(applicationId, 6, "0.1.5-dev", "https://updates.example.invalid/6.apk", "a".repeat(64))

    @Test
    fun `only releases newer than the installed build are offered`() {
        assertEquals(release, readRelease(release, installedVersion = 5))
        assertNull(readRelease(release, installedVersion = 6))
        assertNull(readRelease(release, installedVersion = 7))
    }

    @Test
    fun `a different package insecure URL or missing digest is rejected`() {
        for (invalid in listOf(release.copy(applicationId = "com.icecreampost.pos"),
            release.copy(apkUrl = "http://updates.example.invalid/6.apk"), release.copy(sha256 = ""))) {
            assertThrows(IllegalStateException::class.java) { readRelease(invalid, installedVersion = 5) }
        }
    }

    private fun readRelease(value: PosRelease, installedVersion: Int) =
        parsePosRelease(Json, Json.encodeToString(value), applicationId, installedVersion)
}
