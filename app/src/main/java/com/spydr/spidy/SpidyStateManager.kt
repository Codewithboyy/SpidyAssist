package com.spydr.spidy

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class AssistantState {
    IDLE,
    WAKE_WORD_LISTENING,
    WAKE_WORD_DETECTED,
    COMMAND_LISTENING,
    PROCESSING,
    SPEAKING
}

object SpidyStateManager {

    private const val TAG = "SpidyStateManager"

    private val currentSessionId = AtomicLong(System.currentTimeMillis())
    private val _state = MutableStateFlow(AssistantState.IDLE)

    val state: StateFlow<AssistantState> = _state.asStateFlow()
    val activeState: AssistantState get() = _state.value
    val activeSessionId: Long get() = currentSessionId.get()

    fun getCurrentSessionId(): Long = currentSessionId.get()

    /**
     * Restores a previously saved session ID (e.g. from SharedPreferences).
     * Does NOT reset memory — used on app launch to continue prior conversation.
     */
    fun restoreSession(sessionId: Long) {
        currentSessionId.set(sessionId)
        _state.value = AssistantState.IDLE
        Log.d(TAG, "Session restored: $sessionId")
    }

    /**
     * Creates a brand new session. Call ONLY on explicit user request (clear memory)
     * or when session idle timeout has expired.
     */
    fun startNewSession(): Long {
        val newId = System.currentTimeMillis()
        currentSessionId.set(newId)
        Log.d(TAG, "New session started: $newId")
        return newId
    }

    fun transitionTo(newState: AssistantState, sessionId: Long = currentSessionId.get()): Boolean {
        while (true) {
            if (!isSessionValid(sessionId)) {
                Log.w(TAG, "Rejected transition to $newState — stale session $sessionId (active: ${currentSessionId.get()})")
                return false
            }

            val previous = _state.value
            if (!isValidTransition(previous, newState)) {
                Log.w(TAG, "Invalid transition $previous -> $newState for session $sessionId")
                return false
            }

            if (_state.compareAndSet(previous, newState)) {
                if (!isSessionValid(sessionId)) {
                    _state.value = AssistantState.IDLE
                    return false
                }
                Log.d(TAG, "[$sessionId] $previous -> $newState")
                return true
            }
        }
    }

    fun resetToIdle(): Long {
        _state.value = AssistantState.IDLE
        return currentSessionId.get()
    }

    fun isSessionValid(sessionId: Long): Boolean = sessionId == currentSessionId.get()

    private fun isValidTransition(from: AssistantState, to: AssistantState): Boolean {
        if (from == to) return true
        return when (from) {
            AssistantState.IDLE ->
                to == AssistantState.WAKE_WORD_LISTENING ||
                to == AssistantState.COMMAND_LISTENING

            AssistantState.WAKE_WORD_LISTENING ->
                to == AssistantState.WAKE_WORD_DETECTED ||
                to == AssistantState.COMMAND_LISTENING ||
                to == AssistantState.IDLE

            AssistantState.WAKE_WORD_DETECTED ->
                to == AssistantState.COMMAND_LISTENING ||
                to == AssistantState.IDLE

            AssistantState.COMMAND_LISTENING ->
                to == AssistantState.PROCESSING ||
                to == AssistantState.WAKE_WORD_LISTENING ||
                to == AssistantState.IDLE

            AssistantState.PROCESSING ->
                to == AssistantState.SPEAKING ||
                to == AssistantState.COMMAND_LISTENING ||
                to == AssistantState.WAKE_WORD_LISTENING ||
                to == AssistantState.IDLE

            AssistantState.SPEAKING ->
                to == AssistantState.COMMAND_LISTENING ||
                to == AssistantState.PROCESSING ||
                to == AssistantState.WAKE_WORD_LISTENING ||
                to == AssistantState.IDLE
        }
    }
}
