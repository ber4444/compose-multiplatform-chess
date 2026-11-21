package com.example.myapplication

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.example.myapplication.opening.OpeningExplainerStateHolder
import com.example.myapplication.opening.createOpeningExplainer
import com.example.myapplication.opening.cloudCoachConfigured
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch
import com.example.myapplication.rules.RulesQaScreen
import com.example.myapplication.rules.RulesQaStateHolder
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.DefaultRulesQaOrchestrator
import com.example.ondeviceai.createBundledRuleLookupTool
import com.example.ondeviceai.defaultRulesQaAnswerer

/**
 * Top-level navigation host. Owns the single source of truth for the current screen, applies the
 * app theme (always follows the system dark-mode setting — the persisted theme override was
 * removed), and exposes [AppSettings] via [LocalAppSettings].
 *
 * Replaces the per-platform `MyApplicationTheme { ChessApp(...) }` duplication. New screens
 * (History, Settings) are added here as the lifecycle/persistence work lands.
 */
enum class Screen { GAME, HISTORY, SETTINGS, RULES, CHAT, LICENSES }

val LocalMoveCoachManager = staticCompositionLocalOf<MoveCoachManager?> { null }
val LocalOpeningExplainerStateHolder = staticCompositionLocalOf<OpeningExplainerStateHolder?> { null }
val LocalIsDebug = staticCompositionLocalOf { false }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(
    viewModel: GameViewModel,
    settings: AppSettings,
    board3D: Board3DSupport? = null,
    gameHistory: GameHistoryRepository? = null,
    pgnSharer: PgnSharer? = null,
    moveCoachManager: MoveCoachManager? = null,
    switchTopPadding: Dp = 8.dp,
    isDebug: Boolean = false,
    /**
     * False on the web build, which hides the AI assistant surfaces: Position Chat and the Opening
     * Explainer, which are cloud-routed and billed per request against the coach server, and Rules
     * Q&A, which has no answerer on wasm anyway. The Move Coach is not one of them: it runs
     * deterministically, offline and free, so it stays.
     */
    aiAssistantsEnabled: Boolean = true,
    /** Drawn under the board and its controls on the game screen. The web build passes its store links. */
    belowBoard: (@Composable () -> Unit)? = null,
) {
    // Not constructed at all when the assistants are off, so the web page builds no cloud client.
    val openingExplainerStateHolder = remember(aiAssistantsEnabled) {
        if (aiAssistantsEnabled) OpeningExplainerStateHolder(createOpeningExplainer()) else null
    }
    val chatViewModel = remember(aiAssistantsEnabled) {
        if (aiAssistantsEnabled) ChatViewModel(createPositionChat()) else null
    }
    // Hoisted out of the RulesQaStateHolder construction below because a null answerer also means
    // "this build cannot answer rules questions" — desktop, wasm and JS return null unconditionally.
    val ruleLookupTool = remember { createBundledRuleLookupTool() }
    val rulesQaAnswerer = remember { defaultRulesQaAnswerer(ruleLookupTool) }
    val rulesQaStateHolder = remember {
        RulesQaStateHolder(
            rulesQaAnswerer?.let {
                DefaultRulesQaOrchestrator(
                    answerer = it,
                    lookupTool = ruleLookupTool,
                ) {
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
        onDispose { openingExplainerStateHolder?.close() }
    }
    DisposableEffect(chatViewModel) {
        onDispose { chatViewModel?.close() }
    }
    CompositionLocalProvider(
        LocalAppSettings provides settings,
        LocalMoveCoachManager provides moveCoachManager,
        LocalOpeningExplainerStateHolder provides openingExplainerStateHolder,
        LocalIsDebug provides isDebug,
    ) {
        MyApplicationTheme(darkTheme = isSystemInDarkTheme()) {
            var screen by rememberSaveable { mutableStateOf(Screen.GAME) }
            // Licences is the one screen nested under another (Settings), so back returns there.
            BackHandler(enabled = screen != Screen.GAME) {
                screen = if (screen == Screen.LICENSES) Screen.SETTINGS else Screen.GAME
            }

            // Bridge the persisted engine-difficulty setting → the VM (issue #39 Phase 4). The VM
            // seeds its initial value at construction; this forwards subsequent changes from
            // SettingsScreen, applying them to the attached engine via setEngineDifficulty.
            // One effect, three collectors: the bridges share a lifetime, so they need not each
            // pay for an effect node and a coroutine launch.
            LaunchedEffect(Unit) {
                launch { settings.engineDifficulty.collect { viewModel.setEngineDifficulty(it) } }
                launch { settings.aiCoachEnabled.collect { viewModel.aiCoachEnabled = it } }
                launch {
                    settings.playerSide.collect { sideStr ->
                        val side = if (sideStr == "BLACK") Set.BLACK else Set.WHITE
                        viewModel.playerSide = side
                    }
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
                    onOpenRules = if (aiAssistantsEnabled) { { screen = Screen.RULES } } else null,
                    onOpenChat = if (aiAssistantsEnabled) { { screen = Screen.CHAT } } else null,
                    belowBoard = belowBoard,
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
                    onOpenLicenses = { screen = Screen.LICENSES },
                )
                Screen.LICENSES -> LicensesScreen(onBack = { screen = Screen.SETTINGS })
                // RulesQaScreen reports itself unavailable when there is no answerer, so it is
                // routed to unconditionally.
                Screen.RULES -> RulesQaScreen(
                    stateHolder = rulesQaStateHolder,
                    onBack = { screen = Screen.GAME },
                )
                // Chat is cloud-only, so without a base URL it could only ever emit its fixed offline
                // sentence. Say that instead of pretending the input box works.
                Screen.CHAT -> if (cloudCoachConfigured && chatViewModel != null) {
                    // Collected here, not at the top of AppRoot: only Chat reads the position, and a
                    // top-level collector re-ran this whole host on every move and selection tap.
                    val gameState by viewModel.gameState.collectAsState()
                    ChatScreen(
                        viewModel = chatViewModel,
                        gameState = gameState,
                        onBack = { screen = Screen.GAME },
                    )
                } else {
                    SubScreenScaffold(title = "Position Chat", onBack = { screen = Screen.GAME }) {
                        Text(
                            "Position Chat needs a coach server, and this build has none configured.",
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
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (!isAndroidPlatform) {
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
                .consumeWindowInsets(padding)
                .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) { content() }
    }
}
