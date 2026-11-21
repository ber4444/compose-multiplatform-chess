package com.example.myapplication

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myapplication.board3d.Board3DSupport
import com.example.myapplication.multiplayer.LichessArchiveClient
import com.example.myapplication.multiplayer.LichessAuth
import com.example.myapplication.multiplayer.LichessBoardClient
import com.example.myapplication.multiplayer.LichessHistory
import com.example.myapplication.multiplayer.LichessHistoryCache
import com.example.myapplication.multiplayer.LichessRequestGate
import com.example.myapplication.multiplayer.OnlineMatchController
import com.example.myapplication.multiplayer.OnlineMatchReference
import com.example.myapplication.multiplayer.OnlineScreen
import com.example.myapplication.multiplayer.createLichessHttpClient
import com.example.myapplication.multiplayer.onlineOAuthPlatform
import com.example.myapplication.opening.FIREBASE_WEB_API_KEY
import com.example.myapplication.opening.LICHESS_MULTIPLAYER_ENABLED
import com.example.myapplication.opening.SOLO_HISTORY_SYNC_ENABLED
import com.example.myapplication.opening.SOLO_RESUME_SYNC_ENABLED
import com.example.myapplication.opening.SOLO_SAVES_BASE_URL
import com.example.myapplication.persistence.CurrentGameStore
import com.example.myapplication.persistence.cloud.CloudSaveHistory
import com.example.myapplication.persistence.cloud.CloudSaveSettings
import com.example.myapplication.persistence.cloud.CloudSaveSync
import com.example.saveapi.CloudResumeGame
import kotlinx.serialization.json.Json
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
enum class Screen { GAME, HISTORY, SETTINGS, RULES, CHAT, LICENSES, ONLINE }

