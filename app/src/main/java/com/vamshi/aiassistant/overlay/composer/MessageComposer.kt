package com.vamshi.aiassistant.overlay.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow

/** What the composer is currently doing. */
enum class ComposerMode { EDITING, RECORDING, TRANSCRIBING }

/**
 * Everything the composer needs to remember, including the snapshot taken when
 * recording starts so a cancel can restore the exact prior state - text and
 * whether the layout had already expanded.
 */
class ComposerState {
    var value by mutableStateOf(TextFieldValue(""))
    var mode by mutableStateOf(ComposerMode.EDITING)

    /** True when the text no longer fits one line and the layout has split in two. */
    var expanded by mutableStateOf(false)

    private var snapshotValue: TextFieldValue? = null
    private var snapshotExpanded = false

    /** Width of the single-line field, used to decide when to collapse back. */
    var collapsedFieldWidthPx by mutableFloatStateOf(0f)

    /** Drives the L -> S swap: any character counts, per the spec. */
    val hasText: Boolean get() = value.text.isNotEmpty()

    /** What would actually be sent; blank-only input is not a message. */
    val sendableText: String get() = value.text.trim()

    fun beginRecording() {
        snapshotValue = value
        snapshotExpanded = expanded
        mode = ComposerMode.RECORDING
    }

    fun confirmRecording() {
        mode = ComposerMode.TRANSCRIBING
    }

    /** Restores the exact pre-recording state; no typed text is ever lost. */
    fun cancelRecording() {
        snapshotValue?.let { value = it }
        expanded = snapshotExpanded
        snapshotValue = null
        mode = ComposerMode.EDITING
    }

    /** Appends the transcript to whatever was already typed. */
    fun applyTranscript(transcript: String) {
        val existing = snapshotValue?.text ?: value.text
        val separator = if (existing.isEmpty() || existing.endsWith(" ")) "" else " "
        val merged = existing + separator + transcript
        value = TextFieldValue(merged, selection = TextRange(merged.length))
        snapshotValue = null
        mode = ComposerMode.EDITING
        // Layout re-evaluates on the next text layout pass; expansion is
        // decided there from the measured width, not guessed here.
    }

    fun clear() {
        value = TextFieldValue("")
        expanded = false
    }
}

@Composable
fun rememberComposerState(): ComposerState = remember { ComposerState() }

/**
 * The message composer: a single bordered container holding the input and its
 * action buttons.
 *
 * Layout is content-driven rather than character-counted. While the text fits
 * the single-line field it stays as one row; the moment the measured text wraps
 * it splits into a full-width text area with the buttons beneath, and it
 * collapses back when the text would fit on one line again.
 */
@Composable
fun MessageComposer(
    state: ComposerState,
    amplitudes: Flow<Float>,
    transcribe: suspend () -> String,
    onSend: (String) -> Unit,
    onLiveSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerWidthPx by remember { mutableFloatStateOf(0f) }

    // Latest amplitude, sampled by the waveform on its own cadence.
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.mode) {
        if (state.mode == ComposerMode.RECORDING) {
            amplitudes.collect { level = it }
        }
    }

    // Mock transcription. Cancelling the effect (mode leaving TRANSCRIBING)
    // cancels the work, which is exactly what the X button needs.
    LaunchedEffect(state.mode) {
        if (state.mode == ComposerMode.TRANSCRIBING) {
            val transcript = transcribe()
            state.applyTranscript(transcript)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { containerWidthPx = it.size.width.toFloat() }
            .background(OverlayStyle.Background, RoundedCornerShape(OverlayStyle.Corner))
            .border(1.dp, OverlayStyle.Border, RoundedCornerShape(OverlayStyle.Corner))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // One definition of "send", shared by the S button and the keyboard's
        // Enter/Send key so they cannot drift apart.
        val submit: () -> Unit = {
            val text = state.sendableText
            if (text.isNotEmpty()) {
                onSend(text)
                state.clear()
            }
        }

        if (state.mode == ComposerMode.EDITING) {
            EditingLayout(state, containerWidthPx, submit, onLiveSession)
        } else {
            // Recording and transcribing both show the waveform in place of
            // the input; an expanded text area is hidden for the duration.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GAP)
            ) {
                Waveform(
                    levels = { level },
                    frozen = state.mode == ComposerMode.TRANSCRIBING,
                    modifier = Modifier
                        .weight(1f)
                        .height(INPUT_MIN_HEIGHT)
                )
                RecordingActions(state)
            }
        }
    }
}

/**
 * Input and actions in one [Layout] whose measure policy - not its content -
 * changes with [ComposerState.expanded].
 *
 * This matters: putting the field in a Row for one state and a Column for the
 * other would move it in the composition tree, so Compose would dispose and
 * recreate it. That destroys focus, which visibly closes and reopens the
 * keyboard mid-sentence. Holding both children in a fixed slot and only
 * re-placing them keeps the same node alive, so focus, the cursor position and
 * the IME all survive the transition in both directions.
 */
