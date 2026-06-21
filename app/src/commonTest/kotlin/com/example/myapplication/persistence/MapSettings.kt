package com.example.myapplication.persistence

import com.russhwolf.settings.Settings

/**
 * Minimal in-memory [Settings] implementation for unit tests. The russhwolf `MapSettings` test
 * helper isn't shipped in the published main artifact in 1.3.0, so we provide our own — the
 * [Settings] interface is small enough that a 1-to-1 map-backed impl is a few dozen lines.
 *
 * Stored as `String?` and converted to/from typed accessors, matching how a real K/V store
 * round-trips scalar values.
 */
internal class MapSettings : Settings {
    private val map: MutableMap<String, String?> = mutableMapOf()

    override fun clear() = map.clear()
    override fun remove(key: String) { map.remove(key) }
    override fun hasKey(key: String): Boolean = map.containsKey(key)
    override val keys: Set<String> get() = map.keys
    override val size: Int get() = map.size

    override fun putInt(key: String, value: Int) { map[key] = value.toString() }
    override fun getInt(key: String, defaultValue: Int): Int =
        map[key]?.toIntOrNull() ?: defaultValue
    override fun getIntOrNull(key: String): Int? = map[key]?.toIntOrNull()

    override fun putLong(key: String, value: Long) { map[key] = value.toString() }
    override fun getLong(key: String, defaultValue: Long): Long =
        map[key]?.toLongOrNull() ?: defaultValue
    override fun getLongOrNull(key: String): Long? = map[key]?.toLongOrNull()

    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String, defaultValue: String): String =
        map[key] ?: defaultValue
    override fun getStringOrNull(key: String): String? = map[key]

    override fun putFloat(key: String, value: Float) { map[key] = value.toString() }
    override fun getFloat(key: String, defaultValue: Float): Float =
        map[key]?.toFloatOrNull() ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = map[key]?.toFloatOrNull()

    override fun putDouble(key: String, value: Double) { map[key] = value.toString() }
    override fun getDouble(key: String, defaultValue: Double): Double =
        map[key]?.toDoubleOrNull() ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = map[key]?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString() }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        map[key]?.toBooleanStrictOrNull() ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = map[key]?.toBooleanStrictOrNull()
}
