package org.example.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.example.app.R

/**
 * Chat-list and call-list timestamps: today shows the time (the locale's 12 or 24 hour clock), yesterday shows
 * [yesterday], the last six days show the weekday name, anything older shows a short date.
 */
fun relativeTime(
    at: Instant,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
    yesterday: String,
): String {
    val day = at.atZone(zone).toLocalDate()
    val ago = ChronoUnit.DAYS.between(day, now.atZone(zone).toLocalDate())
    return when {
        ago <= 0 -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(at)
        ago == 1L -> yesterday
        ago <= 6 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(day)
    }
}

/** [relativeTime] for the current moment, device zone and locale. */
@Composable
fun relativeTimeText(at: Instant): String =
    relativeTime(at, Instant.now(), ZoneId.systemDefault(), LocalConfiguration.current.locales.get(0), stringResource(R.string.chat_yesterday))
