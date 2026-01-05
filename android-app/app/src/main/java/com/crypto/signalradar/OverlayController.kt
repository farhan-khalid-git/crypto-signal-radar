package com.crypto.signalradar

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayController(private val context: Context) {
  private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private var overlayView: View? = null
  private var panelView: View? = null
  private var collapsedView: View? = null
  private var messageView: TextView? = null
  private var layoutParams: WindowManager.LayoutParams? = null
  private var isCollapsed = false

  private val edgeSnapPx = dpToPx(32)

  fun canDraw(): Boolean = Settings.canDrawOverlays(context)

  fun show() {
    if (overlayView != null) {
      return
    }
    val inflater = LayoutInflater.from(context)
    val view = inflater.inflate(R.layout.overlay_widget, null)
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    )
    params.gravity = Gravity.TOP or Gravity.START
    params.x = 40
    params.y = 120

    panelView = view.findViewById(R.id.overlay_panel)
    collapsedView = view.findViewById(R.id.overlay_collapsed)
    messageView = view.findViewById(R.id.overlay_message)
    val openButton = view.findViewById<TextView>(R.id.overlay_open)
    val closeButton = view.findViewById<TextView>(R.id.overlay_close)

    openButton.setOnClickListener {
      val intent = Intent(context, MainActivity::class.java)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }

    closeButton.setOnClickListener {
      hide()
      SignalStore.setOverlayEnabled(false)
    }

    val ignored = listOfNotNull(openButton, closeButton, collapsedView)
    val expandedDragListener = DragTouchListener(
      params,
      windowManager,
      view,
      ignored,
      dragThresholdPx = dpToPx(6),
      onRelease = { handleExpandedRelease() },
    )

    val collapsedDragListener = DragTouchListener(
      params,
      windowManager,
      view,
      emptyList(),
      dragThresholdPx = dpToPx(4),
      onTap = { expandPanel() },
      onRelease = { handleCollapsedRelease() },
    )

    view.setOnTouchListener(expandedDragListener)
    collapsedView?.setOnTouchListener(collapsedDragListener)

    windowManager.addView(view, params)
    overlayView = view
    layoutParams = params
    setCollapsed(false)
  }

  fun hide() {
    overlayView?.let {
      windowManager.removeView(it)
    }
    overlayView = null
    panelView = null
    collapsedView = null
    messageView = null
    layoutParams = null
    isCollapsed = false
  }

  fun updateMessage(text: String) {
    messageView?.text = text
  }

  private fun expandPanel() {
    if (!isCollapsed) {
      return
    }
    setCollapsed(false)
  }

  private fun handleExpandedRelease() {
    val panel = panelView ?: return
    val params = layoutParams ?: return
    val viewWidth = panel.width
    if (viewWidth == 0) {
      panel.post { handleExpandedRelease() }
      return
    }
    val screenWidth = screenWidth()
    val leftSnap = params.x <= edgeSnapPx
    val rightSnap = params.x + viewWidth >= screenWidth - edgeSnapPx
    if (leftSnap || rightSnap) {
      setCollapsed(true, if (rightSnap) DockSide.RIGHT else DockSide.LEFT)
    } else {
      clampToBounds(panel)
    }
  }

  private fun handleCollapsedRelease() {
    val bubble = collapsedView ?: return
    val params = layoutParams ?: return
    val viewWidth = bubble.width
    if (viewWidth == 0) {
      bubble.post { handleCollapsedRelease() }
      return
    }
    val screenWidth = screenWidth()
    val side = if (params.x + viewWidth / 2 >= screenWidth / 2) DockSide.RIGHT else DockSide.LEFT
    snapToEdge(bubble, side)
  }

  private fun setCollapsed(collapsed: Boolean, side: DockSide? = null) {
    val panel = panelView ?: return
    val bubble = collapsedView ?: return
    isCollapsed = collapsed
    if (collapsed) {
      panel.visibility = View.GONE
      bubble.visibility = View.VISIBLE
      bubble.post { snapToEdge(bubble, side ?: resolveSide(bubble)) }
    } else {
      bubble.visibility = View.GONE
      panel.visibility = View.VISIBLE
      panel.post { clampToBounds(panel) }
    }
  }

  private fun resolveSide(view: View): DockSide {
    val params = layoutParams ?: return DockSide.LEFT
    val screenWidth = screenWidth()
    val centerX = params.x + (view.width / 2)
    return if (centerX >= screenWidth / 2) DockSide.RIGHT else DockSide.LEFT
  }

  private fun snapToEdge(view: View, side: DockSide) {
    val params = layoutParams ?: return
    val width = view.width
    if (width == 0) {
      view.post { snapToEdge(view, side) }
      return
    }
    val screenWidth = screenWidth()
    params.x = if (side == DockSide.RIGHT) {
      (screenWidth - width).coerceAtLeast(0)
    } else {
      0
    }
    clampToBounds(view)
  }

  private fun clampToBounds(view: View) {
    val params = layoutParams ?: return
    val width = view.width
    val height = view.height
    if (width == 0 || height == 0) {
      view.post { clampToBounds(view) }
      return
    }
    val maxX = (screenWidth() - width).coerceAtLeast(0)
    val maxY = (screenHeight() - height).coerceAtLeast(0)
    params.x = params.x.coerceIn(0, maxX)
    params.y = params.y.coerceIn(0, maxY)
    overlayView?.let { windowManager.updateViewLayout(it, params) }
  }

  private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

  private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

  private fun dpToPx(dp: Int): Int {
    return (dp * context.resources.displayMetrics.density).roundToInt()
  }

  private enum class DockSide {
    LEFT,
    RIGHT,
  }
}

private class DragTouchListener(
  private val params: WindowManager.LayoutParams,
  private val windowManager: WindowManager,
  private val updateTarget: View,
  private val ignoredViews: List<View>,
  private val dragThresholdPx: Int,
  private val onTap: (() -> Unit)? = null,
  private val onRelease: (() -> Unit)? = null,
) : View.OnTouchListener {
  private var initialX = 0
  private var initialY = 0
  private var initialTouchX = 0f
  private var initialTouchY = 0f
  private var dragging = false

  override fun onTouch(view: View, event: MotionEvent): Boolean {
    return when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        if (shouldIgnore(event)) {
          dragging = false
          return false
        }
        initialX = params.x
        initialY = params.y
        initialTouchX = event.rawX
        initialTouchY = event.rawY
        dragging = false
        true
      }
      MotionEvent.ACTION_MOVE -> {
        val deltaX = event.rawX - initialTouchX
        val deltaY = event.rawY - initialTouchY
        if (!dragging && (abs(deltaX) > dragThresholdPx || abs(deltaY) > dragThresholdPx)) {
          dragging = true
        }
        if (!dragging) {
          return true
        }
        params.x = initialX + deltaX.toInt()
        params.y = initialY + deltaY.toInt()
        windowManager.updateViewLayout(updateTarget, params)
        true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (!dragging) {
          onTap?.invoke()
        } else {
          onRelease?.invoke()
        }
        dragging = false
        true
      }
      else -> false
    }
  }

  private fun shouldIgnore(event: MotionEvent): Boolean {
    val x = event.rawX.toInt()
    val y = event.rawY.toInt()
    return ignoredViews.any { view ->
      if (view.width == 0 || view.height == 0) {
        false
      } else {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + view.width
        val bottom = top + view.height
        x in left..right && y in top..bottom
      }
    }
  }
}
