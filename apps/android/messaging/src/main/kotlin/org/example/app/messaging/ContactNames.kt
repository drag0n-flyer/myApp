package org.example.app.messaging

import org.example.app.core.people.ContactDisplay
import org.example.app.messaging.ports.ContactRecord
import org.example.app.messaging.ports.Repository

/**
 * The name shown for a contact in a notification title or a call, read straight from the database: the same rule
 * ([ContactDisplay]) and the same exclusions as the chat list. The contact's profile name counts only while the account
 * is not blocked and its chat is not a message request.
 */
object ContactNames {
    fun shown(repo: Repository, row: ContactRecord): String {
        val hidden = repo.isBlocked(row.accountId) || repo.chat(row.accountId)?.isRequest == true
        val profileName = if (hidden) null else repo.profile(row.accountId)?.name
        return ContactDisplay.name(null, profileName, row.displayName, row.username)
    }
}
