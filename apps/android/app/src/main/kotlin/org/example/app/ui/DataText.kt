package org.example.app.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.text.NumberFormat
import java.util.Locale
import org.example.app.R
import org.example.app.core.calls.DataFormat
import org.example.app.core.calls.DataMode
import org.example.app.core.calls.DataSize
import org.example.app.core.calls.DataUnit

/** Display text for data amounts and the call clock. `:core` returns numbers; the wording and digits live here. */
object DataText {
    private fun number(value: Double, fractionDigits: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }.format(value)

    fun size(context: Context, size: DataSize, locale: Locale = Locale.getDefault()): String = when (size.unit) {
        DataUnit.KB -> context.getString(R.string.data_size_kb, number(size.value, 0, locale))
        DataUnit.MB -> context.getString(R.string.data_size_mb, number(size.value, 1, locale))
    }

    fun used(context: Context, bytes: Long, locale: Locale = Locale.getDefault()): String =
        context.getString(R.string.call_data_used, size(context, DataFormat.size(bytes), locale))

    fun estimate(context: Context, mode: DataMode, locale: Locale = Locale.getDefault()): String =
        estimate(context, mode.estimatedMBPer10Minutes, locale)

    fun estimate(context: Context, megabytes: Int, locale: Locale = Locale.getDefault()): String =
        context.getString(R.string.call_data_estimate, number(megabytes.toDouble(), 0, locale))

    /** "04:12", or "1:02:05" past an hour, with the device's digits. */
    fun clock(elapsedSeconds: Double): String = DateUtils.formatElapsedTime(elapsedSeconds.toLong())
}

@Composable
private fun deviceLocale(): Locale = LocalConfiguration.current.locales.get(0)

@Composable
fun dataSizeText(size: DataSize): String = DataText.size(LocalContext.current, size, deviceLocale())

@Composable
fun dataUsedText(bytes: Long): String = DataText.used(LocalContext.current, bytes, deviceLocale())

@Composable
fun dataEstimateText(megabytes: Int): String = DataText.estimate(LocalContext.current, megabytes, deviceLocale())
