// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Detects the standalone HyperMusicCover module and forks that retain its module identity. */
internal object HmcConflictDetector {
    private const val OFFICIAL_PACKAGE = "com.github.zyl6932.HyperMusicCover"
    private const val SOURCE_NAMESPACE = "com.os4.musiccover"
    private const val MODULE_META_KEY = "xposedmodule"
    private const val DESCRIPTION_META_KEY = "xposeddescription"

    @Volatile private var notified = false

    data class Installation(
        val packageName: String,
        val label: String,
    )

    /**
     * The official build has a stable applicationId. Forks are also matched by the upstream
     * Java namespace and Xposed metadata, or by the HyperMusicCover label/description markers.
     * No single package name is assumed for forks.
     */
    fun findInstalled(context: Context): Installation? {
        return runCatching {
            val pm = context.packageManager

            // Fast path for the official applicationId, including builds whose label was changed.
            val official = runCatching {
                pm.getApplicationInfo(OFFICIAL_PACKAGE, PackageManager.GET_META_DATA)
            }.getOrNull()
            if (official != null) return@runCatching Installation(official.packageName, safeLabel(pm, official))

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            installed.firstNotNullOfOrNull { info ->
                if (info.packageName == context.packageName ||
                    info.packageName == "btm.m.os4.systemuihook") return@firstNotNullOfOrNull null

                val metadata = info.metaData
                val isXposedModule = metadata?.getBoolean(MODULE_META_KEY, false) == true
                val label = safeLabel(pm, info)
                val description = metadata?.getString(DESCRIPTION_META_KEY).orEmpty()
                val normalizedLabel = normalize(label)
                val normalizedDescription = normalize(description)
                val sourceNamespaceMatch = info.packageName == SOURCE_NAMESPACE ||
                    info.packageName.startsWith("$SOURCE_NAMESPACE.")
                val hmcMarker = normalizedLabel.contains("hypermusiccover") ||
                    normalizedDescription.contains("hypermusiccover")

                val matched = isXposedModule && (sourceNamespaceMatch || hmcMarker)
                if (matched) Installation(info.packageName, label) else null
            }
        }.getOrNull()
    }

    /** Show one native Android toast per SystemUI process; never creates a separate dialog. */
    fun notifyConflict(context: Context, installation: Installation) {
        if (notified) return
        synchronized(this) {
            if (notified) return
            notified = true
        }
        val appContext = context.applicationContext
        val message = "检测到 HyperMusicCover 或兼容分支，已跳过 HyperChanger 的音乐锁屏 Hook"
        val show = Runnable {
            runCatching { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) show.run()
        else Handler(Looper.getMainLooper()).post(show)
        android.util.Log.w(
            "HyperChanger-HMC",
            "Music lockscreen hooks disabled due to ${installation.packageName} (${installation.label})",
        )
    }

    private fun safeLabel(pm: PackageManager, info: ApplicationInfo): String =
        runCatching { info.loadLabel(pm).toString() }.getOrDefault("")

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
