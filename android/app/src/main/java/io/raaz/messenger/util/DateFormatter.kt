package io.raaz.messenger.util

import android.content.Context
import saman.zamani.persiandate.PersianDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatter {

    fun formatMessageTime(context: Context, timestampMs: Long): String {
        return if (LocaleManager.getLanguage(context) == LocaleManager.LANG_FA) {
            formatJalali(timestampMs, onlyTime = true)
        } else {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            sdf.format(Date(timestampMs))
        }
    }

    fun formatMessageDate(context: Context, timestampMs: Long): String {
        return if (LocaleManager.getLanguage(context) == LocaleManager.LANG_FA) {
            formatJalali(timestampMs, onlyTime = false)
        } else {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestampMs))
        }
    }

    fun formatFull(context: Context, timestampMs: Long): String {
        return if (LocaleManager.getLanguage(context) == LocaleManager.LANG_FA) {
            formatJalali(timestampMs, onlyTime = false, full = true)
        } else {
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            sdf.format(Date(timestampMs))
        }
    }

    fun formatLockoutTime(context: Context, timestampMs: Long): String {
        return formatMessageTime(context, timestampMs)
    }

    private fun formatJalali(timestampMs: Long, onlyTime: Boolean, full: Boolean = false): String {
        val pd = PersianDate(timestampMs)
        return if (onlyTime) {
            "%02d:%02d".format(pd.hour, pd.minute)
        } else if (full) {
            "${pd.shYear}/${"%02d".format(pd.shMonth)}/${"%02d".format(pd.shDay)} %02d:%02d".format(pd.hour, pd.minute)
        } else {
            "${pd.shMonth}/${pd.shDay}"
        }
    }

    fun isSameDay(ts1: Long, ts2: Long): Boolean {
        val d1 = Date(ts1)
        val d2 = Date(ts2)
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return sdf.format(d1) == sdf.format(d2)
    }
}
