package io.github.tostapane.hven

// Receives generated text from C++, a few bytes at a time (always whole UTF-8 characters).
fun interface PieceListener {
    fun onPiece(bytes: ByteArray)
}

// The native functions in app/src/main/cpp/hven.cpp. The model lives in native memory;
// `handle` is the address of the C++ Engine, which Kotlin only stores and passes back.
// Call everything except stop() and stats() from one thread only.
object LlamaEngine {
    const val DONE = 0
    const val CONTEXT_FULL = 1
    const val ERROR = 2

    init {
        System.loadLibrary("hven")
    }

    // Returns the handle, or 0 if the model can't be loaded or the grammar doesn't parse.
    // The grammar stays off until the model emits triggerToken.
    external fun load(
        path: String,
        contextSize: Int,
        promptThreads: Int,
        generationThreads: Int,
        temperature: Float,
        topK: Int,
        presencePenalty: Float,
        grammar: String,
        triggerToken: Int,
    ): Long

    // Feeds before + userText + after, then blocks until the answer is complete, stop() is called,
    // or the context is full. Control markers like <|im_start|> only count in before and after:
    // in userText they're plain text. Returns DONE, CONTEXT_FULL or ERROR.
    external fun generate(
        handle: Long,
        before: ByteArray,
        userText: ByteArray,
        after: ByteArray,
        listener: PieceListener,
    ): Int

    // Timings of the current or last generate(), safe to call from any thread while it runs:
    // [prompt tokens, prompt ms, generated tokens, generation ms].
    external fun stats(handle: Long): DoubleArray

    // Safe to call from any thread: asks generate() to finish after the current token.
    external fun stop(handle: Long)

    // Forgets the conversation; the model stays loaded.
    external fun reset(handle: Long)

    external fun free(handle: Long)
}
