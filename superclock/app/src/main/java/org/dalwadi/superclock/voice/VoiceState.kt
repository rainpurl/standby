package org.dalwadi.superclock.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the voice pipeline is doing right now. */
enum class VoicePhase {
    /** Passively scanning for the wake word. Nothing is drawn over the clock. */
    IDLE,

    /** Woken. Capturing a command; the overlay is up and the level meter is live. */
    LISTENING,

    /** Command captured, being parsed and scheduled. */
    WORKING,

    /** Finished successfully; [VoiceState.message] holds the confirmation. */
    SUCCESS,

    /** Finished unsuccessfully; [VoiceState.message] holds the reason. */
    FAILURE,
}

/**
 * Process-wide bus between [VoiceService] (producer) and the clock UI (consumer).
 *
 * Both live in the same process, so a singleton is enough and avoids the latency
 * of broadcasting for something that updates at frame rate.
 */
object VoiceState {

    private val _phase = MutableStateFlow(VoicePhase.IDLE)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()

    /** Live partial transcript while [phase] is LISTENING. */
    private val _heard = MutableStateFlow("")
    val heard: StateFlow<String> = _heard.asStateFlow()

    /** Confirmation or error text for SUCCESS / FAILURE. */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** Smoothed mic level 0f..1f, for the listening animation. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** False until the speech model is unpacked and loaded. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Non-null when the pipeline is broken (no mic permission, model failure...). */
    private val _fault = MutableStateFlow<String?>(null)
    val fault: StateFlow<String?> = _fault.asStateFlow()

    internal fun setPhase(p: VoicePhase) { _phase.value = p }
    internal fun setHeard(text: String) { _heard.value = text }
    internal fun setMessage(text: String) { _message.value = text }
    internal fun setLevel(level: Float) { _level.value = level.coerceIn(0f, 1f) }
    internal fun setReady(ready: Boolean) { _ready.value = ready }
    internal fun setFault(fault: String?) { _fault.value = fault }

    internal fun reset() {
        _phase.value = VoicePhase.IDLE
        _heard.value = ""
        _message.value = ""
        _level.value = 0f
    }
}
