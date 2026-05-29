package com.vayunmathur.email.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide email-sync state shared between `EmailSyncWorker` and the UI.
 * The worker writes; the ViewModel / Composables read.
 *
 * `progress` is 0f..1f when [isSyncing] is true. The UI shows a thin
 * `LinearProgressIndicator` at the top of the inbox while [isSyncing] is true.
 */
object EmailSyncState {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    fun start() {
        _progress.value = 0f
        _isSyncing.value = true
    }

    /** Fraction in [0, 1]. */
    fun setProgress(value: Float) {
        _progress.value = value.coerceIn(0f, 1f)
    }

    fun finish() {
        _progress.value = 1f
        _isSyncing.value = false
    }
}
