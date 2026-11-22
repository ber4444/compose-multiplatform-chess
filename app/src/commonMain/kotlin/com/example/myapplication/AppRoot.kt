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
import com.example.myapplication.explorer.HumanMoveExplorer
import com.example.myapplication.explorer.MaiaHumanMovePredictor
import com.example.myapplication.explorer.MaiaOpponent
import com.example.myapplication.explorer.RatingBand
import com.example.myapplication.explorer.forReview
import com.example.myapplication.explorer.maiaRuntimeFactory
import com.example.myapplication.chat.ChatScreen
import com.example.myapplication.chat.ChatViewModel
import com.example.myapplication.chat.createPositionChat
import com.example.myapplication.persistence.AppSettings
import com.example.myapplication.persistence.GameHistoryRepository
import com.example.myapplication.persistence.LocalAppSettings
import com.example.myapplication.persistence.Opponent
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
import com.example.myapplication.habits.HabitsManager
import com.example.ondeviceai.AiContextSnapshot
import com.example.ondeviceai.AiUserSetting
import com.example.ondeviceai.DefaultRulesQaOrchestrator
import com.example.ondeviceai.RetrievalOnlyRulesQaAnswerer
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
     * False on the web build, which hides the paid AI assistant surfaces: Position Chat and the
     * Opening Explainer, which are cloud-routed and billed per request (a Gemini call) against the
     * coach server. Free surfaces stay. The Move Coach runs deterministically. Rules Q&A answers from
     * the bundled rulebook (`RetrievalOnlyRulesQaAnswerer` on wasm), so its button follows whether an
     * answerer exists, not this flag.
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
    // "this build cannot answer rules questions": desktop and JS return null. Wasm answers from the
    // bundled rulebook alone.
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
                        // A retrieval-only answerer has no model behind it, whatever the platform
                        // probe reports (wasm offers LiteRT-LM unconditionally). Offering a vendor
                        // here would route on-device and badge rulebook text "Written by a model".
                        availableLocalVendors = if (it is RetrievalOnlyRulesQaAnswerer) {
                            emptyList()
                        } else {
                            com.example.ondeviceai.probeAvailableLocalVendors()
                        },
                        isAppForegrounded = true,
                        userSetting = AiUserSetting.OFFLINE_ONLY,
                    )
                }
            },
        )
    }
    // Unlike the AI managers below, this needs no platform runtime — it's a pure aggregation over
    // gameHistory — so AppRoot can own it directly instead of entry points threading it through.
    // Null exactly when gameHistory is null, mirroring how the Save/Share buttons already gate.
    // Its habits render at the top of the History screen; there is no separate Habits screen.
    val habitsManager = remember(gameHistory) { gameHistory?.let { HabitsManager(it) } }
    // One Maia predictor, so one model load, shared by the History explorer and the Maia opponent.
    // Null where the platform has no Maia runtime. Nothing loads until the first prediction.
    val maiaPredictor = remember { maiaRuntimeFactory()?.let { MaiaHumanMovePredictor(it) } }
    DisposableEffect(maiaPredictor) { onDispose { maiaPredictor?.close() } }
    val humanMoveExplorer = remember(maiaPredictor) { HumanMoveExplorer(maia = maiaPredictor) }
    // Settings → Opponent. Only the move choice moves to Maia; Stockfish stays attached for hints,
    // coach assessments and draw offers.
    val opponent by settings.opponent.collectAsState()
    // The same Easy/Medium/Hard/Max setting sets Maia's strength, mapped to a rating band.
    val opponentDifficulty by settings.engineDifficulty.collectAsState()
    DisposableEffect(viewModel, maiaPredictor, opponent, opponentDifficulty) {
        val chooser = maiaPredictor?.takeIf { opponent == Opponent.MAIA }
            ?.let { MaiaOpponent(it, RatingBand.forDifficulty(opponentDifficulty)) }
        viewModel.setOpponentMoveChooser(chooser)
        onDispose { viewModel.setOpponentMoveChooser(null) }
    }
    DisposableEffect(openingExplainerStateHolder) {
        onDispose { openingExplainerStateHolder?.close() }
    }
    DisposableEffect(chatViewModel) {
        onDispose { chatViewModel?.close() }
    }
    DisposableEffect(habitsManager) {
        onDispose { habitsManager?.close() }
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
                    // Rules costs nothing anywhere, so on the web it is hidden only when nothing can answer.
                    onOpenRules = if (aiAssistantsEnabled || rulesQaAnswerer != null) { { screen = Screen.RULES } } else null,
                    onOpenChat = if (aiAssistantsEnabled) { { screen = Screen.CHAT } } else null,
                    belowBoard = belowBoard,
                )
                Screen.HISTORY -> if (gameHistory != null) {
                    GameHistoryScreen(
                        gameHistory = gameHistory,
                        pgnSharer = pgnSharer,
                        habitsManager = habitsManager,
                        onBack = { screen = Screen.GAME },
                        humanMoves = humanMoveExplorer.forReview(),
                        humanMovesBand = RatingBand.forDifficulty(opponentDifficulty),
                    )
                } else {
                    SubScreenScaffold(title = "Game History", onBack = { screen = Screen.GAME }) {
                        Text("Game history is unavailable.")
                    }
                }
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { screen = Screen.GAME },
                    board3D = board3D,
                    maiaAvailable = maiaPredictor != null,
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
