package com.csqtt.client.ui.shell

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.csqtt.client.AppOwners
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.profiles.AddProfileSheet
import com.csqtt.client.ui.profiles.LinkImportDialog
import com.csqtt.client.ui.profiles.ProfileDeleteDialog
import com.csqtt.client.ui.profiles.ProfileRenameDialog
import com.csqtt.client.ui.profiles.ProfilesSheet
import com.csqtt.client.ui.profiles.QrScanner
import com.csqtt.client.ui.profiles.SubCertChangedDialog
import com.csqtt.client.ui.profiles.SubDeleteDialog
import com.csqtt.client.ui.profiles.SubEditSheet
import com.csqtt.client.ui.profiles.SubImportSheet
import java.util.UUID

/** What the screens are given at the root, composed once: where the user is, the store, and what outlives the screens. */
internal val LocalNav = staticCompositionLocalOf<NavModel> { error("no NavModel") }
internal val LocalStore = staticCompositionLocalOf<SettingsStore> { error("no SettingsStore") }
internal val LocalOwners = staticCompositionLocalOf<AppOwners> { error("no AppOwners") }

/**
 * The overlays the shell can show, by route. The shell draws them at the root, outside the tabs (U6): an open sheet or
 * dialog is the same one on every tab and after a profile change. Each card adds its routes here.
 */
internal object Routes {
    const val LINK_IMPORT = "link-import"
    const val PROFILES = "profiles"
    const val ADD_PROFILE = "add-profile"
    const val PROFILE_RENAME = "profile-rename"
    const val PROFILE_DELETE = "profile-delete"
    const val QR_SCANNER = "qr-scanner"
    const val SUB_IMPORT = "sub-import"
    const val SUB_EDIT = "sub-edit"
    const val SUB_DELETE = "sub-delete"
    const val SUB_CERT = "sub-cert"

    /** A csqtt:// link waiting for the user's answer (M-70, M-71), by the id [PendingLink] keeps it under. */
    fun linkImport(id: String) = Dest(LINK_IMPORT, Layer.Dialog, id)

    /** «Профили»: every profile and subscription. */
    fun profiles() = Dest(PROFILES, Layer.Sheet)

    fun addProfile() = Dest(ADD_PROFILE, Layer.Sheet)
    fun profileRename(profile: Int) = Dest(PROFILE_RENAME, Layer.Dialog, profile.toString())
    fun profileDelete(profile: Int) = Dest(PROFILE_DELETE, Layer.Dialog, profile.toString())

    /** The scanner is a window of its own over the sheet it was opened from. */
    fun qrScanner() = Dest(QR_SCANNER, Layer.Dialog)

    fun subImport() = Dest(SUB_IMPORT, Layer.Sheet)

    // a subscription is named in the saved state by its key, never by its URL, which may carry its access token
    fun subEdit(url: String) = Dest(SUB_EDIT, Layer.Sheet, subscriptionKey(url))
    fun subDelete(url: String) = Dest(SUB_DELETE, Layer.Dialog, subscriptionKey(url))
    fun subCert(url: String) = Dest(SUB_CERT, Layer.Dialog, subscriptionKey(url))

    /** What stands for a subscription's URL where the URL itself must not be written. */
    fun subscriptionKey(url: String): String = url.trim().hashCode().toString()

    private val app: Map<String, @Composable (dest: Dest, close: () -> Unit) -> Unit> = mapOf(
        LINK_IMPORT to { dest, close -> LinkImportDialog(dest.arg, close) },
        PROFILES to { _, close -> ProfilesSheet(close) },
        ADD_PROFILE to { _, close -> AddProfileSheet(close) },
        PROFILE_RENAME to { dest, close -> ProfileRenameDialog(dest.arg.toIntOrNull(), close) },
        PROFILE_DELETE to { dest, close -> ProfileDeleteDialog(dest.arg.toIntOrNull(), close) },
        QR_SCANNER to { _, close -> QrScanner(close) },
        SUB_IMPORT to { _, close -> SubImportSheet(close) },
        SUB_EDIT to { dest, close -> SubEditSheet(dest.arg, close) },
        SUB_DELETE to { dest, close -> SubDeleteDialog(dest.arg, close) },
        SUB_CERT to { dest, close -> SubCertChangedDialog(dest.arg, close) },
    )

    /** Set by instrumented tests only: routes of their own beside the app's. */
    @Volatile
    @VisibleForTesting
    var test: Map<String, @Composable (dest: Dest, close: () -> Unit) -> Unit> = emptyMap()

    fun find(route: String): (@Composable (dest: Dest, close: () -> Unit) -> Unit)? = test[route] ?: app[route]
}

/**
 * The csqtt:// link that waits for the user's answer. A link holds the connection password, so it is not in the saved
 * navigation (§4.2 rule 2): the route carries an id, and the link is kept here, in the app's private storage — where a
 * link lies once it is a profile — so the question is still there after a process death (rule 11). One link waits at a
 * time: a newer one takes its place, as its dialog does.
 */
internal object PendingLink {
    private const val PREFS = "pending_link"
    private const val ID = "id"
    private const val LINK = "link"

    @Volatile
    private var held: Pair<String, String>? = null

    /** Keeps [raw] and gives the id its dialog is opened with. */
    fun put(context: Context, raw: String): String {
        val id = UUID.randomUUID().toString()
        held = id to raw
        prefs(context).edit().putString(ID, id).putString(LINK, raw).apply()
        return id
    }

    /** The link [id] names, or null: answered, replaced by a newer one, or never kept. */
    fun get(context: Context, id: String): String? {
        held?.let { (heldId, raw) -> return raw.takeIf { heldId == id } }
        val stored = prefs(context)
        val raw = stored.getString(LINK, null)?.takeIf { stored.getString(ID, null) == id } ?: return null
        held = id to raw
        return raw
    }

    /** The question [id] is answered or dropped. A newer link that took its place stays. */
    fun clear(context: Context, id: String) {
        if (held?.first == id) held = null
        val stored = prefs(context)
        if (stored.getString(ID, null) == id) stored.edit().clear().apply()
    }

    /** No question stands (a fresh start of the app): whatever link was kept for one goes. */
    fun drop(context: Context) {
        held = null
        prefs(context).edit().clear().apply()
    }

    /** What a new process starts with: nothing in memory. For the test of the question outliving its process. */
    @VisibleForTesting
    fun forget() {
        held = null
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
