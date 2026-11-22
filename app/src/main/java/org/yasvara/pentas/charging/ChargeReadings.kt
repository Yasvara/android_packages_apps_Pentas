/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import org.yasvara.pentas.thermal.KernelNodeIO
import org.yasvara.pentas.thermal.ThermalNodes
import java.io.File
import kotlin.math.abs

/**
 * Live charging, thermal and bypass telemetry read from kernel sysfs.
 * Board thermistor (`batt_therm`) runs ~5–6 °C hotter than the fuel-gauge cell (`battery`).
 */
data class ChargeReadings(
    val cellC: Float?,
    val boardC: Float?,
    val pumpC: Float?,
    val cpDieC: Float? = null,
    val cpFswKhz: Int? = null,
    val capacity: Int? = null,
    val packWatts: Float? = null,
    val packMa: Int? = null,
    val packMv: Int? = null,
    val charging: Boolean = false,
    val usbOnline: Boolean = false,
    val adapterType: String? = null,
    val usbMv: Int? = null,
    val bypassMode: Int = ThermalNodes.BYPASS_MODE_OFF,
    val bypassState: Int = ThermalNodes.BYPASS_ST_IDLE,
    val bypassDrainMa: Int = 0,
    val bypassFccMa: Int = 0,
) {
    /** Board minus cell, °C. Null when either sensor is unavailable. */
    val boardOffsetC: Float?
        get() = if (cellC != null && boardC != null) boardC - cellC else null

    val isBypassActive: Boolean
        get() = bypassState in ThermalNodes.BYPASS_ST_ACTIVE..ThermalNodes.BYPASS_ST_CP_BYPASS

    val isCpBypassActive: Boolean
        get() = bypassState == ThermalNodes.BYPASS_ST_CP_BYPASS

    fun bypassStateLabel(): String = when (bypassState) {
        ThermalNodes.BYPASS_ST_CP_BYPASS -> "CP Direct (98%)"
        ThermalNodes.BYPASS_ST_ACTIVE -> "Buck Active"
        ThermalNodes.BYPASS_ST_DEFICIT -> "Deficit"
        ThermalNodes.BYPASS_ST_ASSIST -> "Assist"
        ThermalNodes.BYPASS_ST_FULL -> "Full"
        else -> if (bypassMode == ThermalNodes.BYPASS_MODE_OFF) "Off" else "Standby"
    }

    fun adapterSummary(): String = when {
        !usbOnline -> "Unplugged"
        usbMv != null && usbMv > 0 ->
            "%s · %.1f V".format(adapterType ?: "USB", usbMv / 1000f)
        else -> adapterType ?: "USB Connected"
    }

    companion object {
        private const val GAUGE_PATH = "/sys/class/power_supply/battery/temp"
        private const val THERMAL_ROOT = "/sys/class/thermal"

        fun read(dynamics: ChargeDynamics = ChargeDynamics()): ChargeReadings {
            val batt = uevent("battery")
            val usb = uevent("usb")
            val usbOnline = usb["ONLINE"]?.trim() == "1"

            // On QTI/MCA fuel gauge, CURRENT_NOW is negative while charging; invert to charge-positive.
            val currentUa = batt.num("CURRENT_NOW", -60_000_000L..60_000_000L)?.let { -it }
            val voltageUv = batt.num("VOLTAGE_NOW", 0L..6_000_000L)
            val watts = if (currentUa != null && voltageUv != null) {
                abs(currentUa / 1e6f * voltageUv / 1e6f)
            } else null

            val bypassParts = KernelNodeIO.read(ThermalNodes.BYPASS_STATE)
                ?.trim()?.split(' ')?.mapNotNull { it.toIntOrNull() }.orEmpty()

            // Only query SC8581 charge-pump nodes while plugged in to avoid waking idle I2C ADC.
            val cpDieC = if (usbOnline) {
                KernelNodeIO.readInt(ThermalNodes.CP_TDIE)
                    ?.takeIf { it in 1..150 }?.toFloat()
            } else null
            val cpFswKhz = if (usbOnline) {
                KernelNodeIO.readInt(ThermalNodes.CP_FSW)?.takeIf { it in 100..2000 }
            } else null

            return ChargeReadings(
                cellC = readSensor(dynamics.cellSensor),
                boardC = readSensor(dynamics.boardSensor),
                pumpC = readSensor(dynamics.pumpSensor),
                cpDieC = cpDieC,
                cpFswKhz = cpFswKhz,
                capacity = batt["CAPACITY"]?.trim()?.toIntOrNull(),
                packWatts = watts,
                packMa = currentUa?.let { (it / 1000L).toInt() },
                packMv = voltageUv?.let { (it / 1000L).toInt() },
                charging = (currentUa ?: 0L) > 50_000L,
                usbOnline = usbOnline,
                adapterType = usb["REAL_TYPE"]?.takeIf { it.isNotBlank() } ?: usb["TYPE"],
                usbMv = usb.num("VOLTAGE_NOW", 0L..25_000_000L)?.let { (it / 1000L).toInt() },
                bypassMode = KernelNodeIO.readInt(ThermalNodes.BYPASS_CHARGE)
                    ?: ThermalNodes.BYPASS_MODE_OFF,
                bypassState = bypassParts.getOrElse(0) { ThermalNodes.BYPASS_ST_IDLE },
                bypassDrainMa = bypassParts.getOrElse(1) { 0 },
                bypassFccMa = bypassParts.getOrElse(2) { 0 },
            )
        }

        private fun uevent(dir: String): Map<String, String> = runCatching {
            File("/sys/class/power_supply/$dir/uevent").readLines().mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let {
                    line.substring(0, it).removePrefix("POWER_SUPPLY_") to line.substring(it + 1)
                }
            }.toMap()
        }.getOrDefault(emptyMap())

        private fun Map<String, String>.num(key: String, sane: LongRange): Long? =
            this[key]?.trim()?.toLongOrNull()?.takeIf { it in sane }

        /**
         * Resolve a sensor by *name*, never by zone index — zone numbering is
         * not stable across kernels, and `batt_therm` has moved before.
         */
        private fun readSensor(name: String): Float? {
            if (name == "battery") {
                return readFile(GAUGE_PATH)?.let { it / 10f }
            }
            val zone = zoneByType(name) ?: return null
            return readFile("$zone/temp")?.let { it / 1000f }
        }

        private fun zoneByType(type: String): String? =
            runCatching {
                File(THERMAL_ROOT).listFiles { f -> f.name.startsWith("thermal_zone") }
                    ?.firstOrNull { readText("${it.path}/type") == type }
                    ?.path
            }.getOrNull()

        private fun readText(path: String): String? =
            runCatching { File(path).readText().trim() }.getOrNull()

        private fun readFile(path: String): Float? =
            readText(path)?.toFloatOrNull()
    }
}
