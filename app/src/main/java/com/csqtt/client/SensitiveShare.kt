package com.csqtt.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File

// ClipDescription.EXTRA_IS_SENSITIVE (API 33); the key is read by keyboards and the system on older versions too.
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
private const val SHARED_DIR = "shared"
private const val SHARED_LOG_FILE = "csqtt-log.txt"

/** Copies [text] marked sensitive, so the system hides its preview and keyboards keep it out of their history (M-173). */
internal fun Context.copySensitive(label: String, text: String): Boolean {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    return runCatching { clipboard.setPrimaryClip(clip) }.isSuccess
}

/**
 * Shares the log as a text file through the app's FileProvider (cache `shared/` only): a log of 100 long lines is too
 * big for a clipboard or an intent's text. The lines are redacted at the log sink already.
 */
internal fun Context.shareLogFile(text: String): Boolean = runCatching {
    val dir = File(cacheDir, SHARED_DIR).apply { mkdirs() }
    val file = File(dir, SHARED_LOG_FILE).apply { writeText(text) }
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "CSQTT log")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}.isSuccess
