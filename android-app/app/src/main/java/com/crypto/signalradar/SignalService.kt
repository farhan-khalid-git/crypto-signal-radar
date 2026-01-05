package com.crypto.signalradar

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class SignalService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val client = OkHttpClient()
  private var webSocket: WebSocket? = null
  private var engine: SignalEngine? = null
  private var config: SignalConfig? = null
  private var socketConnected = false
  private var socketConnecting = false
  private var reconnectJob: Job? = null
  private var snapshots = mutableMapOf<String, SymbolSnapshot>()
  private var toneGenerator: ToneGenerator? = null
  private lateinit var notificationHelper: NotificationHelper
  private lateinit var overlayController: OverlayController
  private lateinit var connectivityManager: ConnectivityManager
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var hasNetwork = true

  override fun onCreate() {
    super.onCreate()
    notificationHelper = NotificationHelper(this)
    notificationHelper.ensureChannels()
    overlayController = OverlayController(this)
    connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    registerNetworkCallback()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val newConfig = intent.getParcelableExtra<SignalConfig>(EXTRA_CONFIG)
        if (newConfig != null) {
          startMonitoring(newConfig)
        }
      }
      ACTION_STOP -> {
        stopMonitoring()
      }
      ACTION_OVERLAY -> {
        val enabled = intent.getBooleanExtra(EXTRA_OVERLAY, false)
        setOverlayEnabled(enabled)
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    unregisterNetworkCallback()
    stopMonitoring()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startMonitoring(newConfig: SignalConfig) {
    config = newConfig
    engine = SignalEngine(newConfig)
    socketConnected = false
    socketConnecting = false
    snapshots = newConfig.symbols.associateWith { symbol ->
      SymbolSnapshot(symbol, null, null, newConfig.windows.associateWith { null })
    }.toMutableMap()

    SignalStore.setConfig(newConfig)
    SignalStore.updateSymbols(snapshots.values.sortedBy { it.symbol })
    SignalStore.clearAlerts()

    val notification = notificationHelper.buildForegroundNotification("Starting stream...")
    startForeground(FOREGROUND_ID, notification)

    setOverlayEnabled(newConfig.overlayEnabled)
    if (hasNetwork) {
      connectSocket(force = true)
    } else {
      SignalStore.updateStatus(StatusState(ConnectionState.ERROR, "Waiting for network...", null))
      updateForeground("Waiting for network...")
    }
  }

  private fun stopMonitoring() {
    reconnectJob?.cancel()
    reconnectJob = null
    webSocket?.close(1000, "Stopped")
    webSocket = null
    engine = null
    socketConnected = false
    socketConnecting = false
    toneGenerator?.release()
    toneGenerator = null
    overlayController.hide()
    SignalStore.setOverlayEnabled(false)
    SignalStore.setConfig(null)
    SignalStore.updateStatus(StatusState(ConnectionState.IDLE, "Monitoring stopped", null))
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun connectSocket(force: Boolean = false) {
    if (!force && (socketConnected || socketConnecting)) {
      return
    }
    val activeConfig = config ?: return
    socketConnecting = true
    val streamList = activeConfig.symbols.joinToString("/") { "${it.lowercase()}@trade" }
    val url = "wss://stream.binance.com:9443/stream?streams=$streamList"
    val request = Request.Builder().url(url).build()

    webSocket?.close(1000, "Reconnect")
    webSocket = client.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        socketConnected = true
        socketConnecting = false
        SignalStore.updateStatus(StatusState(ConnectionState.LIVE, "Streaming prices", null))
        updateForeground("Streaming prices")
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        val payload = JSONObject(text)
        val data = payload.optJSONObject("data") ?: return
        val symbol = data.optString("s")
        val price = data.optString("p").toDoubleOrNull() ?: return
        if (symbol.isBlank()) {
          return
        }
        val now = System.currentTimeMillis()
        SignalStore.updateStatus(StatusState(ConnectionState.LIVE, "Streaming prices", now))

        val result = engine?.update(symbol, price, now) ?: return
        snapshots[symbol] = result.snapshot
        SignalStore.updateSymbols(snapshots.values.sortedBy { it.symbol })

        result.alerts.forEach { alert ->
          handleAlert(alert)
        }
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        socketConnected = false
        socketConnecting = false
        SignalStore.updateStatus(StatusState(ConnectionState.ERROR, "Connection lost. Reconnecting...", null))
        updateForeground("Reconnecting...")
        scheduleReconnect()
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        socketConnected = false
        socketConnecting = false
        if (engine != null) {
          SignalStore.updateStatus(StatusState(ConnectionState.ERROR, "Disconnected. Reconnecting...", null))
          updateForeground("Reconnecting...")
          scheduleReconnect()
        }
      }
    })
  }

  private fun scheduleReconnect() {
    if (reconnectJob?.isActive == true) {
      return
    }
    reconnectJob = scope.launch {
      delay(3_000L)
      if (engine != null && hasNetwork) {
        connectSocket()
      }
    }
  }

  private fun updateForeground(text: String) {
    val notification = notificationHelper.buildForegroundNotification(text)
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    manager.notify(FOREGROUND_ID, notification)
  }

  private fun handleAlert(alert: AlertEntry) {
    SignalStore.pushAlert(alert)
    notificationHelper.notifyAlert(alert)
    val message = "${alert.symbol} ${PercentFormatter.format(alert.changePercent)} in ${formatWindowLabel(alert.windowMinutes)}"
    overlayController.updateMessage(message)
    if (config?.soundEnabled == true) {
      if (toneGenerator == null) {
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
      }
      toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }
  }

  private fun setOverlayEnabled(enabled: Boolean) {
    val canDraw = overlayController.canDraw()
    if (enabled && canDraw) {
      overlayController.show()
      SignalStore.setOverlayEnabled(true)
    } else {
      overlayController.hide()
      SignalStore.setOverlayEnabled(false)
    }
  }

  private fun registerNetworkCallback() {
    if (networkCallback != null) {
      return
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        hasNetwork = true
        if (engine != null) {
          connectSocket()
        }
      }

      override fun onLost(network: Network) {
        hasNetwork = false
        socketConnected = false
        socketConnecting = false
        webSocket?.cancel()
        webSocket = null
        if (engine != null) {
          SignalStore.updateStatus(StatusState(ConnectionState.ERROR, "Waiting for network...", null))
          updateForeground("Waiting for network...")
        }
      }
    }
    networkCallback = callback
    try {
      connectivityManager.registerDefaultNetworkCallback(callback)
    } catch (_: Exception) {
      networkCallback = null
    }
  }

  private fun unregisterNetworkCallback() {
    val callback = networkCallback ?: return
    try {
      connectivityManager.unregisterNetworkCallback(callback)
    } catch (_: Exception) {
      // Ignore cleanup errors.
    } finally {
      networkCallback = null
    }
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

  companion object {
    private const val FOREGROUND_ID = 101
    private const val ACTION_START = "com.crypto.signalradar.action.START"
    private const val ACTION_STOP = "com.crypto.signalradar.action.STOP"
    private const val ACTION_OVERLAY = "com.crypto.signalradar.action.OVERLAY"
    private const val EXTRA_CONFIG = "com.crypto.signalradar.extra.CONFIG"
    private const val EXTRA_OVERLAY = "com.crypto.signalradar.extra.OVERLAY"

    fun start(context: Context, config: SignalConfig) {
      val intent = Intent(context, SignalService::class.java)
      intent.action = ACTION_START
      intent.putExtra(EXTRA_CONFIG, config)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, SignalService::class.java)
      intent.action = ACTION_STOP
      context.startService(intent)
    }

    fun setOverlay(context: Context, enabled: Boolean) {
      val intent = Intent(context, SignalService::class.java)
      intent.action = ACTION_OVERLAY
      intent.putExtra(EXTRA_OVERLAY, enabled)
      context.startService(intent)
    }
  }
}
