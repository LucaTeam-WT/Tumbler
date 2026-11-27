package io.github.lucateam_wt.tumbler.settings

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] for JVM tests (the Android one is a stub there). Only strings are
 * supported, which is all the settings use. [applies] and [commits] count the writes.
 */
class FakeSharedPreferences : SharedPreferences {

    val values = mutableMapOf<String, String?>()
    var applies = 0
    var commits = 0

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? = if (key in values) values[key] else defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = unsupported()

    override fun getInt(key: String, defValue: Int): Int = unsupported()

    override fun getLong(key: String, defValue: Long): Long = unsupported()

    override fun getFloat(key: String, defValue: Float): Float = unsupported()

    override fun getBoolean(key: String, defValue: Boolean): Boolean = unsupported()

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = unsupported()

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = unsupported()

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = unsupported()

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = unsupported()

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = unsupported()

        override fun remove(key: String): SharedPreferences.Editor = apply { removed += key }

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            commits++
            write()
            return true
        }

        override fun apply() {
            applies++
            write()
        }

        private fun write() {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by the settings")
}
