package com.example.myapplication

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.chat.ChatScreen
import com.example.myapplication.chat.ChatViewModel
import com.example.myapplication.chat.createPositionChat
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.share.PgnSharer
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.movecoach.MoveCoachManager
import com.example.myapplication.movecoach.GameSummaryManager
import com.example.myapplication.opening.OpeningExplainerStateHolder
import com.example.myapplication.opening.createOpeningExplainer
import com.example.myapplication.opening.cloudCoachConfigured
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.myapplication.rules.RulesQaScreen
import com.example.myapplication.rules.RulesQaStateHolder
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.DefaultRulesQaOrchestrator
import com.example.ondeviceai.createBundledRuleLookupTool
import com.example.ondeviceai.defaultRulesQaAnswerer
import com.example.myapplication.monetization.Entitlements
import com.example.myapplication.monetization.LocalEntitlements
import com.example.myapplication.monetization.UnconfiguredEntitlements
import com.example.myapplication.monetization.PaywallScreen
import com.example.myapplication.monetization.ProUpsellCard
import com.example.myapplication.monetization.isProUnlocked

/**
 * Top-level navigation host. Owns the single source of truth for the current screen, applies the
 * app theme (always follows the system dark-mode setting — the persisted theme override was
 * removed), and exposes [AppSettings] via [LocalAppSettings].
 *
 * Replaces the per-platform `MyApplicationTheme { ChessApp(...) }` duplication. New screens
 * (History, Settings) are added here as the lifecycle/persistence work lands.
 */
enum class Screen { GAME, HISTORY, SETTINGS, RULES, CHAT, PAYWALL }

