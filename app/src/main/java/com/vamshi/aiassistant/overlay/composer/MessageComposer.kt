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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalContext
import com.vamshi.aiassistant.overlay.MicPermissionActivity

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

    /** Set while the mic permission prompt is up, so its result is acted on. */
    var awaitingMicPermission by mutableStateOf(false)

    /** Drives the L -> S swap: any character counts, per the spec. */
    val hasText: Boolean get() = value.text.isNotEmpty()

    /** What would actually be sent; blank-only input is not a message. */
    val sendableText: String get() = value.text.trim()

    fun beginRecording() {
        snapshotValue = value
        snapshotExpanded = expanded
        mode = ComposerMode.RECORDING
    }

    /** Set by the Send button; routes the transcript straight to onSend instead of the input field for review. */
    var sendDirectly = false
        private set

    fun confirmRecording() {
        sendDirectly = false
        mode = ComposerMode.TRANSCRIBING
    }

    /** Skips the review step: the transcript is sent as-is once it lands. */
    fun sendRecording() {
        sendDirectly = true
        mode = ComposerMode.TRANSCRIBING
    }

    /** Restores the exact pre-recording state; no typed text is ever lost. */
    fun cancelRecording() {
        snapshotValue?.let { value = it }
        expanded = snapshotExpanded
        snapshotValue = null
        mode = ComposerMode.EDITING
    }

    /** Combines the transcript with whatever was already typed, same rule for both review and direct send. */
    private fun mergedWithSnapshot(transcript: String): String {
        val existing = snapshotValue?.text ?: value.text
        val separator = if (existing.isEmpty() || existing.endsWith(" ")) "" else " "
        return existing + separator + transcript
    }

    /** Appends the transcript to whatever was already typed. */
    fun applyTranscript(transcript: String) {
        val merged = mergedWithSnapshot(transcript)
        value = TextFieldValue(merged, selection = TextRange(merged.length))
        snapshotValue = null
        mode = ComposerMode.EDITING
        // Layout re-evaluates on the next text layout pass; expansion is
        // decided there from the measured width, not guessed here.
    }

    /**
     * Text to hand to onSend when the transcript skips the input field
     * entirely. Clears the snapshot the same way [applyTranscript] does, but
     * leaves [value] and [mode] to the caller - a failed send needs to fall
     * back to review rather than to a blank field.
     */
    fun consumeTranscriptForSend(transcript: String): String {
        val merged = mergedWithSnapshot(transcript)
        snapshotValue = null
        return merged.trim()
    }

    /**
     * Shows already-merged text for review after a direct send was refused.
     * Distinct from [applyTranscript]: that one merges its argument against
     * the snapshot, which [consumeTranscriptForSend] has already consumed -
     * calling it here would merge the same text in twice.
     */
    fun showForReview(text: String) {
        value = TextFieldValue(text, selection = TextRange(text.length))
        mode = ComposerMode.EDITING
    }

    fun clear() {
        value = TextFieldValue("")
        expanded = false
        mode = ComposerMode.EDITING
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
    onTranscriptionError: (Throwable) -> Unit,
    onSend: (String) -> Boolean,
    onStartRecording: () -> Boolean,
    onCancelRecording: () -> Unit,
    onLiveSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerWidthPx by remember { mutableFloatStateOf(0f) }

    // Amplitude accumulated between the waveform's samples.
    //
    // The source emits roughly every 64ms while the waveform samples every
    // 200ms, so reading only the newest emission would keep one frame in three
    // and throw the rest away - a bar could land on a pause between syllables
    // and read near-silent during continuous speech. Peak-holding across the
    // interval instead means each bar reflects the loudest moment it covers,
    // which is what the reference gets for free by running RMS over a window
    // at sample time.
    val meter = remember { AmplitudeMeter() }
    val context = LocalContext.current

    // Start recording as soon as the prompt comes back granted, so the user
    // does not have to tap the mic a second time.
    LaunchedEffect(Unit) {
        MicPermissionActivity.results.collect { granted ->
            if (state.awaitingMicPermission) {
                state.awaitingMicPermission = false
                if (granted && onStartRecording()) state.beginRecording()
            }
        }
    }

    LaunchedEffect(state.mode) {
        if (state.mode == ComposerMode.RECORDING) {
            meter.reset()
            amplitudes.collect { meter.push(it) }
        }
    }

    // The upload is owned by this effect, so leaving TRANSCRIBING cancels the
    // request and lets the X button abandon slow transcription safely.
    LaunchedEffect(state.mode) {
        if (state.mode == ComposerMode.TRANSCRIBING) {
            try {
                val transcript = transcribe()
                if (state.sendDirectly) {
                    val text = state.consumeTranscriptForSend(transcript)
                    // onSend can refuse (e.g. a response is still streaming);
                    // fall back to showing the transcript for review rather
                    // than silently dropping it.
                    if (text.isNotEmpty() && onSend(text)) {
                        state.clear()
                    } else {
                        state.showForReview(text)
                    }
                } else {
                    state.applyTranscript(transcript)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                state.cancelRecording()
                onTranscriptionError(error)
            }
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
            if (text.isNotEmpty() && onSend(text)) {
                state.clear()
            }
        }

        if (state.mode == ComposerMode.EDITING) {
            EditingLayout(state, containerWidthPx, submit, onStartRecording, onLiveSession)
        } else {
            // Recording and transcribing both show the waveform in place of
            // the input; an expanded text area is hidden for the duration.
            // Cancel sits alone on the far left - furthest from the thumb's
            // natural resting position on the right, so a hasty tap lands on
            // Confirm or Send rather than aborting the recording.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GAP)
            ) {
                // Cancel stays live through transcription so a slow transcript
                // can always be abandoned without losing the typed text.
                ActionButton(
                    label = "X",
                    onClick = {
                        onCancelRecording()
                        state.cancelRecording()
                    }
                )
                if (state.mode == ComposerMode.TRANSCRIBING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
                        modifier = Modifier
                            .weight(1f)
                            .height(INPUT_MIN_HEIGHT)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = OverlayStyle.Placeholder,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Transcribing…",
                            color = OverlayStyle.Placeholder,
                            fontSize = 15.sp
                        )
                    }
                } else {
                    Waveform(
                        levels = meter::take,
                        frozen = false,
                        modifier = Modifier
                            .weight(1f)
                            .height(INPUT_MIN_HEIGHT)
                    )
                }
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
    onStartRecording: () -> Boolean,
    onLiveSession: () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { GAP.roundToPx() }
    val rowGapPx = with(density) { ROW_GAP.roundToPx() }
    val expanded = state.expanded

    Layout(
        contents = listOf(
            { InputField(state, containerWidthPx, onSubmit) },
            { EditingActions(state, onSubmit, onStartRecording, onLiveSession) },
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
    onStartRecording: () -> Boolean,
    onLiveSession: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
        val context = LocalContext.current
        ActionButton(
            label = "M",
            onClick = {
                // An overlay window has no Activity to prompt from, so hand off
                // to MicPermissionActivity and start recording only once the
                // user has actually granted it - otherwise AudioRecord is
                // refused and the waveform draws synthetic levels instead.
                if (MicPermissionActivity.hasPermission(context)) {
                    if (onStartRecording()) state.beginRecording()
                } else {
                    state.awaitingMicPermission = true
                    MicPermissionActivity.request(context)
                }
            }
        )
        if (state.hasText) {
            ActionButton(label = "S", accent = true, onClick = onSubmit)
        } else {
            ActionButton(label = "L", accent = true, onClick = onLiveSession)
        }
    }
}

@Composable
private fun RecordingActions(state: ComposerState) {
    // Both stay on screen while transcribing rather than being replaced by a
    // spinner, so the layout does not jump the moment it finishes - only
    // their enabled state changes.
    val enabled = state.mode != ComposerMode.TRANSCRIBING
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
        // Confirm: transcribe, then show the text for review before it is
        // sent. Send: transcribe and send immediately, skipping review - the
        // fast path for a query the user trusts was heard correctly.
        ActionButton(label = "✓", enabled = enabled, onClick = { state.confirmRecording() })
        ActionButton(label = "S", accent = true, enabled = enabled, onClick = { state.sendRecording() })
    }
}

@Composable
private fun ActionButton(
    label: String,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(BUTTON_SIZE)
            .background(
                if (accent) OverlayStyle.AccentBackground else OverlayStyle.ButtonBackground,
                CircleShape
            )
            // Disabled buttons stay visible rather than disappearing, so
            // fading their content is enough to read as "not tappable yet"
            // without the layout shifting.
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
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
private const val DISABLED_ALPHA = 0.4f
