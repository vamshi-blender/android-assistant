package com.vamshi.aiassistant.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The assistant panel itself. Shared by [OverlayService] (drawn into a
 * TYPE_APPLICATION_OVERLAY window when the device is unlocked) and
 * [AssistantOverlayActivity] (used on the lock screen, where overlay windows
 * are not allowed to draw).
 */
@Composable
fun OverlayContent(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Tapping the transparent area outside the panel closes the
            // overlay and consumes the tap - it never reaches whatever is
            // underneath, since this window owns the whole screen.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.3f)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            1.0f to Color.Black
                        )
                    )
                )
                // Swallow taps on the panel itself so they don't bubble up
                // to the outer close-on-tap modifier.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(24.dp)
        ) {
            Text(
                text = "AI Assistant",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Overlay is showing above the home screen.",
                color = Color.White
            )
            Button(
                onClick = onClose,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Close")
            }
        }
    }
}
