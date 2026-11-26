package io.github.lucateam_wt.tumbler.data.crypto

/** Whether new secrets can be sealed, as [DataKeyHolder.writeKeyStatus] finds it without creating anything. */
enum class WriteKeyStatus {

    /** No data key exists and no account names one (a fresh install): the first write creates version 1. */
    NOT_CREATED,

    /** The newest data key is unwrapped: writes use it. */
    READY,

    /**
     * The newest key cannot be used for good (wrapping key gone or invalidated, record does not
     * unwrap), or accounts name a version that has no record. Writes are refused until the user
     * confirms [DataKeyHolder.startNewKey].
     */
    NEW_KEY_REQUIRED,

    /** The Keystore failed in a way that may be transient. Nothing is cached or created; try again later. */
    TEMPORARILY_UNAVAILABLE,
}
