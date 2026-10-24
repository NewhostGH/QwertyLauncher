package com.newhost.qwertylauncher.feature.contacts

import android.Manifest
import android.content.Intent
import android.net.Uri
import com.newhost.qwertylauncher.core.Command
import com.newhost.qwertylauncher.core.Fuzzy
import com.newhost.qwertylauncher.core.LauncherModule
import com.newhost.qwertylauncher.core.ModuleScope
import com.newhost.qwertylauncher.core.Query
import com.newhost.qwertylauncher.core.Score
import com.newhost.qwertylauncher.core.SearchProvider
import com.newhost.qwertylauncher.core.SearchResult
import com.newhost.qwertylauncher.core.SettingsStore

/** Contact search plus `call` and `sms`. */
class ContactsModule : LauncherModule {
    override val id = "contacts"

    override fun install(scope: ModuleScope) {
        scope.declareSettings(*ContactsSettings.all)
        val repository = ContactsRepository(scope.appContext)
        scope.searchProvider(ContactSearchProvider(repository, scope.settings))
        // ACTION_DIAL / ACTION_SENDTO need no permission; the user confirms in the dialer or SMS app.
        scope.command(
            ContactActionCommand("call", "dial a contact or number", repository) { number ->
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            },
        )
        scope.command(
            ContactActionCommand("sms", "text a contact or number", repository) { number ->
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
            },
        )
    }
}

/** Offered in place of results until contacts access is granted. */
internal fun permissionResult() = SearchResult(
    key = "ctc:permission",
    tag = "ctc",
    title = "search contacts too?",
    detail = "grant access",
    score = Score.FALLBACK,
    action = { host ->
        host.requestPermission(Manifest.permission.READ_CONTACTS) { granted ->
            if (granted) host.rerunSearch()
        }
    },
)

internal class ContactSearchProvider(
    private val repository: ContactsRepository,
    private val settings: SettingsStore,
) : SearchProvider {
    override val id = "contacts"

    override suspend fun search(query: Query): List<SearchResult> {
        if (!settings[ContactsSettings.inSearch] || query.text.length < settings[ContactsSettings.minLetters]) {
            return emptyList()
        }
        if (!repository.hasPermission()) return listOf(permissionResult())
        return repository.search(query.text).map { contact ->
            SearchResult(
                key = "ctc:${contact.id}:${contact.number}",
                tag = "ctc",
                title = contact.name,
                detail = "${contact.type} ${contact.number}",
                // The provider already filtered by name, so never rank below a fuzzy app match.
                score = Fuzzy.score(query.text, contact.name).coerceAtLeast(Score.FUZZY) - 20,
                action = { host ->
                    host.clearQuery()
                    host.startActivitySafely(Intent(Intent.ACTION_VIEW, repository.lookupUri(contact)))
                },
            )
        }
    }
}

/** `<name> <contact|number>`, e.g. `call mom` or `sms +49 151 ...`. */
internal class ContactActionCommand(
    override val name: String,
    override val summary: String,
    private val repository: ContactsRepository,
    private val intentFor: (number: String) -> Intent,
) : Command {
    override val usage = "$name <contact|number>"

    override suspend fun suggest(args: String): List<SearchResult> {
        val target = args.trim()
        if (target.isEmpty()) return emptyList()
        return buildList {
            if (looksLikeNumber(target)) add(result(target, target, "number", Score.EXACT))
            if (repository.hasPermission()) {
                repository.search(target).forEach { c ->
                    add(result(c.name, c.number, "${c.type} ${c.number}", Score.PREFIX))
                }
            } else {
                add(permissionResult())
            }
        }
    }

    private fun result(label: String, number: String, detail: String, score: Int) = SearchResult(
        key = "$name:$number",
        tag = name,
        title = "$name $label",
        detail = detail,
        score = score,
        action = { host ->
            host.clearQuery()
            host.startActivitySafely(intentFor(number))
        },
    )

    private fun looksLikeNumber(s: String) =
        s.all { it.isDigit() || it in "+-() " } && s.count { it.isDigit() } >= MIN_DIGITS

    private companion object {
        const val MIN_DIGITS = 3
    }
}
