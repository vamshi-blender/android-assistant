package com.vamshi.aiassistant.overlay.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the composer becomes for the duration of a live voice session, in place
 * of [MessageComposer]: end on the left, a center pill spanning the remaining
 * width showing connection status, and mute on the right. The conversation card above it is
 * unaffected; live turns are appended to it exactly like a normal exchange.
 */
@Composable
fun LiveComposerBar(
    muted: Boolean,
    status: String,
    onMuteToggle: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(OverlayStyle.Background, RoundedCornerShape(OverlayStyle.Corner))
            .border(1.dp, OverlayStyle.Border, RoundedCornerShape(OverlayStyle.Corner))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LiveBarButton(label = "✕", onClick = onEnd)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(INPUT_MIN_HEIGHT)
                    .background(OverlayStyle.ButtonBackground, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = status, color = Color.White, fontSize = 14.sp)
            }

            LiveBarButton(label = "M", accent = muted, onClick = onMuteToggle)
        }
    }
}

@Composable
private fun LiveBarButton(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(BUTTON_SIZE)
            .background(
                if (accent) OverlayStyle.AccentBackground else OverlayStyle.ButtonBackground,
                CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (accent) Color.Black else Color.White,
            fontSize = 15.sp
        )
    }
}

private val BUTTON_SIZE = 36.dp
private val INPUT_MIN_HEIGHT = 36.dp
