package io.github.tostapane.hven

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class Message(val fromUser: Boolean, val text: String)

// Holds the chat and the loaded model. A ViewModel survives screen rotation,
// so the model isn't reloaded every time the Activity is recreated.
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    // One dedicated thread runs every model call, so they never overlap.
    private val modelThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    // Written on the model thread, read every second by the UI thread: @Volatile makes the
    // writes visible to the other thread.
    @Volatile private var handle = 0L
    @Volatile private var loadSeconds = 0.0
    @Volatile private var firstWordSeconds = 0.0
    private var isFirstTurn = true
    // The chat line of the last tool call, reported to the model at the start of the next turn.
    private var toolResult: String? = null

    // Compose redraws whatever reads these when they change.
    val messages = mutableStateListOf<Message>()
    var status by mutableStateOf("Loading model…")
        private set
    var ready by mutableStateOf(false)
        private set
    var generating by mutableStateOf(false)
        private set
    // Speed and memory numbers, refreshed every second (also while generating).
    var stats by mutableStateOf("")
        private set

    init {
        viewModelScope.launch(modelThread) {
            val file = File(app.getExternalFilesDir(null), ModelConfig.FILE_NAME)
            val start = System.nanoTime()
            handle = LlamaEngine.load(
                file.path,
                ModelConfig.CONTEXT_SIZE,
                ModelConfig.PROMPT_THREADS,
                ModelConfig.GENERATION_THREADS,
                ModelConfig.TEMPERATURE,
                ModelConfig.TOP_K,
                ModelConfig.PRESENCE_PENALTY,
                ModelConfig.GRAMMAR,
                ModelConfig.TOOL_CALL_TOKEN,
            )
            loadSeconds = secondsSince(start)
            ready = handle != 0L
            status = if (ready) "" else "Can't load the model from ${file.path}"
        }
        viewModelScope.launch {
            while (true) {
                stats = statsLine()
                delay(1000)
            }
        }
    }

    fun send(text: String) {
        if (!ready || generating || text.isBlank()) return
        messages += Message(fromUser = true, text = text)
        messages += Message(fromUser = false, text = "")
        generating = true
        viewModelScope.launch(modelThread) {
            val turn = ModelConfig.turn(text, isFirstTurn, toolResult)
            isFirstTurn = false
            toolResult = null
            val start = System.nanoTime()
            firstWordSeconds = 0.0
            val answer = StringBuilder()
            val result = LlamaEngine.generate(
                handle,
                turn.before.toByteArray(),
                turn.userText.toByteArray(),
                turn.after.toByteArray(),
            ) { bytes ->
                // What the user waits for: from tapping Send to the first piece of the answer.
                if (firstWordSeconds == 0.0) firstWordSeconds = secondsSince(start)
                answer.append(bytes.decodeToString())
                messages[messages.lastIndex] = Message(fromUser = false, text = answer.toString())
            }
            val call = ModelConfig.parseToolCall(answer.toString())
            if (call != null) {
                val line = withContext(Dispatchers.Main) { Tools.run(getApplication(), call) }
                toolResult = line
                messages[messages.lastIndex] = Message(fromUser = false, text = answer.toString().trimEnd() + "\n\n" + line)
            }
            status = when (result) {
                LlamaEngine.CONTEXT_FULL -> "The chat is full: start a new one."
                LlamaEngine.ERROR -> "Something went wrong while generating."
                else -> ""
            }
            Log.i("hven", statsLine())
            generating = false
        }
    }

    // The numbers of the current (or last) answer. Safe to call from any thread.
    private fun statsLine(): String {
        val ram = "RAM ${memoryMb("VmRSS")} MB · swap ${memoryMb("VmSwap")} MB"
        if (!ready) return ram
        val (promptTokens, promptMs, generatedTokens, generateMs) = LlamaEngine.stats(handle)
        return "load %.1f s · first word %.1f s\nprompt %.0f tok, %.0f tok/s · answer %.0f tok, %.1f tok/s · %s".format(
            loadSeconds,
            firstWordSeconds,
            promptTokens, perSecond(promptTokens, promptMs),
            generatedTokens, perSecond(generatedTokens, generateMs),
            ram,
        )
    }

    fun stop() {
        if (ready) LlamaEngine.stop(handle)
    }

    fun newChat() {
        if (!ready || generating) return
        messages.clear()
        status = ""
        viewModelScope.launch(modelThread) {
            LlamaEngine.reset(handle)
            isFirstTurn = true
            toolResult = null
        }
    }

    // The screen is gone for good: stop generating, then free the model on its thread.
    override fun onCleared() {
        stop()
        CoroutineScope(modelThread).launch {
            if (handle != 0L) LlamaEngine.free(handle)
            modelThread.close()
        }
    }
}

private fun secondsSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1e9

private fun perSecond(count: Double, ms: Double) = if (ms > 0) count / ms * 1000 else 0.0

// A memory figure of this process in MB, from a /proc/self/status line like "VmSwap:   1922028 kB".
// VmRSS is what's in RAM now; VmSwap is what Android moved out to swap.
private fun memoryMb(field: String): Long =
    File("/proc/self/status").readLines()
        .first { it.startsWith("$field:") }
        .split(Regex("\\s+"))[1].toLong() / 1024

@Composable
fun ChatScreen(chat: ChatViewModel) {
    var input by remember { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(), // move up when the keyboard opens
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(chat.status, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = chat::newChat, enabled = chat.ready && !chat.generating) {
                    Text("New chat")
                }
            }
            if (chat.stats.isNotEmpty()) {
                Text(
                    chat.stats,
                    Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // reverseLayout keeps the list pinned to the bottom, so the newest text stays visible.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(8.dp),
            ) {
                items(chat.messages.asReversed()) { message -> MessageBubble(message) }
            }

            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                )
                if (chat.generating) {
                    Button(onClick = chat::stop, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
                } else {
                    Button(
                        onClick = { chat.send(input); input = "" },
                        enabled = chat.ready && input.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(message.text.ifEmpty { "…" }, Modifier.padding(12.dp))
        }
    }
}
