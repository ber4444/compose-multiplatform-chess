package com.example.myapplication

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/** Same URLs as the README. */
internal const val GOOGLE_PLAY_URL = "https://play.google.com/store/apps/details?id=io.github.ber4444.chess"
internal const val TESTFLIGHT_URL = "https://testflight.apple.com/join/sXp1vXd5"

/**
 * The web build's pointer to the mobile apps, shown under the board (`AppRoot(belowBoard = …)`).
 *
 * The web build hides the AI assistants (`aiAssistantsEnabled = false`), so this line says where
 * they are instead of leaving them silently missing. Links open through the platform
 * `UriHandler`, which on wasm is a new browser tab.
 */
@Composable
fun MobileAppLinks(modifier: Modifier = Modifier) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold),
        hoveredStyle = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val text = buildAnnotatedString {
        append("AI coaching features are in the mobile apps: ")
        withLink(LinkAnnotation.Url(GOOGLE_PLAY_URL, linkStyles)) { append("Google Play") }
        append(" · ")
        withLink(LinkAnnotation.Url(TESTFLIGHT_URL, linkStyles)) { append("iOS (TestFlight)") }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .testTag("mobile_app_links"),
    )
}
