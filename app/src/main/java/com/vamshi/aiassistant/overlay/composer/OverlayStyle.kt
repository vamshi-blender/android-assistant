package com.vamshi.aiassistant.overlay.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared surface tokens for the overlay panel.
 *
 * The conversation bubble and the composer are separate components but must
 * read as one system, so both take their background, border and corner radius
 * from here rather than each defining its own.
 */
internal object OverlayStyle {
    val Background = Color(0xFF1C1C1E)
    val Border = Color(0x33FFFFFF)
    val Corner = 22.dp

    val ButtonBackground = Color(0x22FFFFFF)
    val AccentBackground = Color(0xFFE8E8E8)
    val Placeholder = Color(0x80FFFFFF)
}
