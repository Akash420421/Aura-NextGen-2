package com.example.gemini.data.live

object GeminiConstants {
    const val MODEL_3_1_FLASH = "models/gemini-3.1-flash-live-preview"
    const val MODEL_3_8_LIVE = "models/gemini-3.8-live"
    const val MODEL_2_0_FLASH = MODEL_3_1_FLASH

    const val DEFAULT_MODEL = MODEL_3_8_LIVE
    const val DEFAULT_VOICE = "Puck"

    val SUPPORTED_MODELS = listOf(
        MODEL_3_8_LIVE,
        MODEL_3_1_FLASH
    )

    val SUPPORTED_VOICES = listOf(
        "Aoede",
        "Charon",
        "Kore",
        "Fenrir",
        "Puck",
        "Leda",
        "Orus",
        "Zephyr"
    )

    const val WS_BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val SESSION_RENEWAL_MS = 9 * 60 * 1000L // 9 minutes
}
