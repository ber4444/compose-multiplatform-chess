package com.example.myapplication.persistence.cloud

import com.example.myapplication.GameSnapshot
import com.example.myapplication.Set
import com.example.myapplication.WinState
import com.example.myapplication.opening.openingExplainerHttpClientEngine
import com.example.myapplication.persistence.SavedGame
import com.example.saveapi.CloudHistoryItem
import com.example.saveapi.CloudResumeGame
import com.example.saveapi.CloudSavedGame
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.util.sha1
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class CloudHistoryRow(val item: CloudHistoryItem, val pending: Boolean)

data class CloudResumeConflict(val local: CloudResumeGame?, val remote: RemoteResume)

data class CloudSaveState(
    val email: String? = null,
    val verificationRequired: Boolean = false,
    val accountBusy: Boolean = false,
    val message: String? = null,
    val history: List<CloudHistoryRow> = emptyList(),
    val hasMoreHistory: Boolean = false,
    val pendingHistory: Int = 0,
    val pendingResume: Int = 0,
    val resumeConflict: CloudResumeConflict? = null,
    val recovery: CloudResumeGame? = null,
)

/**
 * Optional solo sync. Account and UI actions are confined to the app's main dispatcher. Snapshot
 * notifications can come from the chess worker and enter through a single ordered channel.
 */
