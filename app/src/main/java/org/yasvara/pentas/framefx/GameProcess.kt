/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.framefx

import android.app.ActivityManager
import android.content.Context
import android.content.Intent

/** A game's processes, for the framefx features that arm it at start. */
object GameProcess {
    fun running(context: Context, pkg: String): Boolean =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .any { pkg in it.pkgList.orEmpty() }

    /** The game runs, but started before it was armed: a change waits for its next start. */
    fun needsRestart(context: Context, pkg: String): Boolean =
        running(context, pkg) && FramefxBroker.statusPages(pkg).isEmpty()

    fun restart(context: Context, pkg: String) {
        context.getSystemService(ActivityManager::class.java).forceStopPackage(pkg)
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let(context::startActivity)
    }
}
