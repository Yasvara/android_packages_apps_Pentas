/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

/** Charging catalog. Currents are µA; [UA_PER_WATT] is the only watt conversion. */
object ChargingProfiles {
    // ---- Settings.System keys ----
    /** JSON array of all user profiles: [{id,name,suspend,resume,tiers:[...]}] */
    const val KEY_CUSTOM_PROFILES = "mithermal_charging_profiles"
    /** Global default charging profile id ("" = vendor GLOBAL). */
    const val KEY_GLOBAL_PROFILE = "mithermal_global_charging_profile"
    /** Per-app override: {"com.pkg":"profile_id", ...} */
    const val KEY_APP_PROFILES = "mithermal_charging_app_profiles"

    // ---- Daemon IPC props (must match sic.rs) ----
    const val PROP_MODE   = "persist.sys.mithermal.charging_profile" // "global"|"custom"
    const val PROP_SEQ    = "persist.sys.mithermal.charging.seq"
    const val PROP_COUNT  = "persist.sys.mithermal.charging.n"
    const val PROP_CHUNK_PREFIX = "persist.sys.mithermal.charging."
    const val PROP_CHUNK_SIZE = 88
    const val PROP_CHUNK_MAX = 64

    const val MAX_TIERS = 12


    // ---- Unit conversion ----
    // ABS silicon ceiling (SC8581 OCP x4). The vendor *policy* default is 20 A;
    // profiles widen the live kernel envelope up to ABS via PolicyEnvelope.
    const val FCC_HARD_MIN_UA = 100_000L      // 100 mA
    const val FCC_HARD_MAX_UA = 25_000_000L   // 25 A ABS

    /** Charger rating in Watt, as printed on the box. */
    const val MIN_WATT = 1f
    const val DEFAULT_MAX_WATT = 100f

    /**
     * Dynamically detected from Device Tree (/sys/firmware/devicetree/base/soc/mca_strategy_quick_charge/div_max_curr),
     * or falls back to [DEFAULT_MAX_WATT]. With [UA_PER_WATT] = 222_000, onyx's 22 A SC8581 limit
     * maps directly to 100 W (90 W sustained = 20 A).
     */
    val MAX_WATT: Float by lazy {
        detectMaxWatt()
    }

    private fun detectMaxWatt(): Float {
        val dtPaths = listOf(
            "/sys/firmware/devicetree/base/soc/mca_strategy_quick_charge/div_max_curr",
            "/sys/firmware/devicetree/base/soc/mca_strategy_quick_wireless_class/div_max_curr"
        )
        for (path in dtPaths) {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes != null && bytes.size >= 4) {
                    var maxMa = 0
                    for (i in 0 until bytes.size step 4) {
                        if (i + 3 < bytes.size) {
                            val v = ((bytes[i].toInt() and 0xFF) shl 24) or
                                    ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                                    ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                                    (bytes[i + 3].toInt() and 0xFF)
                            if (v in 1000..50000 && v > maxMa) {
                                maxMa = v
                            }
                        }
                    }
                    if (maxMa > 0) {
                        val watt = uaToWatt(maxMa * 1000L)
                        if (watt >= 10f) {
                            return kotlin.math.ceil(watt)
                        }
                    }
                }
            }
        }
        return DEFAULT_MAX_WATT
    }

    /**
     * Charger-side watts to battery-side µA for a 1S Si-C cell (~4.25 V avg, ~96.5% CP efficiency):
     * 90 W ≙ 20.0 A, 100 W ≙ 22.2 A, 67 W ≙ 14.9 A, 45 W ≙ 10.0 A, 30 W ≙ 6.7 A, 15 W ≙ 3.3 A.
     */
    const val UA_PER_WATT = 222_000f

    fun wattToUa(w: Float): Long =
        (w.toDouble() * UA_PER_WATT).toLong().coerceIn(FCC_HARD_MIN_UA, FCC_HARD_MAX_UA)

    fun uaToWatt(ua: Long): Float = ua / UA_PER_WATT

    fun uaToAmp(ua: Long): Float = ua / 1_000_000f

    fun mcToC(mc: Long): Float = mc / 1000f
    fun cToMc(c: Float): Long = (c * 1000f).toLong()
}
