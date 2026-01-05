package com.crypto.signalradar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.crypto.signalradar.ui.theme.SignalTheme
import kotlinx.coroutines.delay

private const val DEFAULT_SYMBOLS = "USDT, BTC, GIGGLE, SOL, ETH, DOT, BNB, DOGE, XRP, FORM"
private const val DEFAULT_QUOTE = "USDT"
private const val DEFAULT_THRESHOLD = "2.5"
private const val DEFAULT_COOLDOWN = 30
private const val BLINK_DURATION_MS = 2500L

private data class WindowOption(val minutes: Int, val label: String)

private val WINDOW_OPTIONS = listOf(
  WindowOption(1, "1m"),
  WindowOption(5, "5m"),
  WindowOption(15, "15m"),
  WindowOption(60, "1h"),
  WindowOption(1440, "1d"),
  WindowOption(10080, "1w"),
  WindowOption(43200, "1mo"),
)

private val DEFAULT_THRESHOLDS = mapOf(
  1 to "0.5",
  5 to "1.0",
  15 to "1.5",
  60 to "2.5",
  1440 to "4.0",
  10080 to "6.0",
  43200 to "10.0",
)

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SignalTheme {
        RadarScreen()
      }
    }
  }
}

@Composable
private fun RadarScreen() {
  val context = LocalContext.current
  val status by SignalStore.status.collectAsState()
  val symbols by SignalStore.symbols.collectAsState()
  val alerts by SignalStore.alerts.collectAsState()
  val overlayEnabled by SignalStore.overlayEnabled.collectAsState()
  val config by SignalStore.config.collectAsState()
  val prefs = remember { context.getSharedPreferences("signal_prefs", Context.MODE_PRIVATE) }

  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  var symbolsInput by rememberSaveable { mutableStateOf(DEFAULT_SYMBOLS) }
  var quote by rememberSaveable { mutableStateOf(DEFAULT_QUOTE) }
  var thresholdInputs by remember { mutableStateOf(DEFAULT_THRESHOLDS) }
  var cooldown by rememberSaveable { mutableStateOf(DEFAULT_COOLDOWN) }
  var windows by remember { mutableStateOf(setOf(1, 5, 15)) }
  var soundEnabled by rememberSaveable { mutableStateOf(true) }
  var overlayRequested by rememberSaveable { mutableStateOf(false) }
  var overlayInfoAcknowledged by rememberSaveable {
    mutableStateOf(prefs.getBoolean("overlay_info_ack", false))
  }
  var showOverlayInfo by rememberSaveable { mutableStateOf(false) }
  var liveExpanded by rememberSaveable { mutableStateOf(false) }
  var note by rememberSaveable { mutableStateOf("") }

  val isRunning = status.state != ConnectionState.IDLE
  val activeWindows = if (isRunning && config != null) config!!.windows else windows.toList().sorted()
  val activeThresholds = if (isRunning && config != null) config!!.thresholds else emptyMap()
  val overlayToggle = if (isRunning) overlayEnabled else overlayRequested
  val lastAlertBySymbol = remember(alerts) {
    alerts.groupBy { it.symbol }
      .mapValues { entry -> entry.value.maxOf { it.timestamp } }
  }

  val notificationLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (!granted) {
      note = "Notifications are disabled. Alerts will only appear in-app."
    }
  }

  val overlayLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) {
    if (!Settings.canDrawOverlays(context)) {
      note = "Overlay permission not granted."
    }
  }

  val gradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF050F10), Color(0xFF1E535E)), startY = 20.0f, endY = 30.0f
  )

  fun applyOverlayToggle(enabled: Boolean) {
    overlayRequested = enabled
    if (enabled && !Settings.canDrawOverlays(context)) {
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
      )
      overlayLauncher.launch(intent)
      note = "Enable 'Display over other apps' to use floating panel."
    } else {
      if (isRunning) {
        SignalService.setOverlay(context, enabled)
      } else {
        overlayRequested = enabled
      }
    }
  }

  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(300)
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(gradient)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      HeaderBlock(status, symbols.size)

      CardBlock(title = "Radar Controls", subtitle = "Configure pairs, windows, and alerts.") {
        OutlinedTextField(
          value = symbolsInput,
          onValueChange = { symbolsInput = it },
          modifier = Modifier.fillMaxWidth(),
          enabled = !isRunning,
          label = { Text("Assets or pairs") },
          placeholder = { Text("BTC, ETH, SOL or BTCUSDT") },
          maxLines = 4,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          DropdownField(
            label = "Default quote",
            options = listOf("USDT", "BTC", "ETH", "BNB"),
            selected = quote,
            enabled = !isRunning,
            onSelect = { quote = it },
            modifier = Modifier.weight(1f)
          )
          DropdownField(
            label = "Cooldown",
            options = listOf("15", "30", "60"),
            selected = cooldown.toString(),
            enabled = !isRunning,
            onSelect = { cooldown = it.toInt() },
            modifier = Modifier.weight(1f)
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "Time windows & thresholds", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          WINDOW_OPTIONS.forEach { option ->
            val selected = windows.contains(option.minutes)
            val thresholdValue = thresholdInputs[option.minutes] ?: DEFAULT_THRESHOLD
            WindowThresholdRow(
              label = option.label,
              selected = selected,
              threshold = thresholdValue,
              enabled = !isRunning,
              onToggle = { windows = toggleSet(windows, option.minutes) },
              onThresholdChange = { value ->
                thresholdInputs = thresholdInputs.toMutableMap().apply {
                  this[option.minutes] = value
                }
              }
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(text = "Sound", style = MaterialTheme.typography.labelMedium)
          Switch(checked = soundEnabled, onCheckedChange = { soundEnabled = it }, enabled = !isRunning)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Button(
            onClick = {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              }
              if (windows.isEmpty()) {
                note = "Select at least one time window."
                return@Button
              }
              val thresholds = mutableMapOf<Int, Double>()
              val invalid = mutableListOf<String>()
              windows.forEach { window ->
                val raw = thresholdInputs[window] ?: DEFAULT_THRESHOLD
                val value = raw.toDoubleOrNull()
                if (value == null || value <= 0) {
                  invalid.add(formatWindowLabel(window))
                } else {
                  thresholds[window] = value
                }
              }
              if (invalid.isNotEmpty()) {
                note = "Enter valid thresholds for: ${invalid.joinToString(", ")}"
                return@Button
              }
              val parsed = SymbolParser.parse(symbolsInput, quote)
              if (parsed.symbols.isEmpty()) {
                note = "Add at least one valid pair."
                return@Button
              }
              val config = SignalConfig(
                symbols = parsed.symbols,
                quote = quote,
                windows = windows.toList().sorted(),
                thresholds = thresholds,
                cooldownSeconds = cooldown,
                soundEnabled = soundEnabled,
                overlayEnabled = overlayToggle,
              )
              note = if (parsed.ignored.isNotEmpty()) {
                "Ignored: ${parsed.ignored.joinToString(", ")}"
              } else {
                ""
              }
              SignalService.start(context, config)
            },
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
          ) {
            Text("Start")
          }
          TextButton(onClick = { SignalService.stop(context) }, enabled = isRunning) {
            Text("Stop")
          }
          TextButton(onClick = { SignalStore.clearAlerts() }) {
            Text("Clear alerts")
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(text = "Floating panel", style = MaterialTheme.typography.labelMedium)
          Switch(
            checked = overlayToggle,
            onCheckedChange = { enabled ->
              if (enabled && !overlayInfoAcknowledged) {
                showOverlayInfo = true
              } else {
                applyOverlayToggle(enabled)
              }
            }
          )
        }
        Text(
          text = "Uses Android's \"Display over other apps\" permission to show alerts on top of other apps.",
          color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
          fontSize = 12.sp
        )

        if (showOverlayInfo) {
          AlertDialog(
            onDismissRequest = { showOverlayInfo = false },
            title = { Text("Floating panel permission") },
            text = {
              Text(
                "The floating panel lets you keep price alerts visible while using other apps. " +
                  "Android requires the \"Display over other apps\" permission. " +
                  "We only show a small, movable panel and never record your screen."
              )
            },
            confirmButton = {
              TextButton(
                onClick = {
                  overlayInfoAcknowledged = true
                  prefs.edit().putBoolean("overlay_info_ack", true).apply()
                  showOverlayInfo = false
                  applyOverlayToggle(true)
                }
              ) {
                Text("Continue")
              }
            },
            dismissButton = {
              TextButton(
                onClick = {
                  showOverlayInfo = false
                  overlayRequested = false
                }
              ) {
                Text("Not now")
              }
            }
          )
        }

        if (note.isNotBlank()) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(text = note, color = MaterialTheme.colorScheme.secondary)
        }
      }

      CardBlock(
        title = "Live Signals",
        subtitle = "Percent changes update every second.",
        action = {
          TextButton(onClick = { liveExpanded = true }) {
            Text("Full screen")
          }
        }
      ) {
        if (symbols.isEmpty()) {
          Text(text = "No live data yet. Press Start to begin.")
        } else {
          SymbolsTable(symbols, activeWindows, activeThresholds, lastAlertBySymbol, now)
        }
      }

      CardBlock(title = "Alert Feed", subtitle = "Recent signals are shown here.") {
        if (alerts.isEmpty()) {
          Text(text = "No alerts yet.")
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            alerts.forEach { alert ->
              AlertCard(alert)
            }
          }
        }
      }
    }

    if (liveExpanded) {
      Dialog(
        onDismissRequest = { liveExpanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(gradient)
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Live Signals",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
              )
              TextButton(onClick = { liveExpanded = false }) {
                Text("Close")
              }
            }
            Text(
              text = "Percent changes update every second.",
              color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            if (symbols.isEmpty()) {
              Text(text = "No live data yet. Press Start to begin.")
            } else {
              SymbolsTable(symbols, activeWindows, activeThresholds, lastAlertBySymbol, now)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HeaderBlock(status: StatusState, count: Int) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = stringResource(R.string.app_name),
      fontSize = 28.sp,
      fontFamily = FontFamily.Serif,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
      text = "Alerts for your custom thresholds across your Binance spot watchlist.",
      color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      StatCard("Connection", status.state.name, status.message)
      StatCard("Watching", "$count pairs", TimeFormatter.format(status.lastTickAt))
    }
  }
}

