package com.crypto.signalradar

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PriceFormatter {
  fun format(value: Double?): String {
    if (value == null || value.isNaN()) {
      return "--"
    }
    return when {
      value >= 1000 -> String.format(Locale.US, "%.2f", value)
      value >= 1 -> String.format(Locale.US, "%.4f", value)
      else -> String.format(Locale.US, "%.6f", value)
    }
  }
}

object PercentFormatter {
  fun format(value: Double?): String {
    if (value == null || value.isNaN()) {
      return "--"
    }
    val sign = if (value > 0) "+" else ""
    return String.format(Locale.US, "%s%.2f%%", sign, value)
  }
}

object TimeFormatter {
  private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

  fun format(value: Long?): String {
    return if (value == null) "--" else format.format(Date(value))
  }
}
