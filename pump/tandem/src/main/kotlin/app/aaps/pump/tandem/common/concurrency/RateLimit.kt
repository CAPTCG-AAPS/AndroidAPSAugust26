package app.aaps.pump.tandem.common.concurrency

/**
 * Token-bucket rate limit for a [Priority] tier in [PumpOpQueue].
 *
 * @param rps sustained rate in messages per second (the bucket refill rate)
 * @param burst bucket capacity — how many ops may dispatch back-to-back from a full bucket
 *   before throttling kicks in. Set to [rps] (or 1) for steady-state pacing; higher values
 *   allow short bursts.
 */
data class RateLimit(val rps: Double, val burst: Int) {
    init {
        require(rps > 0) { "rps must be > 0, got $rps" }
        require(burst >= 1) { "burst must be >= 1, got $burst" }
    }
}

/**
 * Token bucket. Not thread-safe on its own — callers serialize access (in [PumpOpQueue], every
 * call happens inside the queue's master `lock`).
 *
 * The bucket starts full so the first burst-worth of ops dispatch immediately on driver start.
 */
class TokenBucket(
    private val limit: RateLimit,
    private val timeSource: () -> Long = System::currentTimeMillis
) {
    private var tokens: Double = limit.burst.toDouble()
    private var lastRefillMs: Long = timeSource()

    /**
     * Try to consume one token.
     *
     * @return `0L` if a token was consumed and the caller may dispatch immediately; otherwise
     *   the number of milliseconds the caller should wait before retrying.
     */
    fun tryAcquire(): Long {
        refill()
        return if (tokens >= 1.0) {
            tokens -= 1.0
            0L
        } else {
            val needed = 1.0 - tokens
            (needed / limit.rps * 1000.0).toLong().coerceAtLeast(1L)
        }
    }

    private fun refill() {
        val now = timeSource()
        val elapsedMs = now - lastRefillMs
        if (elapsedMs > 0) {
            tokens = (tokens + elapsedMs / 1000.0 * limit.rps).coerceAtMost(limit.burst.toDouble())
            lastRefillMs = now
        }
    }
}
