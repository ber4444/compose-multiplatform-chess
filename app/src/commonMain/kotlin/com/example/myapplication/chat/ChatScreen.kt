package com.example.myapplication.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.GameUiState
import com.example.myapplication.isAndroidPlatform
import com.example.myapplication.SubScreenScaffold

/**
 * Interactive position-chat screen. Renders each `token` event as it arrives; Stop cancels the
 * in-flight stream, Retry re-issues the last turn. The message list uses stable keys so a token
 * arrival only recomposes the in-flight row, not the whole transcript.
 *
 * **The UI is incremental; the wire currently is not.** Measured against the deployment on
 * 2026-08-05, a turn arrives as one `token` event 10.9 s after the request, immediately followed by
 * `done` — the provider answers `stream: true` with a whole completion, so there is nothing for
 * this screen to reveal gradually. The per-token rendering is not dead code (the template composer
 * and any genuinely streaming provider do arrive in pieces) but do not describe the shipped
 * experience as token-by-token. See `docs/plans/cloud-eval-honesty-followups.md` § P1-2 and the
 * `chat-provider-oneshot` log line in `:server`.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    gameState: GameUiState,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the latest message in view as tokens stream in.
    LaunchedEffect(state.messages.size, state.partialText) {
        val lastIndex = state.messages.size - 1
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
        SubScreenScaffold(
        title = "Position Chat",
        onBack = onBack,
        showBackButton = !isAndroidPlatform,
        scrollable = false,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Ask about the current position. Replies are grounded and cloud-streamed; Stop cancels.",
                style = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(
                state = listState,
                // Use weight(1f) instead of heightIn so the chat view pushes the input box to the bottom.
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { message ->
                    MessageRow(message)
                }
                if (state.streaming) {
                    item(key = "__streaming__") {
                        StreamingRow(state.displayPartialText, state.firstTokenReceived)
                    }
                }
                if (state.error && !state.streaming) {
                    item(key = "__error__") {
                        Text(
                            "Chat failed or was cancelled.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("chat_error"),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(MAX_INPUT_CHARS) },
                    label = { Text("Ask about the position") },
                    enabled = !state.streaming,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("chat_input"),
                )
                if (state.streaming) {
                    OutlinedButton(
                        onClick = { viewModel.stop() },
                        modifier = Modifier.testTag("chat_stop_button"),
                    ) { Text("Stop") }
                } else if (state.canRetry) {
                    OutlinedButton(
                        onClick = { viewModel.retry(gameState) },
                        modifier = Modifier.testTag("chat_retry_button"),
                    ) { Text("Retry") }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                viewModel.send(gameState, input)
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank(),
                        modifier = Modifier.testTag("chat_send_button"),
                    ) { Text("Send") }
                }
            }
        }
    }  }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val tag = if (message.role == "user") "chat_user_message" else "chat_assistant_message"
    Column(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(
            text = if (message.role == "user") "You" else "Coach",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
        if (message.isFallback) {
            Text(
                "fallback reply",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun StreamingRow(partialText: String, firstTokenReceived: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().testTag("chat_streaming_row")) {
        Text("Coach", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        if (!firstTokenReceived) {
            // Typing indicator while awaiting the first token.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text("Thinking…")
            }
        } else {
            Text(partialText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val MAX_INPUT_CHARS = 500
