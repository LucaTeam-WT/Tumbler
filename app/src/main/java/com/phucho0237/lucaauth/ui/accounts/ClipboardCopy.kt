package com.phucho0237.lucaauth.ui.accounts

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

private const val CLIP_LABEL = "OTP"

/**
 * Copies [code] to the clipboard. On Android 13+ the clip is marked sensitive so the
 * system hides it in the clipboard preview. The clipboard is not cleared automatically
 * yet; that comes in a later change.
 */
fun copyCodeToClipboard(context: Context, code: String) {
    val clip = ClipData.newPlainText(CLIP_LABEL, code)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
