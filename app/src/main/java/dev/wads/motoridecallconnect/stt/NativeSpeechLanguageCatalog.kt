package dev.wads.motoridecallconnect.stt

data class NativeSpeechLanguageOption(
    val tag: String,
    val displayName: String
)

object NativeSpeechLanguageCatalog {
    val options: List<NativeSpeechLanguageOption> = listOf(
        NativeSpeechLanguageOption(tag = "pt-BR", displayName = "Portuguese (Brazil)"),
        NativeSpeechLanguageOption(tag = "pt-PT", displayName = "Portuguese (Portugal)"),
        NativeSpeechLanguageOption(tag = "en-US", displayName = "English (US)"),
        NativeSpeechLanguageOption(tag = "en-GB", displayName = "English (UK)"),
        NativeSpeechLanguageOption(tag = "es-ES", displayName = "Spanish (Spain)"),
        NativeSpeechLanguageOption(tag = "fr-FR", displayName = "French"),
        NativeSpeechLanguageOption(tag = "de-DE", displayName = "German"),
        NativeSpeechLanguageOption(tag = "it-IT", displayName = "Italian")
    )

    val defaultOption: NativeSpeechLanguageOption = options.first()

    fun findByTag(tag: String?): NativeSpeechLanguageOption? {
        if (tag.isNullOrBlank()) {
            return null
        }
        return options.firstOrNull { option ->
            option.tag.equals(tag, ignoreCase = true)
        }
    }

    fun normalizeTag(tag: String?): String {
        return findByTag(tag)?.tag ?: defaultOption.tag
    }
}
