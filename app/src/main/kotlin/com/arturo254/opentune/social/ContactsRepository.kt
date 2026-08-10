/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.social

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import com.arturo254.opentune.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** A phone contact from the device, with privacy-preserving hashes of its numbers. */
data class DeviceContact(
    val name: String,
    val hashes: List<String>,
)

/** Result of a contact sync: who already uses the app, and who to invite. */
data class ContactSyncResult(
    val onApp: List<SocialUser>,
    val toInvite: List<DeviceContact>,
)

object ContactsRepository {

    const val DOWNLOAD_URL = "https://dezzy.space"

    /** Normalises a raw phone string to its last 10 significant digits for cross-device matching. */
    fun normalizePhone(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 7) return null
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    /** SHA-256 hex of a normalised phone — what we store/compare, never the raw number. */
    fun hashPhone(normalized: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun readDeviceContacts(context: Context): List<DeviceContact> {
        val out = ArrayList<DeviceContact>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        // Group numbers by display name.
        val byName = LinkedHashMap<String, MutableSet<String>>()
        try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx < 0 || numIdx < 0) return emptyList()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.trim().orEmpty().ifBlank { continue }
                    val number = cursor.getString(numIdx) ?: continue
                    val normalized = normalizePhone(number) ?: continue
                    byName.getOrPut(name) { LinkedHashSet() }.add(hashPhone(normalized))
                }
            }
        } catch (e: Exception) {
            reportException(e)
            return emptyList()
        }
        byName.forEach { (name, hashes) -> out += DeviceContact(name, hashes.toList()) }
        return out
    }

    /** Reads contacts, matches them against the app's users, and splits into on-app vs invite. */
    suspend fun sync(context: Context): ContactSyncResult = withContext(Dispatchers.IO) {
        val contacts = readDeviceContacts(context)
        if (contacts.isEmpty()) return@withContext ContactSyncResult(emptyList(), emptyList())

        val allHashes = contacts.flatMap { it.hashes }.distinct()
        val onApp = SocialRepository.findFriendsByContacts(allHashes)
        val matched = onApp.mapNotNull { it.phoneHash }.toHashSet()

        val toInvite = contacts.filter { contact -> contact.hashes.none { it in matched } }
            .distinctBy { it.name }
        ContactSyncResult(onApp = onApp, toInvite = toInvite)
    }

    /** Builds the personalized share intent with the referral link for a non-app contact. */
    fun buildInviteIntent(contactName: String, inviterId: String?): Intent {
        val link = if (inviterId.isNullOrBlank()) DOWNLOAD_URL else "$DOWNLOAD_URL/?ref=$inviterId"
        val text = "$contactName, te invito a unirte al ecosistema Dezzy 🎵\n" +
            "Descúbrela aquí: $link"
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }
}
