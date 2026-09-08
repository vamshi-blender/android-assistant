package com.vamshi.aiassistant.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Requests RECORD_AUDIO on behalf of the overlay.
 *
 * The overlay lives in a TYPE_APPLICATION_OVERLAY window owned by a Service,
 * and the permission APIs all require an Activity - so tapping the mic there
 * could never prompt, and [MicAmplitudeSource] silently fell back to synthetic
 * levels instead. This activity is invisible: it exists only to host the
 * prompt, publishes the outcome on [results], and finishes immediately.
 */
class MicPermissionActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        results.tryEmit(granted)
        finish()
        // No animation, so the overlay does not visibly flicker behind it.
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        /**
         * Grant results, delivered to whoever is waiting in the overlay.
         *
         * extraBufferCapacity keeps [MutableSharedFlow.tryEmit] from dropping
         * the result when the collector has not resumed yet.
         */
        val results = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        fun request(context: Context) {
            context.startActivity(
                Intent(context, MicPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
