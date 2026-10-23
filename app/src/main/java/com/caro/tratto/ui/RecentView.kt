package com.caro.tratto.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "oggi, 10:32", "ieri, 18:05", altrimenti la data. */
internal fun whenText(millis: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    val hm = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(millis))
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(day, today)) return "oggi, $hm"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(day, today)) return "ieri, $hm"
    return formatDate(millis)
}
