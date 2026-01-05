package com.crypto.signalradar

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class PricePoint(val timeMs: Long, val price: Double)

private class SymbolState {
  val fastPoints = ArrayDeque<PricePoint>()
  val slowPoints = ArrayDeque<PricePoint>()
  var lastPrice: Double? = null
  var lastTickAt: Long? = null
  var lastFastRecordAt: Long = 0
  var lastSlowRecordAt: Long = 0
  val lastAlertAt = mutableMapOf<String, Long>()
  val lastAlertStep = mutableMapOf<String, Int>()
}

data class ChangeStats(
  val strongest: Double,
  val up: Double,
  val down: Double,
)

class SignalEngine(private val config: SignalConfig) {
  private val fastWindowLimitMinutes = 60
  private val fastRecordMs = 1_000L
  private val slowRecordMs = 60_000L
  private val windowMs = config.windows.associateWith { it * 60_000L }
  private val maxFastWindowMs = config.windows.filter { it <= fastWindowLimitMinutes }
    .maxOrNull()?.times(60_000L) ?: 0L
  private val maxSlowWindowMs = config.windows.filter { it > fastWindowLimitMinutes }
    .maxOrNull()?.times(60_000L) ?: 0L
  private val states = mutableMapOf<String, SymbolState>()

  init {
    config.symbols.forEach { symbol ->
      states[symbol] = SymbolState()
    }
  }

  fun update(symbol: String, price: Double, now: Long): UpdateResult? {
    val state = states.getOrPut(symbol) { SymbolState() }
    state.lastPrice = price
    state.lastTickAt = now

    if (maxFastWindowMs > 0 && now - state.lastFastRecordAt >= fastRecordMs) {
      state.fastPoints.addLast(PricePoint(now, price))
      state.lastFastRecordAt = now
      while (state.fastPoints.isNotEmpty() && now - state.fastPoints.first().timeMs > maxFastWindowMs) {
        state.fastPoints.removeFirst()
      }
    }

    if (maxSlowWindowMs > 0 && now - state.lastSlowRecordAt >= slowRecordMs) {
      state.slowPoints.addLast(PricePoint(now, price))
      state.lastSlowRecordAt = now
      while (state.slowPoints.isNotEmpty() && now - state.slowPoints.first().timeMs > maxSlowWindowMs) {
        state.slowPoints.removeFirst()
      }
    }

    val changes = mutableMapOf<Int, Double?>()
    val alerts = mutableListOf<AlertEntry>()

    for (window in config.windows) {
      val points = if (window > fastWindowLimitMinutes) state.slowPoints else state.fastPoints
      val change = computeChange(points, now, windowMs[window] ?: 0L, price)
      if (change == null) {
        changes[window] = null
        continue
      }

      changes[window] = change.strongest
      val threshold = config.thresholds[window] ?: continue
      val upStep = if (change.up >= threshold) (change.up / threshold).toInt() else 0
      val downStep = if (change.down <= -threshold) (abs(change.down) / threshold).toInt() else 0

      val upKey = "${window}-${Direction.UP}"
      val downKey = "${window}-${Direction.DOWN}"
      if (upStep == 0) {
        state.lastAlertStep[upKey] = 0
      }
      if (downStep == 0) {
        state.lastAlertStep[downKey] = 0
      }

      val direction = when {
        upStep > 0 && downStep > 0 -> if (abs(change.up) >= abs(change.down)) Direction.UP else Direction.DOWN
        upStep > 0 -> Direction.UP
        downStep > 0 -> Direction.DOWN
        else -> null
      } ?: continue

      val step = if (direction == Direction.UP) upStep else downStep
      val changeValue = if (direction == Direction.UP) change.up else change.down
      val alertKey = "${window}-${direction}"
      val lastStep = state.lastAlertStep[alertKey] ?: 0
      if (step <= lastStep) {
        continue
      }

      val lastAlert = state.lastAlertAt[alertKey]
      if (lastAlert == null || now - lastAlert >= config.cooldownSeconds * 1000L) {
        state.lastAlertAt[alertKey] = now
        state.lastAlertStep[alertKey] = step
        alerts.add(
          AlertEntry(
            symbol = symbol,
            windowMinutes = window,
            changePercent = changeValue,
            price = price,
            direction = direction,
            timestamp = now,
          )
        )
      }
    }

    return UpdateResult(
      SymbolSnapshot(
        symbol = symbol,
        price = price,
        lastTickAt = state.lastTickAt,
        changes = changes,
      ),
      alerts,
    )
  }

  private fun computeChange(
    points: ArrayDeque<PricePoint>,
    now: Long,
    windowMs: Long,
    currentPrice: Double,
  ): ChangeStats? {
    if (windowMs <= 0) {
      return null
    }
    var maxPrice = currentPrice
    var minPrice = currentPrice
    for (point in points.reversed()) {
      if (now - point.timeMs > windowMs) {
        break
      }
      maxPrice = max(maxPrice, point.price)
      minPrice = min(minPrice, point.price)
    }
    if (maxPrice <= 0 || minPrice <= 0) {
      return null
    }
    val down = ((currentPrice - maxPrice) / maxPrice) * 100
    val up = ((currentPrice - minPrice) / minPrice) * 100
    val strongest = if (abs(up) >= abs(down)) up else down
    return ChangeStats(strongest = strongest, up = up, down = down)
  }
}

data class UpdateResult(
  val snapshot: SymbolSnapshot,
  val alerts: List<AlertEntry>,
)
