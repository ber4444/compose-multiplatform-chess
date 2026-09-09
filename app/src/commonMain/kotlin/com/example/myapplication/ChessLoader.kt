package com.example.myapplication

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Spinner shown while a 3D backend starts up or tears down.
 *
 * [text] is rendered. It used to be accepted and dropped, so all four call sites — "Loading 3D
 * Engine", "Initializing Graphics", "Tearing down 3D board" — showed the same anonymous spinner,
 * and the two seconds a cold Filament start takes looked like a hang.
 *
 * Colours come from the scheme rather than a hardcoded black, since the overlay behind this now
 * follows the theme too and a black spinner disappeared into it in dark mode.
 */
@Composable
fun ChessLoader(text: String = "Loading 3D Board") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}
