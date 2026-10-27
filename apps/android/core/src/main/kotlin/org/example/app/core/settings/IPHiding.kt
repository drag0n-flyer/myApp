package org.example.app.core.settings

/** "Hide my IP address in calls". */
enum class IPHiding {
    Never, NonContacts, Always;

    val titleKey: String
        get() = when (this) {
            Never -> "ip_hiding_never"
            NonContacts -> "ip_hiding_non_contacts"
            Always -> "ip_hiding_always"
        }

    val subtitleKey: String
        get() = when (this) {
            Never -> "ip_hiding_never_subtitle"
            NonContacts -> "ip_hiding_non_contacts_subtitle"
            Always -> "ip_hiding_always_subtitle"
        }

    /** Whether a call with this person must go through the relay server. */
    fun requiresRelay(isContact: Boolean): Boolean = when (this) {
        Never -> false
        NonContacts -> !isContact
        Always -> true
    }
}
