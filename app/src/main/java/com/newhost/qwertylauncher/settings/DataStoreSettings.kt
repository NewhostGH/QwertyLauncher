package com.newhost.qwertylauncher.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.MultiChoiceSetting
import com.newhost.qwertylauncher.core.NumberSetting
import com.newhost.qwertylauncher.core.Setting
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ToggleSetting
import java.io.IOException
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * [SettingsStore] backed by Preferences DataStore.
 *
 * Values are served from an in-memory snapshot so modules can read them
 * synchronously. The snapshot is loaded once, blocking, at process start: the
 * file is tiny, and it keeps the first frame from showing defaults and then
 * rebuilding when the real values arrive.
 */
class DataStoreSettings(context: Context) : SettingsStore {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = ioScope) {
        context.applicationContext.preferencesDataStoreFile(FILE_NAME)
    }

    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e !is IOException) throw e
        Log.w(TAG, "Settings unreadable; using defaults", e)
        emit(emptyPreferences())
    }

    /** Every stored value. Collect it to hear about any change. */
    val snapshot: StateFlow<Preferences> = data.stateIn(
        scope = ioScope,
        started = SharingStarted.Eagerly,
        initialValue = runBlocking { data.first() },
    )

    /** Declared setting → namespaced storage key ("module.key"). */
    private val names = IdentityHashMap<Setting<*>, String>()

    fun declare(owner: String, setting: Setting<*>) {
        synchronized(names) { names[setting] = "$owner.${setting.key}" }
    }

    override fun <T : Any> get(setting: Setting<T>): T = read(snapshot.value, setting)

    override fun <T : Any> observe(setting: Setting<T>): Flow<T> =
        snapshot.map { read(it, setting) }.distinctUntilChanged()

    override fun <T : Any> set(setting: Setting<T>, value: T) {
        ioScope.launch {
            dataStore.edit {
                if (setting is MultiChoiceSetting) {
                    // Stored as "a,b,c", in the options' order.
                    @Suppress("UNCHECKED_CAST")
                    val chosen = value as List<String>
                    it[stringPreferencesKey(name(setting))] = setting.options.map { o -> o.value }.filter { v -> v in chosen }.joinToString(",")
                } else {
                    it[preferenceKey(setting)] = value
                }
            }
        }
    }

    /** Reads a value, falling back to the default for unknown choices and clamping numbers. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> read(preferences: Preferences, setting: Setting<T>): T {
        if (setting is MultiChoiceSetting) {
            val stored = preferences[stringPreferencesKey(name(setting))] ?: return setting.default
            val chosen = stored.split(',').toSet()
            return setting.options.map { it.value }.filter { it in chosen } as T
        }
        val stored = preferences[preferenceKey(setting)] ?: return setting.default
        return when (setting) {
            is ChoiceSetting ->
                if (setting.options.any { it.value == stored }) stored else setting.default
            is NumberSetting -> (stored as Int).coerceIn(setting.min, setting.max) as T
            is ToggleSetting -> stored
            is MultiChoiceSetting -> setting.default
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> preferenceKey(setting: Setting<T>): Preferences.Key<T> {
        val name = name(setting)
        return when (setting) {
            is ToggleSetting -> booleanPreferencesKey(name)
            is ChoiceSetting, is MultiChoiceSetting -> stringPreferencesKey(name)
            is NumberSetting -> intPreferencesKey(name)
        } as Preferences.Key<T>
    }

    private fun name(setting: Setting<*>): String = synchronized(names) { names[setting] } ?: setting.key

    /** Every stored value by its storage key, for `config export`; values never set aren't there. */
    fun storedValues(): Map<String, Any> =
        snapshot.value.asMap().entries
            .filter { SettingsFile.isStorable(it.value) }
            .associate { it.key.name to it.value }

    /**
     * [values] (by storage key) without any of the wrong kind for their setting,
     * which a hand-edited file could have. Keys no setting declares (a module
     * that isn't installed) are kept as they are.
     */
    fun fitting(values: Map<String, Any>): Map<String, Any> {
        val fits: Map<String, (Any) -> Boolean> = synchronized(names) {
            names.entries.associate { (setting, name) ->
                name to when (setting) {
                    is ToggleSetting -> { value: Any -> value is Boolean }
                    is NumberSetting -> { value: Any -> value is Int }
                    is ChoiceSetting, is MultiChoiceSetting -> { value: Any -> value is String }
                }
            }
        }
        return values.filter { (name, value) -> fits[name]?.invoke(value) ?: true }
    }

    /** Replaces every stored value with the [fitting] ones of [values], for `config import`. */
    suspend fun replaceAll(values: Map<String, Any>) {
        val fitting = fitting(values)
        dataStore.edit { stored ->
            stored.clear()
            for ((name, value) in fitting) {
                when (value) {
                    is Boolean -> stored[booleanPreferencesKey(name)] = value
                    is Int -> stored[intPreferencesKey(name)] = value
                    is Long -> stored[longPreferencesKey(name)] = value
                    is String -> stored[stringPreferencesKey(name)] = value
                }
            }
        }
    }

    private companion object {
        const val TAG = "DataStoreSettings"
        const val FILE_NAME = "launcher_settings"
    }
}