@Composable
private fun StatCard(title: String, value: String, hint: String) {
  Card(
    colors = CardDefaults.cardColors(containerColor = Color(0xFF101E23))
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(text = title, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 12.sp)
      Text(text = value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
      Text(text = hint, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 11.sp)
    }
  }
}

@Composable
private fun CardBlock(
  title: String,
  subtitle: String,
  action: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = Color(0xFF101E23)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (action != null) {
          action()
        }
      }
      Text(text = subtitle, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
      content()
    }
  }
}

@Composable
private fun WindowChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
  val contentColor = if (selected) Color(0xFF031014) else MaterialTheme.colorScheme.onBackground

  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(999.dp))
      .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
      .background(background)
      .clickable(enabled = enabled) { onClick() }
      .padding(horizontal = 12.dp, vertical = 6.dp)
  ) {
    Text(
      text = label,
      color = contentColor,
      fontSize = 12.sp,
      modifier = Modifier.align(Alignment.Center)
    )
  }
}

@Composable
private fun WindowThresholdRow(
  label: String,
  selected: Boolean,
  threshold: String,
  enabled: Boolean,
  onToggle: () -> Unit,
  onThresholdChange: (String) -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    WindowChip(label, selected, enabled, onToggle)
    OutlinedTextField(
      value = threshold,
      onValueChange = onThresholdChange,
      enabled = enabled && selected,
      modifier = Modifier.width(92.dp),
      label = { Text("%") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
  }
}

@Composable
private fun SymbolsTable(
  symbols: List<SymbolSnapshot>,
  windows: List<Int>,
  thresholds: Map<Int, Double>,
  lastAlertBySymbol: Map<String, Long>,
  now: Long,
) {
  val scrollState = rememberScrollState()
  Column(
    modifier = Modifier.horizontalScroll(scrollState),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Row(
      modifier = Modifier.wrapContentWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      TableCell(text = "Symbol", width = 90.dp, header = true)
      TableCell(text = "Price", width = 80.dp, header = true)
      windows.forEach { window ->
        TableCell(text = formatWindowLabel(window), width = 70.dp, header = true)
      }
      TableCell(text = "Updated", width = 80.dp, header = true)
    }

    symbols.sortedBy { it.symbol }.forEach { snapshot ->
      val lastAlertAt = lastAlertBySymbol[snapshot.symbol]
      val blinkActive = lastAlertAt != null && now - lastAlertAt <= BLINK_DURATION_MS
      val blinkOn = blinkActive && ((now / 350L) % 2L == 0L)
      val blinkColor = if (blinkOn) Color(0x332EC4B6) else Color.Transparent
      Row(
        modifier = Modifier
          .wrapContentWidth()
          .drawBehind {
            if (blinkOn) {
              val radius = 12.dp.toPx()
              drawRoundRect(color = blinkColor, cornerRadius = CornerRadius(radius, radius))
            }
          },
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        TableCell(text = snapshot.symbol, width = 90.dp)
        TableCell(text = PriceFormatter.format(snapshot.price), width = 80.dp)
        windows.forEach { window ->
          val change = snapshot.changes[window]
          val threshold = thresholds[window] ?: 0.0
          val color = changeColor(change, threshold)
          TableCell(text = PercentFormatter.format(change), width = 70.dp, color = color)
        }
        TableCell(text = TimeFormatter.format(snapshot.lastTickAt), width = 80.dp)
      }
    }
  }
}

@Composable
private fun TableCell(
  text: String,
  width: Dp,
  color: Color = MaterialTheme.colorScheme.onBackground,
  header: Boolean = false,
) {
  Text(
    text = text,
    color = color,
    fontSize = if (header) 12.sp else 13.sp,
    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
    maxLines = 1,
    softWrap = false,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.width(width)
  )
}

@Composable
private fun AlertCard(alert: AlertEntry) {
  val background = if (alert.direction == Direction.UP) {
    Color(0xFF142626)
  } else {
    Color(0xFF2A1E1E)
  }
  val accent = if (alert.direction == Direction.UP) Color(0xFF3DDC97) else Color(0xFFFF6B6B)

  Card(colors = CardDefaults.cardColors(containerColor = background)) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = "${alert.symbol} ${PercentFormatter.format(alert.changePercent)} in ${formatWindowLabel(alert.windowMinutes)}",
        color = accent,
        fontWeight = FontWeight.SemiBold
      )
      Text(
        text = "Price ${PriceFormatter.format(alert.price)} | ${TimeFormatter.format(alert.timestamp)}",
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        fontSize = 12.sp
      )
    }
  }
}

