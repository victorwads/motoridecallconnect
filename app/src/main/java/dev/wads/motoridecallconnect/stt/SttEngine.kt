package dev.wads.motoridecallconnect.stt

enum class SttEngine(val id: String) {
    WHISPER("whisper"),
    NATIVE("native");

    companion object {
        fun fromId(id: String?): SttEngine {
            return entries.firstOrNull { it.id == id } ?: WHISPER
        }
    }
}
