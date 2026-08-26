package app.aaps.pump.omnipod.omnipod5.bledriver.comm

/**
 * PodId rotation for Omnipod 5 pairing retries, ported from OmnipodKit's Id.swift
 * (`nextPodId`/`controllerIdForPodId`).
 *
 * Unlike Dash (whose controllerId is randomly generated locally), O5's controllerId must
 * match a value already present in the certificate store - so podIds cycle through 3
 * values (`controllerId+1`, `+2`, `+3`) built on top of a *fixed* controllerId, rather than
 * generating a fresh controllerId per attempt the way Dash does.
 */
object O5IdRotation {

    private const val CONTROLLER_ID_BIT_MASK: Long = 0b11L

    /** Given a [podId], returns the controllerId it was derived from (clears the low 2 bits). */
    fun controllerIdForPodId(podId: Long): Long =
        podId and (CONTROLLER_ID_BIT_MASK.inv())

    /**
     * Returns the next podId in the 3-way rotation after [lastPodId]: `+1, +2, +3`, then
     * wraps back to `base+1` (matching OmnipodKit's exact wrap-around behavior, used when
     * a pairing attempt with one podId fails and the next one in rotation should be tried).
     */
    fun nextPodId(lastPodId: Long): Long {
        val low2Bits = lastPodId and CONTROLLER_ID_BIT_MASK
        return if (low2Bits == CONTROLLER_ID_BIT_MASK) {
            controllerIdForPodId(lastPodId) + 1
        } else {
            lastPodId + 1
        }
    }
}
