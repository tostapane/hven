package io.github.tostapane.hven

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    // Created once and kept across screen rotations (see ChatViewModel).
    private val chat: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the status and navigation bars; Scaffold's padding keeps content clear of them.
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                ChatScreen(chat)
            }
        }
    }
}
