package app.aaps.pump.tandem.common.concurrency

/**
 * Submission priority. Determines insertion point in [PumpOpQueue]; FIFO within each tier.
 *
 * Priority is independent of the per-op `requiresDeliveryEnabled` flag — priority controls
 * *when* an op runs relative to others, the flag controls *whether* an op runs given the current
 * [PumpAvailability]. By convention [CRITICAL] ops set `requiresDeliveryEnabled = false` because
 * they typically need to run during the very workflow that disabled delivery, but the two stay
 * orthogonal so each op states its actual semantics.
 */
enum class Priority {
    /**
     * Emergency overrides — bolus cancel, alarm acknowledgement, force-stop. Front of the queue,
     * ahead of even [USER_INITIATED] ops. Almost always paired with `requiresDeliveryEnabled = false`.
     */
    CRITICAL,

    /**
     * UI-driven ops (taps, refresh, settings change). Ahead of [DEFAULT] so a user tap never
     * waits behind a queued AAPS loop cycle. Behind [CRITICAL].
     */
    USER_INITIATED,

    /**
     * Default tier for ops without a more specific priority — AAPS Loop bolus/TBR, profile sync,
     * scheduled status pulls. Below [USER_INITIATED], above [BACKGROUND].
     */
    DEFAULT,

    /**
     * Lowest-priority maintenance work — history log fetches, audit pulls. Runs only when no
     * higher-priority op is queued. Subject to the queue's per-priority rate limit (default
     * configured for BACKGROUND only) so a long history sync cannot saturate the wire.
     */
    BACKGROUND
}
