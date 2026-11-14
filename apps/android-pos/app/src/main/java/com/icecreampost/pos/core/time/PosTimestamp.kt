package com.icecreampost.pos.core.time

import java.time.Instant
import java.time.OffsetDateTime

// Supabase sends numeric offsets, which older Android versions reject in Instant.parse.
internal fun parsePosTimestamp(value: String): Instant = OffsetDateTime.parse(value).toInstant()
