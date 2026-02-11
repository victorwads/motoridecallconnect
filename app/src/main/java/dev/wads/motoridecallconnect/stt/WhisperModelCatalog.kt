package dev.wads.motoridecallconnect.stt

private const val DEFAULT_MODEL_REPO = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
private const val TINY_DIARIZE_REPO = "https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main"

data class WhisperModelOption(
    val id: String,
    val displayName: String,
    val details: String,
    val sourceRepo: String = DEFAULT_MODEL_REPO
) {
    val fileName: String = "ggml-$id.bin"
    val downloadUrl: String = "$sourceRepo/$fileName?download=true"
}

object WhisperModelCatalog {
    val options: List<WhisperModelOption> = listOf(
        WhisperModelOption("tiny", "tiny", "Multilingual, fastest"),
        WhisperModelOption("tiny.en", "tiny.en", "English only, fastest"),
        WhisperModelOption("tiny-q5_1", "tiny-q5_1", "Multilingual, quantized q5_1"),
        WhisperModelOption("tiny.en-q5_1", "tiny.en-q5_1", "English only, quantized q5_1"),
        WhisperModelOption("tiny-q8_0", "tiny-q8_0", "Multilingual, quantized q8_0"),
        WhisperModelOption("tiny.en-q8_0", "tiny.en-q8_0", "English only, quantized q8_0"),

        WhisperModelOption("base", "base", "Multilingual, better quality"),
        WhisperModelOption("base.en", "base.en", "English only, better quality"),
        WhisperModelOption("base-q5_1", "base-q5_1", "Multilingual, quantized q5_1"),
        WhisperModelOption("base.en-q5_1", "base.en-q5_1", "English only, quantized q5_1"),
        WhisperModelOption("base-q8_0", "base-q8_0", "Multilingual, quantized q8_0"),
        WhisperModelOption("base.en-q8_0", "base.en-q8_0", "English only, quantized q8_0"),

        WhisperModelOption("small", "small", "Multilingual, higher quality"),
        WhisperModelOption("small.en", "small.en", "English only, higher quality"),
        WhisperModelOption("small-q5_1", "small-q5_1", "Multilingual, quantized q5_1"),
        WhisperModelOption("small.en-q5_1", "small.en-q5_1", "English only, quantized q5_1"),
        WhisperModelOption("small-q8_0", "small-q8_0", "Multilingual, quantized q8_0"),
        WhisperModelOption("small.en-q8_0", "small.en-q8_0", "English only, quantized q8_0"),
        WhisperModelOption(
            id = "small.en-tdrz",
            displayName = "small.en-tdrz",
            details = "English only with tiny diarization",
            sourceRepo = TINY_DIARIZE_REPO
        ),

        WhisperModelOption("medium", "medium", "Multilingual, highest quality in this list"),
        WhisperModelOption("medium.en", "medium.en", "English only, highest quality in this list"),
        WhisperModelOption("medium-q5_0", "medium-q5_0", "Multilingual, quantized q5_0"),
        WhisperModelOption("medium.en-q5_0", "medium.en-q5_0", "English only, quantized q5_0"),
        WhisperModelOption("medium-q8_0", "medium-q8_0", "Multilingual, quantized q8_0"),
        WhisperModelOption("medium.en-q8_0", "medium.en-q8_0", "English only, quantized q8_0")
    )

    val defaultOption: WhisperModelOption = options.first { it.id == "tiny" }

    fun findById(id: String): WhisperModelOption? = options.firstOrNull { it.id == id }
}
