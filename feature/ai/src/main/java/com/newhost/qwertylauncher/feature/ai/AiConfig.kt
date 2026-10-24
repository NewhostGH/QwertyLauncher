package com.newhost.qwertylauncher.feature.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.newhost.qwertylauncher.core.ChoiceSetting
import com.newhost.qwertylauncher.core.ChoiceSetting.Choice
import com.newhost.qwertylauncher.core.SettingsStore
import com.newhost.qwertylauncher.core.ToggleSetting
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal object AiSettings {
    val provider = ChoiceSetting(
        "provider",
        "AI provider",
        Provider.entries.map { Choice(it.id, it.label) },
        default = Provider.ANTHROPIC.id,
        summary = "Keys, models and addresses are set in the AI pane: type ai, then open setup.",
    )
    val offerForQuestions = ToggleSetting(
        "offer_for_questions",
        "offer AI for multi-word input",
        default = true,
        summary = "Typing a few words offers asking the AI. ? and ai always work.",
    )

    val all = arrayOf(provider, offerForQuestions)
}

/**
 * The AI's set-up: the provider (a setting), and for each provider its key
 * (encrypted, see [KeyVault]), model and address. [changes] ticks on every
 * edit, for the panes that show them.
 */
internal class AiConfig(context: Context, private val settings: SettingsStore, scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val vault = KeyVault(context)

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Kept here too, since the settings store saves asynchronously; it also follows changes made in settings. */
    var provider: Provider = Provider.of(settings[AiSettings.provider])
        private set

    init {
        scope.launch {
            settings.observe(AiSettings.provider).collect { id ->
                if (id != provider.id) {
                    provider = Provider.of(id)
                    changed()
                }
            }
        }
    }

    fun setProvider(provider: Provider) {
        this.provider = provider
        settings.set(AiSettings.provider, provider.id)
        changed()
    }

    fun key(provider: Provider): String? = vault.get("key.${provider.id}")

    fun setKey(provider: Provider, key: String?) {
        if (key.isNullOrBlank()) vault.remove("key.${provider.id}") else vault.put("key.${provider.id}", key.trim())
        changed()
    }

    fun model(provider: Provider): String? = prefs.getString("model.${provider.id}", null) ?: provider.suggestedModel

    fun setModel(provider: Provider, model: String) {
        prefs.edit().putString("model.${provider.id}", model.trim()).apply()
        changed()
    }

    fun address(provider: Provider): String = prefs.getString("address.${provider.id}", null) ?: provider.defaultAddress

    fun setAddress(provider: Provider, address: String) {
        prefs.edit().putString("address.${provider.id}", address.trim().trimEnd('/')).apply()
        changed()
    }

    /** What's still missing before [provider] can be used, in words; null when it's ready. */
    fun missing(provider: Provider = this.provider): String? = when {
        provider.needsAddress && address(provider).isBlank() -> "an address"
        provider.needsKey && key(provider).isNullOrBlank() -> "an API key"
        model(provider).isNullOrBlank() -> "a model"
        else -> null
    }

    /** How to reach [provider], or null while something's [missing]. */
    fun connection(provider: Provider = this.provider): Connection? {
        if (missing(provider) != null) return null
        return Connection(provider, address(provider), key(provider), model(provider)!!)
    }

    private fun changed() = _changes.update { it + 1 }
}

/**
 * Secrets (API keys) encrypted with an AES key that lives in the Android
 * Keystore and never leaves it: what's stored on disk is useless alone.
 */
internal class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("ai_keys", Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, encode(cipher.iv) + SEPARATOR + encode(sealed)).apply()
    }

    /** The secret, or null if there's none (or it can't be opened, e.g. after a restore to a new phone). */
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val (iv, sealed) = stored.split(SEPARATOR).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(sealed))
        }.getOrNull()
    }

    fun remove(name: String) = prefs.edit().remove(name).apply()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "qwertylauncher.ai"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