val LocalMoveCoachManager = staticCompositionLocalOf<MoveCoachManager?> { null }
val LocalGameSummaryManager = staticCompositionLocalOf<GameSummaryManager?> { null }
val LocalOpeningExplainerStateHolder = staticCompositionLocalOf<OpeningExplainerStateHolder?> { null }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(
    viewModel: GameViewModel,
    settings: AppSettings,
    board3D: Board3DSupport? = null,
    gameHistory: GameHistoryRepository? = null,
    pgnSharer: PgnSharer? = null,
    moveCoachManager: MoveCoachManager? = null,
    gameSummaryManager: GameSummaryManager? = null,
    // The default is deliberately the locked, SDK-free implementation: previews, Compose UI tests,
    // and any caller that omits this argument must not configure a billing SDK or hit the network.
    // Android/iOS pass RevenueCatEntitlements.createOrNull(...); desktop/wasm pass a locked
    // NoOpEntitlements seeded from AppSettings. purchase() here fails rather than granting Pro.
    entitlements: Entitlements = remember { UnconfiguredEntitlements() },
    switchTopPadding: Dp = 8.dp,
    forceProUnlocked: Boolean = false,
) {
    val openingExplainerStateHolder = remember { OpeningExplainerStateHolder(createOpeningExplainer()) }
    val chatViewModel = remember { ChatViewModel(createPositionChat()) }
    val gameState by viewModel.gameState.collectAsState()
    // Hoisted out of the RulesQaStateHolder construction below because a null answerer is also the
    // "don't sell this" signal for the Pro gate — desktop, wasm and JS return null unconditionally.
    val rulesQaAnswerer = remember { defaultRulesQaAnswerer(createBundledRuleLookupTool()) }
    val rulesQaStateHolder = remember {
        RulesQaStateHolder(
            rulesQaAnswerer?.let {
                DefaultRulesQaOrchestrator(it) {
                    AiContextSnapshot(
                        availableLocalVendors = com.example.ondeviceai.probeAvailableLocalVendors(),
                        isAppForegrounded = true,
                        userSetting = AiUserSetting.OFFLINE_ONLY,
                    )
                }
            },
        )
    }
    DisposableEffect(openingExplainerStateHolder) {
        onDispose { openingExplainerStateHolder.close() }
    }
    DisposableEffect(chatViewModel) {
        onDispose { chatViewModel.close() }
    }
    CompositionLocalProvider(
        LocalAppSettings provides settings,
        LocalEntitlements provides entitlements,
        LocalMoveCoachManager provides moveCoachManager,
        LocalGameSummaryManager provides gameSummaryManager,
        LocalOpeningExplainerStateHolder provides openingExplainerStateHolder,
    ) {
        MyApplicationTheme(darkTheme = isSystemInDarkTheme()) {
            var screen by rememberSaveable { mutableStateOf(Screen.GAME) }
            BackHandler(enabled = screen != Screen.GAME) { screen = Screen.GAME }

            // Bridge the persisted engine-difficulty setting → the VM (issue #39 Phase 4). The VM
            // seeds its initial value at construction; this forwards subsequent changes from
            // SettingsScreen, applying them to the attached engine via setEngineDifficulty.
            LaunchedEffect(Unit) {
                settings.engineDifficulty.collect { viewModel.setEngineDifficulty(it) }
            }
            LaunchedEffect(Unit) {
                settings.aiCoachEnabled.collect { viewModel.aiCoachEnabled = it }
            }
            // Bridge the Pro entitlement → the coach manager, mirroring the aiCoachEnabled bridge
            // above. Free keeps the deterministic coach; Pro gets the model-phrased one. The
            // manager can't read LocalEntitlements itself — entry points construct it before any
            // composition exists.
            LaunchedEffect(entitlements, moveCoachManager, forceProUnlocked) {
                entitlements.isProUnlocked.collect { moveCoachManager?.proUnlocked = if (forceProUnlocked) true else it }
            }
            LaunchedEffect(Unit) {
                settings.playerSide.collect { sideStr -> 
                    val side = if (sideStr == "BLACK") Set.BLACK else Set.WHITE
                    viewModel.playerSide = side
                }
            }

            when (screen) {
                Screen.GAME -> ChessApp(
                    viewModel = viewModel,
                    board3D = board3D,
                    gameHistory = gameHistory,
                    pgnSharer = pgnSharer,
                    switchTopPadding = switchTopPadding,
                    onOpenHistory = { screen = Screen.HISTORY },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onOpenRules = { screen = Screen.RULES },
                    onOpenChat = { screen = Screen.CHAT },
                    onOpenPaywall = { screen = Screen.PAYWALL },
                )
                Screen.HISTORY -> if (gameHistory != null) {
                    GameHistoryScreen(
                        gameHistory = gameHistory,
                        pgnSharer = pgnSharer,
                        onBack = { screen = Screen.GAME },
                    )
                } else {
                    SubScreenScaffold(title = "Game History", onBack = { screen = Screen.GAME }) {
                        Text("Game history is unavailable.")
                    }
                }
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { screen = Screen.GAME },
                    board3D = board3D,
                )
                // Branching here rather than wrapping in ProGate: RulesQaScreen supplies its own
                // SubScreenScaffold, so nesting would render two title bars when unlocked.
                //
                // `rulesQaAnswerer == null` short-circuits the gate for the same reason ProGate's
                // `available` flag does — on a build with no answerer the feature stays dead after a
                // purchase, so it must not be sold. RulesQaScreen already says so itself.
                Screen.RULES -> if (rulesQaAnswerer == null || isProUnlocked()) {
                    RulesQaScreen(
                        stateHolder = rulesQaStateHolder,
                        onBack = { screen = Screen.GAME },
                    )
                } else {
                    SubScreenScaffold(title = "Chess rules", onBack = { screen = Screen.GAME }) {
                        ProUpsellCard(
                            featureName = "Rules Q&A",
                            pitch = "Ask any rules question and get an answer cited to the " +
                                "rulebook, entirely on your device.",
                            onOpenPaywall = { screen = Screen.PAYWALL },
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                Screen.PAYWALL -> PaywallScreen(onClose = { screen = Screen.GAME })
                Screen.CHAT -> when {
                    // Unavailable beats both branches: chat is cloud-only, so without a base URL
                    // it can only ever emit its fixed offline sentence. Say that instead of either
                    // selling it or pretending the input box works.
                    !cloudCoachConfigured ->
                        SubScreenScaffold(title = "Position Chat", onBack = { screen = Screen.GAME }) {
                            Text(
                                "Position Chat needs a coach server, and this build has none configured.",
                                modifier = Modifier.padding(16.dp),
                            )
                        }

                    isProUnlocked() -> ChatScreen(
                        viewModel = chatViewModel,
                        gameState = gameState,
                        onBack = { screen = Screen.GAME },
                    )

                    else -> SubScreenScaffold(title = "Position Chat", onBack = { screen = Screen.GAME }) {
                        ProUpsellCard(
                            featureName = "Position Chat",
                            pitch = "Ask about the position you're in and get grounded answers as you play.",
                            onOpenPaywall = { screen = Screen.PAYWALL },
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Shared Material3 scaffold for the secondary screens so back-navigation is consistent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    scrollable: Boolean = true,
    showBackButton: Boolean = true,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBackButton) {
                        Button(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) { content() }
    }
}
