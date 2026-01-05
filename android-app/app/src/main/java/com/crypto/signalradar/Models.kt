package com.crypto.signalradar

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class ConnectionState {
  IDLE,
  LIVE,
  ERROR,
}

enum class Direction {
  UP,
  DOWN,
}

@Parcelize
data class SignalConfig(
  val symbols: List<String>,
  val quote: String,
  val windows: List<Int>,
  val thresholds: Map<Int, Double>,
  val cooldownSeconds: Int,
  val soundEnabled: Boolean,
  val overlayEnabled: Boolean,
) : Parcelable

data class StatusState(
  val state: ConnectionState,
  val message: String,
  val lastTickAt: Long?,
)

data class SymbolSnapshot(
  val symbol: String,
  val price: Double?,
  val lastTickAt: Long?,
  val changes: Map<Int, Double?>,
)

data class AlertEntry(
  val symbol: String,
  val windowMinutes: Int,
  val changePercent: Double,
  val price: Double,
  val direction: Direction,
  val timestamp: Long,
)
