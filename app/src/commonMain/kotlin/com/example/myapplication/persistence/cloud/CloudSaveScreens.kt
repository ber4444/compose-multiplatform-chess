package com.example.myapplication.persistence.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.persistence.SavedGame
import com.example.saveapi.CloudResumeGame

@Composable
internal fun CloudSaveSettings(
    sync: CloudSaveSync,
    localGames: List<SavedGame>,
    onRestore: (CloudResumeGame?) -> Unit,
) {
    val state by sync.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Solo cloud saves", style = MaterialTheme.typography.titleMedium)
        state.message?.let { Text(it) }
        if (state.email == null) {
            Text("Sign in to sync solo games across devices.")
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            val canSubmit = email.isNotBlank() && password.isNotEmpty() && !state.accountBusy
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = canSubmit, onClick = {
                    sync.signIn(email, password)
                    password = ""
                }) { Text("Sign in") }
                TextButton(enabled = canSubmit, onClick = {
                    sync.register(email, password)
                    password = ""
                }) { Text("Create account") }
            }
            TextButton(enabled = email.isNotBlank() && !state.accountBusy, onClick = { sync.resetPassword(email) }) {
                Text("Reset password")
            }
            if (state.verificationRequired) {
                TextButton(onClick = sync::sendVerification, enabled = !state.accountBusy) {
                    Text("Resend verification email")
                }
            }
        } else {
            Text("Signed in as ${state.email}")
            if (state.pendingHistory + state.pendingResume > 0) {
                Text("${state.pendingHistory + state.pendingResume} changes waiting to sync.")
            }
            Button(onClick = sync::refresh) { Text("Sync now") }
            if (sync.historyEnabled && localGames.any { it.lichessGameId == null }) {
                TextButton(onClick = { sync.importLocalGames(localGames) }) { Text("Import local solo history") }
            }
            if (state.resumeConflict == null && state.recovery != null) {
                TextButton(onClick = { sync.recoverSnapshot(onRestore) }) { Text("Resume recovery copy") }
            }
            TextButton(onClick = sync::signOut) { Text("Sign out") }
            TextButton(onClick = { deleting = true }) { Text("Delete cloud account") }
        }
    }
    if (deleting) {
        var confirmation by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete cloud account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This deletes your cloud saves. Enter your password to continue.")
                    OutlinedTextField(
                        confirmation, { confirmation = it }, label = { Text("Password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = confirmation.isNotEmpty() && !state.accountBusy, onClick = {
                    sync.deleteAccount(confirmation)
                    deleting = false
                }) { Text("Delete account") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun CloudSaveHistory(sync: CloudSaveSync, onOpen: (SavedGame) -> Unit) {
    val state by sync.state.collectAsState()
    if (state.email == null || !sync.historyEnabled) return
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Cloud solo games", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = sync::refresh) { Text("Refresh cloud history") }
        state.message?.let { Text(it) }
        state.history.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { sync.openHistory(row.item.id, onOpen) }) {
                    Text("${row.item.white} vs ${row.item.black} · ${row.item.result}" + if (row.pending) " · Pending" else "")
                }
                TextButton(onClick = { sync.deleteHistory(row.item.id) }) { Text("Delete") }
            }
        }
        if (state.hasMoreHistory) TextButton(onClick = sync::loadMoreHistory) { Text("Load more") }
    }
}
