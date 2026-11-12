package io.github.tostapane.hven

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

// Everything specific to the model we run. Switching models means changing this file.
object ModelConfig {
    const val FILE_NAME = "Qwen3.5-2B-Q4_K_M.gguf"
    const val CONTEXT_SIZE = 4096 // Tokens of conversation the model can hold.
    // The M35 has 4 fast cores. Reading the prompt is compute-bound and uses all 4; generating is
    // limited by memory speed: on the M35, 2 threads match 4 (7.3 tok/s) and run much cooler.
    const val PROMPT_THREADS = 4
    const val GENERATION_THREADS = 2

    const val TEMPERATURE = 1.0f
    const val TOP_K = 20
    const val PRESENCE_PENALTY = 2.0f

    // Kept short: the model reads it at the start of every chat. Small models act out every
    // sentence, so it states who Hven is once and otherwise only asks for behavior.
    const val SYSTEM_PROMPT =
        "You are Hven, a private assistant that runs offline on the user's phone. Answer directly and briefly."

    // Tool schemas, one per line, exactly as Qwen's template prints them (Python's json.dumps).
    // Must match GRAMMAR and Tools.kt.
    private val TOOLS = listOf(
        """{"type": "function", "function": {"name": "set_timer", "description": "Start a countdown timer for a duration.", "parameters": {"type": "object", "properties": {"hours": {"type": "integer"}, "minutes": {"type": "integer"}, "seconds": {"type": "integer"}}, "required": []}}}""",
        """{"type": "function", "function": {"name": "set_alarm", "description": "Set an alarm that rings at a time of day.", "parameters": {"type": "object", "properties": {"hour": {"type": "integer", "description": "0-23"}, "minute": {"type": "integer", "description": "0-59"}}, "required": ["hour", "minute"]}}}""",
        """{"type": "function", "function": {"name": "add_calendar_event", "description": "Add an event to the calendar.", "parameters": {"type": "object", "properties": {"title": {"type": "string"}, "date": {"type": "string", "description": "YYYY-MM-DD"}, "start": {"type": "string", "description": "HH:MM, 24-hour"}, "duration_minutes": {"type": "integer"}}, "required": ["title", "date", "start"]}}}""",
    )

    // Qwen's tool instructions, copied word for word from the chat template in the GGUF.
    private const val TOOL_INSTRUCTIONS =
        "If you choose to call a function ONLY reply in the following format with NO suffix:\n\n" +
        "<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\nvalue_1\n</parameter>\n" +
        "<parameter=example_parameter_2>\nThis is the value for the second parameter\nthat can span\nmultiple lines\n" +
        "</parameter>\n</function>\n</tool_call>\n\n" +
        "<IMPORTANT>\nReminder:\n" +
        "- Function calls MUST follow the specified format: an inner <function=...></function> block must be nested within <tool_call></tool_call> XML tags\n" +
        "- Required parameters MUST be specified\n" +
        "- You may provide optional reasoning for your function call in natural language BEFORE the function call, but NOT after\n" +
        "- If there is no function call available, answer the question like normal with your current knowledge and do not tell the user about function calls\n" +
        "</IMPORTANT>"

    // The system turn as the template builds it when tools are present: tools, instructions, our prompt.
    private val SYSTEM_TURN =
        "<|im_start|>system\n# Tools\n\nYou have access to the following functions:\n\n<tools>\n" +
        TOOLS.joinToString("\n") + "\n</tools>\n\n" + TOOL_INSTRUCTIONS + "\n\n" + SYSTEM_PROMPT + "<|im_end|>\n"

    // <tool_call> is one token in Qwen3.5's vocabulary; the grammar switches on when it appears.
    const val TOOL_CALL_TOKEN = 248058

