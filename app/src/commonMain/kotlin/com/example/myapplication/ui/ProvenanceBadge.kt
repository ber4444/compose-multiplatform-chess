package com.example.myapplication.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ondeviceai.AiRoute

@Composable
fun ProvenanceBadge(
    route: AiRoute,
    modifier: Modifier = Modifier,
) {
    val emoji = when (route) {
        is AiRoute.Fallback -> "⚙️"
        is AiRoute.OnDevice -> "📱"
        is AiRoute.Cloud -> "☁️"
    }

    val text = when (route) {
        is AiRoute.Fallback -> "Engine-derived"
        is AiRoute.OnDevice -> "Model-phrased • Never left your device"
        is AiRoute.Cloud -> "Model-phrased"
    }

    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
