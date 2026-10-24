package com.newhost.qwertylauncher.feature.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class Contact(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val number: String,
    val type: String,
)

internal class ContactsRepository(private val context: Context) {

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Phone numbers of contacts whose name matches [text]; one row per number. */
    suspend fun search(text: String, limit: Int = 6): List<Contact> {
        if (text.isBlank() || !hasPermission()) return emptyList()
        return withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(text))
            context.contentResolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                val contacts = mutableListOf<Contact>()
                while (cursor.moveToNext() && contacts.size < limit) {
                    val number = cursor.getString(COL_NUMBER) ?: continue
                    val type = Phone.getTypeLabel(context.resources, cursor.getInt(COL_TYPE), cursor.getString(COL_LABEL))
                    contacts += Contact(
                        id = cursor.getLong(COL_ID),
                        lookupKey = cursor.getString(COL_LOOKUP).orEmpty(),
                        name = cursor.getString(COL_NAME) ?: number,
                        number = number,
                        type = type.toString().lowercase(),
                    )
                }
                // Linked raw contacts often repeat the same number.
                contacts.distinctBy { it.name to it.number.filter(Char::isDigit) }
            }.orEmpty()
        }
    }

    fun lookupUri(contact: Contact): Uri = ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey)

    private companion object {
        val PROJECTION = arrayOf(
            Phone.CONTACT_ID,
            Phone.LOOKUP_KEY,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL,
        )
        const val COL_ID = 0
        const val COL_LOOKUP = 1
        const val COL_NAME = 2
        const val COL_NUMBER = 3
        const val COL_TYPE = 4
        const val COL_LABEL = 5
    }
}
