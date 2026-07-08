package com.example.myapplication

/**
 * A platform-neutral sink the [GameViewModel] calls to persist the in-progress game.
 *
 * This is the **only** persistence surface the core needs. It deliberately knows nothing about
 * russhwolf `Settings`, kotlinx-serialization backends, or where the snapshot is stored — the
 * `:app` module supplies a concrete adapter (over its russhwolf-backed `CurrentGameStore`) at
 * construction. `null` disables autosave entirely (used by tests and previews).
 *
 * The VM never reads back through this interface — restore-on-launch happens at the platform
 * entry point, which constructs the VM from the loaded snapshot.
 */
interface GameSnapshotSink {
    /** Persist [snapshot] as the current in-progress game. */
    fun save(snapshot: GameSnapshot)

    /** Drop any persisted in-progress game (called when a fresh game replaces an autosaved one). */
    fun clear()
}
