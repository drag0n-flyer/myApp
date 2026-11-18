package org.example.app.core.people

import org.example.app.core.profile.DisplayNameRule

/** The one rule for the name shown for a contact: everywhere, the chat list, titles, system lines, notifications and calls. */
object ContactDisplay {
    /**
     * The first of [nickname] (a name the person saved locally, if that feature exists), [profileName] (what the contact
     * says about themselves) and [lookupName] (from the server, falling back to [username]) that is visible after
     * [DisplayNameRule.forDisplay]. Every one is cleaned first.
     */
    fun name(nickname: String?, profileName: String?, lookupName: String, username: String): String {
        for (candidate in listOf(nickname, profileName)) {
            if (candidate == null) continue
            val shown = DisplayNameRule.forDisplay(candidate, "")
            if (shown.isNotEmpty()) return shown
        }
        return DisplayNameRule.forDisplay(lookupName, username)
    }
}
