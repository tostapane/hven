package io.github.tostapane.hven

// Everything specific to the model we run. Switching models means changing this file.
object ModelConfig {
    // Copied to the app's files dir: /sdcard/Android/data/io.github.tostapane.hven/files/
    const val FILE_NAME = "Qwen3.5-2B-Q4_K_M.gguf"

    // Tokens of conversation the model can hold.
    const val CONTEXT_SIZE = 4096

    // The M35 has 4 fast cores; adding the 4 slow ones makes generation slower.
    const val THREADS = 4

    // Qwen's recommended settings for non-thinking text chat.
    const val TEMPERATURE = 1.0f
    const val TOP_K = 20
    const val PRESENCE_PENALTY = 2.0f

    // Kept short: the model reads it at the start of every chat. Small models act out every
    // sentence, so it states who Hven is once and otherwise only asks for behavior.
    const val SYSTEM_PROMPT =
        "You are Hven, a private assistant that runs offline on the user's phone. Answer directly and briefly."

    // One turn of the chat. `before` and `after` are our template, where control markers like
    // <|im_start|> are real. The user's text travels separately, so markers in it stay plain text.
    class Turn(val before: String, val userText: String, val after: String)

    // Qwen's chat format (ChatML). Only the new part of the conversation is sent:
    // earlier turns are already in the model's memory.
    fun turn(userMessage: String, isFirstTurn: Boolean): Turn {
        // The previous answer stopped before its end marker, so later turns close it first.
        val start = if (isFirstTurn) "<|im_start|>system\n$SYSTEM_PROMPT<|im_end|>\n" else "<|im_end|>\n"
        return Turn(
            before = start + "<|im_start|>user\n",
            userText = userMessage,
            // The empty <think> block turns off thinking, as Qwen's own template does.
            after = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
        )
    }
}
