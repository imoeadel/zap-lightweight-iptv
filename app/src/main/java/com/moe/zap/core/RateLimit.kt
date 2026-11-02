package com.moe.zap.core

/** How long to stay quiet after a provider answers 429 Too Many Requests. */
object RateLimit {
    const val DEFAULT_WAIT_SECONDS = 120L
    private const val LONGEST_WAIT_SECONDS = 900L

    /** Honors Retry-After in seconds. Anything missing, nonsense or absurdly long gets a sensible wait. */
    fun waitSeconds(retryAfter: String?): Long {
        val asked = retryAfter?.trim()?.toLongOrNull() ?: return DEFAULT_WAIT_SECONDS
        if (asked <= 0) return DEFAULT_WAIT_SECONDS
        return asked.coerceAtMost(LONGEST_WAIT_SECONDS)
    }
}
