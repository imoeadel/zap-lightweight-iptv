package com.moe.zap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimitTest {
    @Test fun honorsRetryAfter() = assertEquals(30L, RateLimit.waitSeconds(" 30 "))
    @Test fun missingHeaderUsesDefault() = assertEquals(RateLimit.DEFAULT_WAIT_SECONDS, RateLimit.waitSeconds(null))
    @Test fun dateFormUsesDefault() =
        assertEquals(RateLimit.DEFAULT_WAIT_SECONDS, RateLimit.waitSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
    @Test fun zeroOrNegativeUsesDefault() {
        assertEquals(RateLimit.DEFAULT_WAIT_SECONDS, RateLimit.waitSeconds("0"))
        assertEquals(RateLimit.DEFAULT_WAIT_SECONDS, RateLimit.waitSeconds("-5"))
    }
    @Test fun capsAbsurdWaits() = assertEquals(900L, RateLimit.waitSeconds("999999"))
}