@Composable
private fun EditingLayout(
    state: ComposerState,
    containerWidthPx: Float,
    onSubmit: () -> Unit,
    onLiveSession: () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { GAP.roundToPx() }
    val rowGapPx = with(density) { ROW_GAP.roundToPx() }
    val expanded = state.expanded

    Layout(
        contents = listOf(
            { InputField(state, containerWidthPx, onSubmit) },
            { EditingActions(state, onSubmit, onLiveSession) },
        )
    ) { (inputMeasurables, actionMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        val actions = actionMeasurables.map { it.measure(loose) }
        val actionsWidth = actions.sumOf { it.width }
        val actionsHeight = actions.maxOfOrNull { it.height } ?: 0

        val total = constraints.maxWidth

        if (!expanded) {
            // Single row: the field takes whatever the buttons leave, which
            // lands around 70% on a phone and scales with the screen rather
            // than being pinned to a fixed width.
            val inputWidth = (total - actionsWidth - gapPx).coerceAtLeast(0)
            val input = inputMeasurables.map {
                it.measure(loose.copy(maxWidth = inputWidth))
            }
            val inputHeight = input.maxOfOrNull { it.height } ?: 0
            val height = maxOf(inputHeight, actionsHeight)

            layout(total, height) {
                input.forEach { it.place(0, (height - it.height) / 2) }
                var x = total - actionsWidth
                actions.forEach {
                    it.place(x, (height - it.height) / 2)
                    x += it.width
                }
            }
        } else {
            // Stacked: full-width text area with the buttons beneath it.
            val input = inputMeasurables.map {
                it.measure(loose.copy(maxWidth = total))
            }
            val inputHeight = input.maxOfOrNull { it.height } ?: 0
            val height = inputHeight + rowGapPx + actionsHeight

            layout(total, height) {
                input.forEach { it.place(0, 0) }
                var x = total - actionsWidth
                actions.forEach {
                    it.place(x, inputHeight + rowGapPx)
                    x += it.width
                }
            }
        }
    }
}

@Composable
private fun InputField(
    state: ComposerState,
    containerWidthPx: Float,
    onSubmit: () -> Unit,
) {
    // Remembered outside the conditional so the scroll position is not lost
    // when the layout collapses and expands again.
    val scroll = rememberScrollState()

    BasicTextField(
        value = state.value,
        onValueChange = { state.value = it },
        modifier = Modifier
            // Enter sends rather than inserting a newline, which is what a chat
            // composer is expected to do. Without this the field is multi-line,
            // so pressing Enter silently stacks each fragment on its own line
            // and nothing is ever sent.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (
                        event.key == Key.Enter || event.key == Key.NumPadEnter
                        )
                ) {
                    onSubmit()
                    true
                } else {
                    false
                }
            }
            .then(
                if (state.expanded) {
                    Modifier
                        .heightIn(min = INPUT_MIN_HEIGHT, max = INPUT_MAX_HEIGHT)
                        .verticalScroll(scroll)
                } else {
                    Modifier
                        .heightIn(min = INPUT_MIN_HEIGHT)
                        .onGloballyPositioned {
                            if (it.size.width > 0) {
                                state.collapsedFieldWidthPx = it.size.width.toFloat()
                            }
                        }
                }
            )
            .fillMaxWidth(),
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        cursorBrush = SolidColor(Color.White),
        // Ask the IME for a Send key too, so keyboards that never emit a raw
        // Enter still submit. The key handler above covers the rest.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        onTextLayout = { layout ->
            // Expand as soon as the text actually wraps, and collapse again
            // once one line of it would fit the narrow field. Measured, not
            // counted, so it holds at any font scale or screen width.
            if (!state.expanded) {
                if (layout.lineCount > 1) state.expanded = true
            } else if (layout.lineCount == 1) {
                val narrow = state.collapsedFieldWidthPx.takeIf { it > 0f }
                    ?: (containerWidthPx * FALLBACK_INPUT_FRACTION)
                // Hysteresis, so text sitting exactly on the boundary does not
                // flip the layout back and forth on every keystroke.
                if (narrow > 0f && layout.getLineRight(0) <= narrow * COLLAPSE_MARGIN) {
                    state.expanded = false
                }
            }
        },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (state.value.text.isEmpty()) {
                    Text("Ask anything", color = OverlayStyle.Placeholder, fontSize = 15.sp)
                }
                inner()
            }
        }
    )
}

@Composable
private fun EditingActions(
    state: ComposerState,
    onSubmit: () -> Unit,
    onLiveSession: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
        ActionButton(label = "M", onClick = { state.beginRecording() })
        if (state.hasText) {
            ActionButton(label = "S", accent = true, onClick = onSubmit)
        } else {
            ActionButton(label = "L", onClick = onLiveSession)
        }
    }
}

@Composable
private fun RecordingActions(state: ComposerState) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
        // Cancel stays live through transcription so a slow transcript can
        // always be abandoned without losing the typed text.
        ActionButton(label = "X", onClick = { state.cancelRecording() })

        if (state.mode == ComposerMode.TRANSCRIBING) {
            Box(
                modifier = Modifier
                    .size(BUTTON_SIZE)
                    .background(OverlayStyle.ButtonBackground, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        } else {
            ActionButton(label = "✓", accent = true, onClick = { state.confirmRecording() })
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
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

private val GAP = 8.dp
private val ROW_GAP = 6.dp
private val BUTTON_SIZE = 36.dp
private val INPUT_MIN_HEIGHT = 36.dp
private val INPUT_MAX_HEIGHT = 120.dp
private const val FALLBACK_INPUT_FRACTION = 0.7f
private const val COLLAPSE_MARGIN = 0.92f