val LocalMoveCoachManager = staticCompositionLocalOf<MoveCoachManager?> { null }
val LocalOpeningExplainerStateHolder = staticCompositionLocalOf<OpeningExplainerStateHolder?> { null }
val LocalIsDebug = staticCompositionLocalOf { false }
private const val ONLINE_MATCH_KEY = "lichess.unfinished.v1"

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
) {
    val openingExplainerStateHolder = remember { OpeningExplainerStateHolder(createOpeningExplainer()) }
    val chatViewModel = remember { ChatViewModel(createPositionChat()) }
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
    val coroutineScope = rememberCoroutineScope()
    val uriHandler by rememberUpdatedState(LocalUriHandler.current)
    val onlineHttp = remember { if (LICHESS_MULTIPLAYER_ENABLED) createLichessHttpClient() else null }
    val lichessGate = remember { LichessRequestGate() }
    val online = remember(onlineHttp, viewModel, settings) {
        onlineHttp?.let { http ->
            val savedMatch = settings.settings.getStringOrNull(ONLINE_MATCH_KEY)?.let {
                runCatching { Json.decodeFromString<OnlineMatchReference>(it) }.getOrNull()
            }
            OnlineMatchController(
                scope = coroutineScope,
                auth = LichessAuth(http, onlineOAuthPlatform { uriHandler.openUri(it) }, gate = lichessGate),
                board = { LichessBoardClient(http, it, gate = lichessGate) },
                savedMatch = savedMatch,
                persistMatch = {
                    if (it == null) settings.settings.remove(ONLINE_MATCH_KEY)
                    else settings.settings.putString(ONLINE_MATCH_KEY, Json.encodeToString(it))
                },
                onAssistanceBlocked = { blocked ->
                    if (blocked) {
                        viewModel.setSoloPaused(true)
                        moveCoachManager?.hideWindow()
                        openingExplainerStateHolder.reset()
                        chatViewModel.stop()
                    }
                },
            )
        }
    }
    val onlineState = online?.state?.collectAsState()?.value
    val assistanceBlocked = onlineState?.assistanceBlocked == true
    val archive = remember(onlineHttp) { onlineHttp?.let { LichessArchiveClient(it, lichessGate) } }
    val archiveCache = remember { LichessHistoryCache() }
    val cloudSync = remember(settings) {
        if ((SOLO_HISTORY_SYNC_ENABLED || SOLO_RESUME_SYNC_ENABLED) &&
            SOLO_SAVES_BASE_URL.startsWith("https://") && FIREBASE_WEB_API_KEY.isNotBlank()
        ) {
            CloudSaveSync.create(settings.settings, SOLO_SAVES_BASE_URL, FIREBASE_WEB_API_KEY,
                SOLO_HISTORY_SYNC_ENABLED, SOLO_RESUME_SYNC_ENABLED)
        } else null
    }
    val resumeConflict = cloudSync?.state?.collectAsState()?.value?.resumeConflict
    DisposableEffect(online) {
        if (online?.state?.value?.assistanceBlocked == true) viewModel.setSoloPaused(true)
        onDispose {
            online?.close()
            onlineHttp?.close()
        }
    }
    DisposableEffect(viewModel, cloudSync) {
        if (cloudSync != null) {
            val side = if (settings.playerSide.value == "BLACK") Set.BLACK else Set.WHITE
            cloudSync.seedLocalSnapshot(CurrentGameStore(settings.settings).load(), side)
            viewModel.setSnapshotObserver(cloudSync::recordSnapshot)
        }
        onDispose {
            viewModel.setSnapshotObserver(null)
            cloudSync?.close()
        }
    }
    DisposableEffect(openingExplainerStateHolder) {
        onDispose { openingExplainerStateHolder.close() }
    }
    DisposableEffect(chatViewModel) {
        onDispose { chatViewModel.close() }
    }
    CompositionLocalProvider(
        LocalAppSettings provides settings,
        LocalMoveCoachManager provides moveCoachManager.takeUnless { assistanceBlocked },
        LocalOpeningExplainerStateHolder provides openingExplainerStateHolder.takeUnless { assistanceBlocked },
        LocalIsDebug provides isDebug,
    ) {
        MyApplicationTheme(darkTheme = isSystemInDarkTheme()) {
            var screen by rememberSaveable { mutableStateOf(Screen.GAME) }
            val displayedScreen = if (assistanceBlocked) Screen.ONLINE else screen
            SideEffect {
                viewModel.setSoloPaused(displayedScreen == Screen.ONLINE || resumeConflict != null)
            }
            LaunchedEffect(assistanceBlocked) {
                if (assistanceBlocked) screen = Screen.ONLINE
            }
            LaunchedEffect(displayedScreen) {
                if (displayedScreen == Screen.ONLINE) {
                    moveCoachManager?.hideWindow()
                    openingExplainerStateHolder.reset()
                    chatViewModel.stop()
                }
            }
            val restoreSolo: (CloudResumeGame?) -> Boolean = restore@{ remote ->
                val side = remote?.playerSide ?: viewModel.playerSide
                try {
                    viewModel.restoreSoloSnapshot(remote?.snapshot, side)
                } catch (_: Exception) {
                    cloudSync?.reportRestoreFailure()
                    screen = Screen.SETTINGS
                    return@restore false
                }
                runCatching { settings.setPlayerSide(side.name) }
                screen = Screen.GAME
                true
            }
            if (cloudSync != null && resumeConflict != null && displayedScreen != Screen.ONLINE) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Choose a solo game") },
                    text = { Text("Resume one game and keep the other as a recovery copy.") },
                    confirmButton = {
                        TextButton(onClick = { cloudSync.resolveResume(true, restoreSolo) }) {
                            Text("Resume cloud game")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { cloudSync.resolveResume(false, restoreSolo) }) {
                            Text("Keep this device’s game")
                        }
                    },
                )
            }
            val leaveOnline = {
                if (!assistanceBlocked) {
                    online?.closeMatch()
                    screen = Screen.GAME
                }
            }
            BackHandler(enabled = displayedScreen != Screen.GAME) {
                when (displayedScreen) {
                    Screen.ONLINE -> leaveOnline()
                    Screen.LICENSES -> screen = Screen.SETTINGS
                    else -> screen = Screen.GAME
                }
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

            when (displayedScreen) {
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
                    onOpenOnline = online?.let {
                        {
                            viewModel.setSoloPaused(true)
                            screen = Screen.ONLINE
                        }
                    },
                    onSaveFinished = cloudSync?.let { sync ->
                        { game -> if (!sync.saveFinished(game)) gameHistory?.add(game) }
                    },
                )
                Screen.HISTORY -> if (gameHistory != null) {
                    GameHistoryScreen(
                        gameHistory = gameHistory,
                        pgnSharer = pgnSharer,
                        onBack = { screen = Screen.GAME },
                        extraHistory = if (archive != null || cloudSync != null) {
                            { games, onOpen ->
                                val profile = onlineState?.profile
                                if (archive != null && profile != null) {
                                    LichessHistory(profile, archive, archiveCache, games, gameHistory, onOpen)
                                }
                                if (cloudSync != null) CloudSaveHistory(cloudSync, onOpen)
                            }
                        } else null,
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
                    extraSettings = cloudSync?.let { sync ->
                        {
                            val games = gameHistory?.games?.collectAsState()?.value.orEmpty()
                            CloudSaveSettings(sync, games, restoreSolo)
                        }
                    },
                )
                Screen.ONLINE -> if (online != null && onlineState != null) {
                    val use3D by settings.board3DEnabled.collectAsState()
                    OnlineScreen(onlineState, online, board3D, use3D, gameHistory, pgnSharer, leaveOnline)
                } else {
                    SubScreenScaffold(title = "Play Online", onBack = leaveOnline) {
                        Text("Online play is unavailable in this build.")
                    }
                }
                Screen.LICENSES -> LicensesScreen(onBack = { screen = Screen.SETTINGS })
                // RulesQaScreen reports itself unavailable when there is no answerer, so it is
                // routed to unconditionally.
                Screen.RULES -> RulesQaScreen(
                    stateHolder = rulesQaStateHolder,
                    onBack = { screen = Screen.GAME },
                )
                // Chat is cloud-only, so without a base URL it could only ever emit its fixed offline
                // sentence. Say that instead of pretending the input box works.
                Screen.CHAT -> if (cloudCoachConfigured) {
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
    showBackButton: Boolean = !isAndroidPlatform,
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
                .consumeWindowInsets(padding)
                .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) { content() }
    }
}