@Composable
private fun DropdownField(
  label: String,
  options: List<String>,
  selected: String,
  enabled: Boolean,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  Column(modifier = modifier) {
    Text(text = label, style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Box {
      OutlinedTextField(
        value = selected,
        onValueChange = {},
        enabled = enabled,
        modifier = Modifier
          .fillMaxWidth()
          .clickable(enabled = enabled) { expanded = true },
        readOnly = true,
        trailingIcon = {
          Text(text = if (expanded) "^" else "v", fontSize = 12.sp)
        },
      )
      androidx.compose.material3.DropdownMenu(
        expanded = expanded && enabled,
        onDismissRequest = { expanded = false }
      ) {
        options.forEach { option ->
          androidx.compose.material3.DropdownMenuItem(
            text = { Text(option) },
            onClick = {
              onSelect(option)
              expanded = false
            }
          )
        }
      }
    }
  }
}

private fun toggleSet(set: Set<Int>, value: Int): Set<Int> {
  return if (set.contains(value)) set - value else set + value
}

private fun formatWindowLabel(minutes: Int): String {
  return when (minutes) {
    60 -> "1h"
    1440 -> "1d"
    10080 -> "1w"
    43200 -> "1mo"
    else -> "${minutes}m"
  }
}

@Composable
private fun changeColor(value: Double?, threshold: Double): Color {
  return when {
    value == null -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    value >= threshold -> Color(0xFF3DDC97)
    value <= -threshold -> Color(0xFFFF6B6B)
    value < 0 -> Color(0xFFF2A93B)
    value > 0 -> Color(0xFF3DDC97)
    else -> MaterialTheme.colorScheme.onBackground
  }
}
