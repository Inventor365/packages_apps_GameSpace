/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package io.chaldeaprjkt.gamespace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.chaldeaprjkt.gamespace.data.GameAutoDetector

/** A freshly installed game is added to the list straight away (if auto detection is on). */
class PackageAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val pending = goAsync()
        GameAutoDetector.scanAsync(context, pkg) { pending.finish() }
    }
}