class CloudSaveSync internal constructor(
    private val auth: CloudIdentity,
    private val api: CloudSaveTransport,
    private val storage: CloudSaveJournal,
    val historyEnabled: Boolean,
    val resumeEnabled: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val closeHttp: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val snapshots = Channel<SnapshotEvent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(CloudSaveState())
    val state: StateFlow<CloudSaveState> = _state

    private var session: FirebaseSession? = null
    private var unverified: FirebaseSession? = null
    private var generation = 0
    private var accountJob: Job? = null
    private var syncJob: Job? = null
    private var openJob: Job? = null
    private var journal = CloudJournal()
    private var local: CloudResumeGame? = null
    private var resumeInitialized = false
    private var historyRefresh = false
    private var resumeRefresh = false
    private var historyPages = 1
    private var hasMore = false

    init {
        if (resumeEnabled) scope.launch {
            for (event in snapshots) acceptSnapshot(event)
        }
    }

    fun signIn(email: String, password: String) {
        signOut()
        accountAction {
            val signedIn = auth.signIn(email.trim(), password)
            if (!signedIn.verified) {
                unverified = signedIn
                _state.value = _state.value.copy(verificationRequired = true, message = "Verify your email, then sign in again.")
                return@accountAction
            }
            if (api.account(signedIn.idToken).uid != signedIn.uid) throw CloudSaveException(401)
            val persisted = storage.read(signedIn.uid)
            session = signedIn
            journal = persisted
            _state.value = _state.value.copy(email = email.trim())
            historyRefresh = historyEnabled
            resumeRefresh = resumeEnabled
            publish()
            scheduleSync()
        }
    }

    fun register(email: String, password: String) = accountAction {
        auth.register(email.trim(), password)
        message("Check your email for a verification link, then sign in.")
    }

    fun sendVerification() {
        val pending = unverified ?: return
        accountAction {
            auth.sendVerification(auth.refreshIfNeeded(pending))
            message("Verification email sent.")
        }
    }

    fun resetPassword(email: String) = accountAction {
        auth.resetPassword(email.trim())
        message("Check your email for password reset instructions.")
    }

    fun deleteAccount(password: String) {
        val owner = session?.uid ?: return
        val email = state.value.email ?: return
        accountAction {
            val recent = auth.signIn(email, password)
            if (!recent.verified || recent.uid != owner) throw CloudSaveException(403)
            syncJob?.cancel()
            syncJob = null
            openJob?.cancel()
            try {
                api.deleteAccount(recent.idToken)
                storage.clear(owner)
                signOut(cancelAccountAction = false)
                message("Cloud account deleted.")
            } finally {
                scheduleSync()
            }
        }
    }

    fun signOut() = signOut(cancelAccountAction = true)

    private fun signOut(cancelAccountAction: Boolean) {
        if (cancelAccountAction) accountJob?.cancel()
        syncJob?.cancel()
        openJob?.cancel()
        syncJob = null
        openJob = null
        generation++
        session = null
        unverified = null
        journal = CloudJournal()
        resumeInitialized = false
        historyRefresh = false
        resumeRefresh = false
        historyPages = 1
        hasMore = false
        _state.value = CloudSaveState()
    }

    /**
     * Returns true only when the account journal durably accepted the record. A caller can retain a
     * local copy on false, including when signed out or when device storage is full.
     */
    fun saveFinished(game: SavedGame): Boolean {
        if (!historyEnabled || session == null || game.lichessGameId != null) return false
        val saved = game.toCloud(Uuid.random().toString())
        if (!fits(saved)) {
            message("This game is too large for cloud sync. A local copy can still be saved.")
            return false
        }
        return change { it.copy(history = it.history + HistoryWrite(saved.id, saved)) }
    }

    fun importLocalGames(games: List<SavedGame>) {
        if (!historyEnabled || session == null) return
        val queued = journal.history.mapTo(mutableSetOf(), HistoryWrite::id)
        val incoming = games.asSequence()
            .filter { it.lichessGameId == null }
            .map { it.toCloud(legacyId(it)) }
            .filter { it.id !in journal.importedIds && queued.add(it.id) && fits(it) }
            .map { HistoryWrite(it.id, it) }
            .toList()
        if (incoming.isNotEmpty()) change { it.copy(history = it.history + incoming) }
    }

    fun deleteHistory(id: String) {
        if (session == null || state.value.history.none { it.item.id == id }) return
        change {
            it.copy(history = it.history + HistoryWrite(id, null), details = it.details.filterNot { game -> game.id == id })
        }
    }

    fun openHistory(id: String, onOpen: (SavedGame) -> Unit) {
        if (session == null || state.value.history.none { it.item.id == id }) return
        val available = journal.history.lastOrNull { it.id == id }?.game ?: journal.details.firstOrNull { it.id == id }
        if (available != null) {
            onOpen(available.toLocal())
            return
        }
        val version = generation
        openJob?.cancel()
        openJob = scope.launch {
            try {
                val game = api.game(token(version), id)
                checkSession(version)
                if (state.value.history.none { it.item.id == id }) return@launch
                persist(journal.withDetail(game))
                onOpen(game.toLocal())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == generation) message("Unable to load this cloud game. Try again when connected.")
            }
        }
    }

    fun loadMoreHistory() {
        if (!hasMore || !historyEnabled || historyPages >= MAX_HISTORY_PAGES) return
        historyPages++
        refresh()
    }

    fun refresh() {
        if (session == null) return
        historyRefresh = historyEnabled
        resumeRefresh = resumeEnabled
        syncJob?.cancel()
        syncJob = null
        scheduleSync()
    }

    fun seedLocalSnapshot(snapshot: GameSnapshot?, playerSide: Set) {
        if (resumeEnabled) snapshots.trySend(SnapshotEvent(snapshot, playerSide, upload = false))
    }

    fun recordSnapshot(snapshot: GameSnapshot?, playerSide: Set) {
        if (resumeEnabled) snapshots.trySend(SnapshotEvent(snapshot, playerSide, upload = true))
    }

    fun resolveResume(useCloud: Boolean, onRestore: (CloudResumeGame?) -> Unit) {
        val conflict = state.value.resumeConflict ?: return
        val chosen = if (useCloud) conflict.remote.game else conflict.local
        val other = if (useCloud) conflict.local else conflict.remote.game
        val queue = if (useCloud || chosen.samePosition(conflict.remote.game)) emptyList() else listOf(resumeWrite(chosen))
        val updated = journal.copy(
            localResume = chosen,
            resumeRevision = conflict.remote.revision,
            resumeWrites = queue,
            recovery = other ?: journal.recovery,
        )
        if (!persist(updated)) return
        local = chosen
        _state.value = _state.value.copy(resumeConflict = null)
        publish()
        if (useCloud) onRestore(chosen)
        scheduleSync()
    }

    fun recoverSnapshot(onRestore: (CloudResumeGame?) -> Unit) {
        val recovery = journal.recovery ?: return
        if (state.value.resumeConflict != null) return
        val updated = journal.copy(
            localResume = recovery,
            resumeWrites = journal.resumeWrites.enqueue(resumeWrite(recovery)),
            recovery = local,
        )
        if (!persist(updated)) return
        local = recovery
        publish()
        onRestore(recovery)
        scheduleSync()
    }

    internal fun reportRestoreFailure() {
        message("The selected game could not be restored on this device. Free local storage and sync again.")
    }

    fun close() {
        snapshots.close()
        scope.cancel()
        closeHttp()
    }

    private fun acceptSnapshot(event: SnapshotEvent) {
        val next = event.snapshot?.takeIf { it.winState == WinState.NONE }?.let { snapshot ->
            val id = local?.gameId ?: Uuid.random().toString()
            CloudResumeGame(id, event.playerSide, snapshot)
        }
        val changed = !next.samePosition(local)
        local = next
        if (session == null || !event.upload || !changed) return
        val conflict = state.value.resumeConflict
        if (conflict != null) {
            _state.value = _state.value.copy(resumeConflict = conflict.copy(local = local))
            return
        }
        change { it.copy(localResume = next, resumeWrites = it.resumeWrites.enqueue(resumeWrite(next))) }
    }

    private fun change(update: (CloudJournal) -> CloudJournal): Boolean {
        if (!persist(update(journal))) return false
        publish()
        scheduleSync()
        return true
    }

    private fun persist(updated: CloudJournal): Boolean {
        val owner = session?.uid ?: return false
        return try {
            storage.write(owner, updated)
            journal = updated
            true
        } catch (_: Exception) {
            message("Device storage could not save a cloud sync change. Free some space and retry.")
            false
        }
    }

    private fun accountAction(block: suspend () -> Unit) {
        val version = generation
        _state.value = _state.value.copy(accountBusy = true, message = null)
        accountJob?.cancel()
        accountJob = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == generation) message("Cloud account request failed. Check your details and connection, then retry.")
            } finally {
                if (version == generation) _state.value = _state.value.copy(accountBusy = false)
            }
        }
    }

    private fun scheduleSync() {
        if (session == null || syncJob?.isActive == true) return
        val version = generation
        syncJob = scope.launch {
            var backoff = 1_000L
            while (version == generation) {
                try {
                    synchronize(version)
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (version != generation) break
                    message("Cloud sync paused. Changes on this device are kept for retry.")
                    if (failure is CloudSaveException && failure.status !in 500..599 && failure.status != 429) break
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun synchronize(version: Int) {
        if (resumeEnabled && (!resumeInitialized || resumeRefresh)) {
            val remote = api.current(token(version))
            checkSession(version)
            resumeRefresh = false
            if (!reconcile(remote)) return
        }
        if (historyEnabled && !drainHistory(version)) return
        if (resumeEnabled && state.value.resumeConflict == null && !drainResume(version)) return
        if (historyEnabled && historyRefresh) {
            val newItems = mutableListOf<CloudHistoryItem>()
            var cursor: String? = null
            for (pageNumber in 0 until historyPages) {
                val page = api.history(token(version), cursor)
                checkSession(version)
                newItems += page.items
                cursor = page.nextCursor
                if (cursor == null) break
            }
            if (!persist(journal.copy(historyCache = newItems.distinctBy(CloudHistoryItem::id)))) return
            historyRefresh = false
            hasMore = cursor != null && historyPages < MAX_HISTORY_PAGES
            publish()
        }
        if (journal.history.isNotEmpty() || (state.value.resumeConflict == null && journal.resumeWrites.isNotEmpty())) {
            // A move or save can arrive while the listing is in flight.
            synchronize(version)
        }
    }

    private fun reconcile(remote: RemoteResume): Boolean {
        resumeInitialized = true
        val queued = journal.resumeWrites
        val base = journal.resumeRevision
        if (queued.isNotEmpty() && journal.localResume.samePosition(local) && base != null) {
            // Retry a claimed write with its original ETag and operation ID, even if GET has seen
            // its result already. A conditional write is also how stale offline clears are fenced.
            local = journal.localResume
            return true
        }
        val updated = when {
            local.samePosition(remote.game) -> {
                local = remote.game
                journal.copy(localResume = local, resumeRevision = remote.revision, resumeWrites = emptyList())
            }
            remote.game == null && (base == null || base == remote.revision) -> journal.copy(
                localResume = local,
                resumeRevision = remote.revision,
                resumeWrites = listOf(resumeWrite(local)),
            )
            else -> {
                _state.value = _state.value.copy(resumeConflict = CloudResumeConflict(local, remote))
                return true
            }
        }
        if (!persist(updated)) return false
        publish()
        return true
    }

    private suspend fun drainHistory(version: Int): Boolean {
        while (journal.history.isNotEmpty()) {
            val write = journal.history.first()
            var gone = false
            try {
                api.writeHistory(token(version), write)
            } catch (failure: CloudSaveException) {
                if (failure.code != "game_deleted") throw failure
                gone = true
            }
            checkSession(version)
            val game = write.game.takeUnless { gone }
            val items = journal.historyCache.filterNot { it.id == write.id }
            val updated = journal.copy(
                history = journal.history.drop(1),
                historyCache = if (game == null) items else items + game.metadata(),
                importedIds = if (write.id.startsWith("legacy-")) journal.importedIds + write.id else journal.importedIds,
            )
            if (!persist(if (game == null) updated else updated.withDetail(game))) return false
            historyRefresh = true
            publish()
        }
        return true
    }

    private suspend fun drainResume(version: Int): Boolean {
        while (journal.resumeWrites.isNotEmpty()) {
            val operation = journal.resumeWrites.first()
            val base = journal.resumeRevision ?: return false
            val claimed = operation.copy(expectedRevision = operation.expectedRevision ?: base)
            if (claimed != operation && !persist(journal.copy(resumeWrites = listOf(claimed) + journal.resumeWrites.drop(1)))) {
                return false
            }
            val revision = try {
                api.writeResume(token(version), claimed)
            } catch (failure: CloudSaveException) {
                if (failure.status != 412) throw failure
                val remote = api.current(token(version))
                checkSession(version)
                _state.value = _state.value.copy(resumeConflict = CloudResumeConflict(local, remote))
                publish()
                return true
            }
            checkSession(version)
            if (!persist(journal.copy(resumeWrites = journal.resumeWrites.drop(1), resumeRevision = revision))) return false
            publish()
        }
        return true
    }

    private suspend fun checkSession(version: Int) {
        currentCoroutineContext().ensureActive()
        if (version != generation) throw CancellationException("Cloud session changed")
    }

    private suspend fun token(version: Int): String {
        checkSession(version)
        val active = session ?: throw CancellationException("Cloud session ended")
        val refreshed = auth.refreshIfNeeded(active)
        checkSession(version)
        if (session === active) session = refreshed
        return session?.idToken ?: throw CancellationException("Cloud session ended")
    }

    private fun publish() {
        val rows = journal.historyCache.associateTo(mutableMapOf()) { it.id to CloudHistoryRow(it, pending = false) }
        journal.history.forEach { operation ->
            val game = operation.game
            if (game == null) rows.remove(operation.id)
            else rows[operation.id] = CloudHistoryRow(game.metadata(), pending = true)
        }
        _state.value = _state.value.copy(
            history = rows.values.sortedWith(compareByDescending<CloudHistoryRow> { it.item.savedAtEpochMillis }.thenByDescending { it.item.id }),
            hasMoreHistory = hasMore,
            pendingHistory = journal.history.size,
            pendingResume = journal.resumeWrites.size,
            recovery = journal.recovery,
        )
    }

    private fun message(value: String) { _state.value = _state.value.copy(message = value) }

    private data class SnapshotEvent(val snapshot: GameSnapshot?, val playerSide: Set, val upload: Boolean)

    companion object {
        private const val MAX_HISTORY_PAGES = 10

        fun create(
            settings: Settings,
            baseUrl: String,
            firebaseApiKey: String,
            historyEnabled: Boolean,
            resumeEnabled: Boolean,
        ): CloudSaveSync {
            require(baseUrl.startsWith("https://")) { "Cloud saves require an HTTPS endpoint" }
            require(firebaseApiKey.isNotBlank()) { "A Firebase client API key is required" }
            val http = HttpClient(openingExplainerHttpClientEngine()) {
                install(HttpTimeout) { requestTimeoutMillis = 15_000 }
            }
            return CloudSaveSync(
                FirebaseAuthClient(http, firebaseApiKey), CloudSaveClient(http, baseUrl),
                CloudSaveJournal(settings), historyEnabled, resumeEnabled, closeHttp = http::close,
            )
        }
    }
}

private fun SavedGame.toCloud(id: String) = CloudSavedGame(id, savedAtEpochMillis, result, white, black, moveCount, pgn, moveRecords)
private fun CloudSavedGame.toLocal() = SavedGame(id, savedAtEpochMillis, result, white, black, moveCount, pgn, moveRecords)
private fun CloudSavedGame.metadata() = CloudHistoryItem(id, savedAtEpochMillis, result, white, black, moveCount)
private fun legacyId(game: SavedGame): String = "legacy-${sha1("${game.id}\u0000${game.pgn}".encodeToByteArray()).toHexString()}"
private fun resumeWrite(game: CloudResumeGame?) = ResumeWrite(Uuid.random().toString(), game)
private fun CloudResumeGame?.samePosition(other: CloudResumeGame?): Boolean =
    this?.snapshot == other?.snapshot && this?.playerSide == other?.playerSide

private const val RECORD_BYTES = 256 * 1024
private fun fits(game: CloudSavedGame) = Json.encodeToString(game).encodeToByteArray().size <= RECORD_BYTES

private fun CloudJournal.withDetail(game: CloudSavedGame): CloudJournal {
    var used = 0
    val bounded = (listOf(game) + details.filterNot { it.id == game.id }).take(4).takeWhile {
        used += Json.encodeToString(it).encodeToByteArray().size
        used <= RECORD_BYTES
    }
    return copy(details = bounded)
}
