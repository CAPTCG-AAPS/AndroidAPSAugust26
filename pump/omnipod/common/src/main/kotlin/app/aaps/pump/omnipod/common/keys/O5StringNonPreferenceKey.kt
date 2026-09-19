package app.aaps.pump.omnipod.common.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * Preference key(s) for persisting Omnipod 5 pod state, mirroring [DashStringNonPreferenceKey].
 */
enum class O5StringNonPreferenceKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    PodState("AAPS.Omnipod5.pod_state", ""),

    /** Holds Keystore-encrypted O5 registration (credential) data - never exported, since
     *  unlike pod connection state, this contains actual key material. */
    RegistrationData("AAPS.Omnipod5.registration_data_encrypted", "", exportable = false),

    /** Non-empty once the build-time embedded credential has been seeded on this install, so
     *  it is seeded at most once and a later Remove sticks. Cleared only by a fresh install. */
    EmbeddedCredentialSeeded("AAPS.Omnipod5.embedded_credential_seeded", "", exportable = false),

    /** A random id made once per install, sent when downloading a credential with a token so
     *  the server can bind that token to this one phone. Never exported. */
    CredentialClaimDeviceId("AAPS.Omnipod5.credential_claim_device_id", "", exportable = false),
}
