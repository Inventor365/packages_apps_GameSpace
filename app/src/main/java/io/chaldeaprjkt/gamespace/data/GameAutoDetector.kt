/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package io.chaldeaprjkt.gamespace.data

import android.app.GameManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.utils.GameModeUtils

/**
 * Fills GameSpace's game list by itself.
 *
 * The framework's auto-detection only adds apps whose developer declared
 * android:appCategory="game" (or isGame), and only at install time. Many of
 * the games people actually play here don't declare it, or were installed
 * before the toggle existed - BGMI had to be added by hand, and until it was,
 * no GameSpace session (touch polling, DND, gamebar) and no game treatment in
 * the power profiles ever started for it.
 *
 * A launchable, non-system app is treated as a game when any of these hold:
 *  - ApplicationInfo.category == CATEGORY_GAME or FLAG_IS_GAME
 *  - its package is in config_gamespace_known_games
 *  - its package starts with one of config_gamespace_known_game_prefixes
 * New games are added in Performance mode with the same intervention a manual
 * add gets. Anything the user removed stays removed (gamespace_denied_list),
 * and nothing happens while "Auto game detection" is off.
 */
class GameAutoDetector(private val context: Context) {

    private val gameModeUtils = GameModeUtils(context)
    private val systemSettings = SystemSettings(context, gameModeUtils)
    private val pm: PackageManager = context.packageManager

    private val knownPackages: Set<String> by lazy {
        context.resources.getStringArray(R.array.config_gamespace_known_games).toSet()
    }
    private val knownPrefixes: List<String> by lazy {
        context.resources.getStringArray(R.array.config_gamespace_known_game_prefixes).toList()
    }

    fun isGame(info: ApplicationInfo): Boolean {
        if (info.category == ApplicationInfo.CATEGORY_GAME) return true
        @Suppress("DEPRECATION")
        if ((info.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
        val pkg = info.packageName
        return pkg in knownPackages || knownPrefixes.any { pkg.startsWith(it) }
    }

    /**
     * Adds every undetected game (or just [onlyPackage]) to the list.
     * Returns the packages that were added.
     */
    @Synchronized
    fun scan(onlyPackage: String? = null): List<String> {
        if (!systemSettings.autoGameDetect) return emptyList()
        val current = systemSettings.userGames
        val listed = current.map { it.packageName }.toSet()
        val denied = deniedList()
        val candidates = if (onlyPackage != null) {
            listOfNotNull(appInfo(onlyPackage))
        } else {
            launchableApps()
        }
        val added = candidates
            .asSequence()
            .filter { it.packageName !in listed && it.packageName !in denied }
            .filter { it.packageName != context.packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { isGame(it) }
            .map { it.packageName }
            .distinct()
            .toList()
        if (added.isEmpty()) return added

        systemSettings.userGames = current + added.map {
            UserGame(it, GameManager.GAME_MODE_PERFORMANCE)
        }
        // game_overlay holds one package at a time; give GameManagerService's
        // observer a moment per package so none of the configs is dropped.
        added.forEach {
            gameModeUtils.setIntervention(it, GameConfig.ModeBuilder.build())
            SystemClock.sleep(INTERVENTION_SPACING_MS)
        }
        Log.i(TAG, "Auto-added games: $added")
        return added
    }

    private fun appInfo(pkg: String): ApplicationInfo? = try {
        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun launchableApps(): List<ApplicationInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
    }

    private fun deniedList(): Set<String> =
        Settings.System.getStringForUser(
            context.contentResolver, KEY_DENIED_LIST, UserHandle.USER_CURRENT
        )?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    companion object {
        private const val TAG = "GameAutoDetector"
        private const val KEY_DENIED_LIST = "gamespace_denied_list"
        private const val INTERVENTION_SPACING_MS = 250L

        /** Runs [scan] off the caller's thread. */
        fun scanAsync(context: Context, onlyPackage: String? = null, done: (() -> Unit)? = null) {
            val app = context.applicationContext
            Thread({
                try {
                    GameAutoDetector(app).scan(onlyPackage)
                } catch (e: Exception) {
                    Log.w(TAG, "Game scan failed", e)
                } finally {
                    done?.invoke()
                }
            }, "GameAutoDetector").start()
        }
    }
}
