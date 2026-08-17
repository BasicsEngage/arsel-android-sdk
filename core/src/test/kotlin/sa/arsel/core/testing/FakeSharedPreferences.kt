package sa.arsel.core.testing

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] so the real [sa.arsel.core.store.ArselStore] can be exercised
 * on the JVM. `SharedPreferences` is an interface, so implementing it costs nothing at runtime —
 * unlike the mockable `android.jar`, whose concrete implementation throws from every method.
 *
 * [SharedPreferences.Editor.commit] and `apply()` are both immediate here. That is not a
 * simplification that matters to these tests: what the store relies on is that a flushed write is
 * visible to the next read, which holds either way.
 */
internal class FakeSharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = values[key] as? MutableSet<String> ?: defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?): Unit = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?): Unit = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putStringSet(
            key: String,
            value: MutableSet<String>?,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor = stage(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            pending -= key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            // A null value is a removal in the real implementation too, not a stored null.
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            pending.clear()
            removals.clear()
            clearAll = false
            return true
        }

        override fun apply() {
            commit()
        }

        private fun stage(
            key: String,
            value: Any?,
        ): SharedPreferences.Editor {
            pending[key] = value
            removals -= key
            return this
        }
    }
}
