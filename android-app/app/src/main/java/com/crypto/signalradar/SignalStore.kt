package com.crypto.signalradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SignalStore {
  private val _status = MutableStateFlow(StatusState(ConnectionState.IDLE, "Waiting for start", null))
  private val _symbols = MutableStateFlow<List<SymbolSnapshot>>(emptyList())
  private val _alerts = MutableStateFlow<List<AlertEntry>>(emptyList())
  private val _overlayEnabled = MutableStateFlow(false)
  private val _config = MutableStateFlow<SignalConfig?>(null)

  val status = _status.asStateFlow()
  val symbols = _symbols.asStateFlow()
  val alerts = _alerts.asStateFlow()
  val overlayEnabled = _overlayEnabled.asStateFlow()
  val config = _config.asStateFlow()

  fun updateStatus(state: StatusState) {
    _status.value = state
  }

  fun updateSymbols(list: List<SymbolSnapshot>) {
    _symbols.value = list
  }

  fun pushAlert(alert: AlertEntry) {
    val updated = listOf(alert) + _alerts.value
    _alerts.value = updated.take(40)
  }

  fun clearAlerts() {
    _alerts.value = emptyList()
  }

  fun setOverlayEnabled(enabled: Boolean) {
    _overlayEnabled.value = enabled
  }

  fun setConfig(config: SignalConfig?) {
    _config.value = config
  }
}
