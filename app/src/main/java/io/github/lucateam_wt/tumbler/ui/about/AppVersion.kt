package io.github.lucateam_wt.tumbler.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri

/** Where the source code is published; shown and opened by the About screen. */
internal const val SOURCE_CODE_URL = "https://github.com/LucaTeam-WT/Tumbler"

/** SPDX identifier of the app's license (see LICENSE). */
internal const val LICENSE_ID = "GPL-3.0-or-later"

/** The installed app's version, as the package manager reports it. */
internal data class AppVersion(val name: String, val code: Long)

/** Reads this app's own version from [PackageManager]; needs no permission. */
internal fun Context.appVersion(): AppVersion {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    return AppVersion(name = info.versionName.orEmpty(), code = PackageInfoCompat.getLongVersionCode(info))
}

/**
 * Opens [url] in another app (a browser) with `ACTION_VIEW`, which needs no permission; the app
 * itself makes no network request. Returns false if no app can open it.
 */
internal fun Context.openLink(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (e: ActivityNotFoundException) {
    false
}
