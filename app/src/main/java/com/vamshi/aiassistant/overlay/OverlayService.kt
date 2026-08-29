package com.vamshi.aiassistant.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vamshi.aiassistant.ui.theme.AIAssistantTheme

class OverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    override val viewModelStore = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var homeWatcher: BroadcastReceiver? = null

    /** Guards against stopSelf being called from several dismiss paths at once. */
    private var closing = false

    override fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        showOverlay()
    }

    private fun dismiss() {
        if (closing) return
        closing = true
        stopSelf()
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        // The window covers the full screen so it fully owns touch input while
        // shown - nothing behind it is clickable until the overlay is closed.
        //
        // It is deliberately focusable: FLAG_NOT_FOCUSABLE would stop the
        // composer from ever taking focus, so no soft keyboard would appear and
        // the text field could not be typed into. FLAG_LAYOUT_IN_SCREEN is
        // dropped for the same reason - it prevents ADJUST_RESIZE from shrinking
        // the window, which is what lifts the composer above the keyboard.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val composeView = ComposeView(this).apply {
            setContent {
                AIAssistantTheme {
                    OverlayContent(onClose = { dismiss() })
                }
            }
        }

        // Back has to be caught above the Compose hierarchy. A key listener on
        // the ComposeView itself is not enough: once the composer's text field
        // holds focus, ViewGroup.dispatchKeyEvent hands the event straight to
        // that child and never consults the parent's listener. The root sees it
        // first, so Back works whether or not the user was typing.
        val root = DismissibleRoot(this, onBack = ::dismiss).apply {
            // The owners must live on the window's ROOT view: Compose resolves
            // its recomposer by looking them up from the root, not from the
            // ComposeView, so setting them on the child alone throws
            // "ViewTreeLifecycleOwner not found" the moment the view attaches.
            // Children still find them by walking up the tree.
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        overlayView = root
        wm.addView(root, params)
        registerHomeWatcher()
    }

    /**
     * Closes the overlay when Home or Recents is pressed.
     *
     * Neither is reachable any other way: KEYCODE_HOME is swallowed by the
     * system and never dispatched to app windows, and watching for window-focus
     * loss does not work either - a TYPE_APPLICATION_OVERLAY window floats
     * *above* the launcher, so it keeps focus and the launcher simply appears
     * behind it. The system's own "close system dialogs" broadcast is the one
     * signal that does arrive.
     *
     * Only homekey/recentapps are acted on. The same broadcast is also sent for
     * "globalactions" - the power-button long-press that launches this overlay
     * in the first place - so reacting to every reason risks closing the panel
     * as it opens.
     */
    private fun registerHomeWatcher() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val reason = intent?.getStringExtra(SYSTEM_DIALOG_REASON) ?: return
                if (reason == REASON_HOME || reason == REASON_RECENTS) dismiss()
            }
        }
        homeWatcher = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        homeWatcher?.let { runCatching { unregisterReceiver(it) } }
        homeWatcher = null
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayView = null
        super.onDestroy()
    }

    private companion object {
        const val SYSTEM_DIALOG_REASON = "reason"
        const val REASON_HOME = "homekey"
        const val REASON_RECENTS = "recentapps"
    }
}

/**
 * Root container that closes the overlay on Back before the event can reach
 * the Compose hierarchy.
 *
 * Note that when the soft keyboard is open the IME consumes the first Back to
 * dismiss itself, so the overlay closes on the second - the same two-step
 * users already expect from any text field.
 */
private class DismissibleRoot(
    context: Context,
    private val onBack: () -> Unit,
) : FrameLayout(context) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
