package com.example.myapplication.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.SubScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun RulesQaScreen(
    stateHolder: RulesQaStateHolder,
    onBack: () -> Unit,
) {
    val state by stateHolder.state.collectAsState()
    var question by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    SubScreenScaffold(title = "Chess rules", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ask a rules question. Answers and retrieval stay on this device.")
            OutlinedTextField(
                value = question,
                onValueChange = { question = it.take(240) },
                label = { Text("Rules question") },
                enabled = state !is RulesQaUiState.Unavailable,
                modifier = Modifier.fillMaxWidth().testTag("rules_question_input"),
            )
            Button(
                onClick = { scope.launch { stateHolder.ask(question) } },
                enabled = question.isNotBlank() && state !is RulesQaUiState.Loading && state !is RulesQaUiState.Unavailable,
                modifier = Modifier.testTag("ask_rules_button"),
            ) {
                Text("Ask offline")
            }
            when (val current = state) {
                RulesQaUiState.Idle -> Text("Try: When is en passant allowed?")
                RulesQaUiState.Loading -> CircularProgressIndicator()
                RulesQaUiState.Unavailable -> Text("Offline rules Q&A is unavailable on this platform or build.")
                is RulesQaUiState.Ready -> {
                    Text(current.text, modifier = Modifier.testTag("rules_answer"))
                    if (current.passageIds.isNotEmpty()) Text("Sources: ${current.passageIds.joinToString()}")
                    if (current.isFallback) Text("Offline reference fallback")
                }
            }
        }
    }
}
