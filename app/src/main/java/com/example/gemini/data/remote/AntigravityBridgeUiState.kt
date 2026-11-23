package com.example.gemini.data.remote

/**
 * Clean sealed state for Antigravity IDE Bridge & Hub connection lifecycle.
 * Distinguishes between normal offline idle (no server running), active connection,
 * and genuine unexpected drops or failed reconnection attempts.
 */
sealed class AntigravityBridgeUiState {
    /** Bridge functionality explicitly disabled by user/settings. Show no banner. */
    object Disabled : AntigravityBridgeUiState()

    /**
     * Bridge server is simply not running on localhost/LAN at app startup.
     * This is the normal idle state for standard Chat / Voice users.
     * Do NOT lock a permanent red error banner across screens.
     */
    object OfflineIdle : AntigravityBridgeUiState()

    /** Actively attempting to connect or reconnect to the bridge. Shows subtle progress. */
    object Connecting : AntigravityBridgeUiState()

    /** Bridge and/or Hub connected and operational. */
    object Connected : AntigravityBridgeUiState()

    /**
     * An active connection unexpectedly dropped, or an explicit user Reconnect attempt failed.
     *
     * @param reason Informative explanation (e.g. "Bridge server not running on 127.0.0.1:1234")
     * @param canDismiss Whether the user can dismiss this banner
     */
    data class Error(val reason: String, val canDismiss: Boolean = true) : AntigravityBridgeUiState()
}
