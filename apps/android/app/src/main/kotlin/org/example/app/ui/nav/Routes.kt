package org.example.app.ui.nav

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.example.app.R

/** The four bottom tabs, in Telegram order. */
enum class MainTab(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    Contacts("contacts", R.string.tab_contacts, R.drawable.ic_contacts),
    Calls("calls", R.string.tab_calls, R.drawable.ic_calls),
    Chats("chats", R.string.tab_chats, R.drawable.ic_chats),
    Settings("settings", R.string.tab_settings, R.drawable.ic_settings);

    companion object {
        val start = Chats
        fun fromRoute(route: String?): MainTab? = entries.firstOrNull { it.route == route }
    }
}
