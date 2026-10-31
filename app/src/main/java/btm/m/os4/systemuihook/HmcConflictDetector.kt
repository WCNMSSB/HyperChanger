// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Finds the standalone HyperMusicCover module without relying on one applicationId.
 *
 * Forks commonly change their package name while retaining the module title or
 * Xposed description, so detection uses several independent identity fields.
 */
internal object HmcConflictDetector {
    data class Match(
        val packageName: String,
        val label: String,
        val reason: String,
    )

    private val knownPackageIds = setOf(
        "com.github.zyl6932.hypermusiccover",
        "com.os4.musiccover",
    )

    fun find(context: Context): Match? = runCatching {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        installed.asSequence()
            .filterNot { it.packageName.equals(context.packageName, ignoreCase = true) }
            .mapNotNull { app -> runCatching { identify(pm, app) }.getOrNull() }
            .firstOrNull()
    }.getOrNull()

    private fun identify(pm: PackageManager, app: ApplicationInfo): Match? {
        val packageName = app.packageName.orEmpty()
        val label = runCatching { app.loadLabel(pm)?.toString().orEmpty() }.getOrDefault("")
        val description = runCatching { app.loadDescription(pm)?.toString().orEmpty() }.getOrDefault("")
        val metadata: Bundle? = app.metaData
        val metadataText = buildString {
            metadata?.keySet()?.forEach { key ->
                append(key).append(' ')
                append(metadata.get(key)?.toString().orEmpty()).append(' ')
            }
        }
        val packageId = normalize(packageName)
        val identity = normalize("$label $description $metadataText")
        val isXposedModule = runCatching {
            metadata?.getBoolean("xposedmodule", false) == true
        }.getOrDefault(false)

        val reason = when {
            knownPackageIds.any { packageName.equals(it, ignoreCase = true) } ->
                "known package id"
            packageId.contains("hypermusiccover") ->
                "package id marker"
            identity.contains("hypermusiccover") ->
                "module label/description marker"
            isXposedModule && identity.contains("hypermusic") && identity.contains("cover") ->
                "Xposed module identity"
            isXposedModule && packageId.contains("music") && identity.contains("hmc") ->
                "fork module identity"
            isXposedModule && normalize(label) == "hmc" &&
                (description.contains("music", ignoreCase = true) || packageId.contains("music")) ->
                "HMC module label"
            else -> return null
        }
        return Match(packageName, label.ifBlank { packageName }, reason)
    }

    private fun normalize(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)
}