    // GBNF for what follows <tool_call>: our three functions, parameters in schema order, values
    // of the right shape. Ranges (hour <= 23) are checked by Tools.kt.
    val GRAMMAR = """
        root ::= "<tool_call>\n<function=" (timer | alarm | event) "</function>\n</tool_call>"

        timer ::= "set_timer>\n" (hours minutes? seconds? | minutes seconds? | seconds)
        hours ::= "<parameter=hours>\n" number "\n</parameter>\n"
        minutes ::= "<parameter=minutes>\n" number "\n</parameter>\n"
        seconds ::= "<parameter=seconds>\n" number "\n</parameter>\n"

        alarm ::= "set_alarm>\n" hour minute
        hour ::= "<parameter=hour>\n" [0-9]{1,2} "\n</parameter>\n"
        minute ::= "<parameter=minute>\n" [0-9]{1,2} "\n</parameter>\n"

        event ::= "add_calendar_event>\n" title date start duration?
        title ::= "<parameter=title>\n" [^<\n]{1,100} "\n</parameter>\n"
        date ::= "<parameter=date>\n" [0-9]{4} "-" [0-9]{2} "-" [0-9]{2} "\n</parameter>\n"
        start ::= "<parameter=start>\n" [0-9]{2} ":" [0-9]{2} "\n</parameter>\n"
        duration ::= "<parameter=duration_minutes>\n" number "\n</parameter>\n"

        number ::= [0-9]{1,4}
    """.trimIndent()

    // One turn of the chat. `before` and `after` are our template, where control markers like
    // <|im_start|> are real. The user's text travels separately, so markers in it stay plain text.
    class Turn(val before: String, val userText: String, val after: String)

    // The new part of the chat in ChatML (earlier turns are already in the model's memory): close the last
    // answer, report its tool call if any, add the date list. The empty <think> block turns thinking off.
    fun turn(
        userMessage: String,
        isFirstTurn: Boolean,
        toolResult: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Turn {
        val start = if (isFirstTurn) SYSTEM_TURN else "<|im_end|>\n"
        val response =
            if (toolResult == null) "" else "<|im_start|>user\n<tool_response>\n$toolResult\n</tool_response><|im_end|>\n"
        return Turn(
            before = start + response + "<|im_start|>user\n" + dateList(now) + "\n\n",
            userText = userMessage,
            after = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
        )
    }

    // Dates the model can look up, so it never does date arithmetic. For example:
    //   Today: Wednesday 2026-11-11, 14:30
    //   Tomorrow: Thursday 2026-11-12
    //   Friday: 2026-11-13
    //   ...
    //   Next Wednesday: 2026-11-18
    // Weekday names are in English whatever the phone's language, because the prompt is in English.
    // LocalDate and LocalTime print as ISO (2026-11-11, 14:30) in every locale.
    fun dateList(now: LocalDateTime): String {
        val today = now.toLocalDate()
        fun weekday(offset: Long) =
            today.plusDays(offset).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val lines = mutableListOf(
            "Today: ${weekday(0)} $today, ${now.toLocalTime().truncatedTo(ChronoUnit.MINUTES)}",
            "Tomorrow: ${weekday(1)} ${today.plusDays(1)}",
        )
        for (offset in 2L..6L) lines += "${weekday(offset)}: ${today.plusDays(offset)}"
        // Today's weekday name alone would be ambiguous, so the one a week from now gets "Next".
        lines += "Next ${weekday(7)}: ${today.plusDays(7)}"
        return lines.joinToString("\n")
    }

    // A tool call as the model wrote it. Values stay text; Tools.kt checks and converts them.
    class ToolCall(val name: String, val args: Map<String, String>)

    // The tool call in a finished answer, or null if the model only chatted or was stopped mid-call.
    fun parseToolCall(answer: String): ToolCall? {
        val call = Regex("<tool_call>(.*)</tool_call>", RegexOption.DOT_MATCHES_ALL)
            .find(answer)?.groupValues?.get(1) ?: return null
        val name = Regex("<function=([^>]+)>").find(call)?.groupValues?.get(1) ?: return null
        val args = Regex("<parameter=([^>]+)>\n(.*?)\n</parameter>", RegexOption.DOT_MATCHES_ALL)
            .findAll(call)
            .associate { it.groupValues[1] to it.groupValues[2] }
        return ToolCall(name, args)
    }
}
