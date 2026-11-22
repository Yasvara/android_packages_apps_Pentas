/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.overlay.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo

/** One package's PSS summed over its running processes. */
data class AppMemory(val pkg: String, val pssKb: Long, val system: Boolean)

/**
 * Running app processes per package, heaviest first. Memory comes from one batched AMS call:
 * other apps' /proc is closed to system_app, and AMS reuses a process's PSS for a few minutes.
 */
class RunningApps(context: Context) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val pm = context.packageManager
    private val system = HashMap<String, Boolean>()

    fun sample(): List<AppMemory> {
        val procs = am.runningAppProcesses.orEmpty()
        val mem = am.getProcessMemoryInfo(IntArray(procs.size) { procs[it].pid })
        val kb = HashMap<String, Long>()
        procs.forEachIndexed { i, p ->
            val pkg = p.pkgList?.firstOrNull() ?: p.processName
            kb[pkg] = (kb[pkg] ?: 0L) + mem[i].totalPss
        }
        return kb.map { (pkg, pss) -> AppMemory(pkg, pss, system.getOrPut(pkg) { isSystem(pkg) }) }
            .sortedByDescending { it.pssKb }
    }

    private fun isSystem(pkg: String) = runCatching {
        pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(true)
}
