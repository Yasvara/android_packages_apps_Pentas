/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.charging

import org.json.JSONArray
import org.json.JSONObject

/**
 * Charging limits pushed at runtime: JEITA bands, per-adapter ceilings, the thermal derate
 * ladder and the bypass regulator. The wire format is parsed by the daemon's `policy.rs`;
 * field names and units must match it.
 */

/**
 * One JEITA band, columns as the kernel's `jeita_table`: `t_lo t_hi hys_lo hys_hi fcc_mA fv_mV iterm_mA`.
 * ±65535 means unbounded; [fvMv] 0 means the band does not charge.
 */
data class JeitaBand(
    val tLoC: Int,
    val tHiC: Int,
    val hysLo: Int = 1,
    val hysHi: Int = 1,
    val fccMa: Int = 0,
    val fvMv: Int = 0,
    val itermMa: Int = 572,
) {
    fun toJsonArray(): JSONArray = JSONArray().apply {
        put(tLoC); put(tHiC); put(hysLo); put(hysHi); put(fccMa); put(fvMv); put(itermMa)
    }

    /** True when this band refuses to charge — the thing users see as "stuck". */
    val isCutoff: Boolean get() = fccMa == 0 || fvMv == 0

    companion object {
        const val SENTINEL_LO = -65535
        const val SENTINEL_HI = 65535

        fun fromJsonArray(a: JSONArray): JeitaBand? {
            if (a.length() < 7) return null
            return JeitaBand(
                tLoC = a.optInt(0), tHiC = a.optInt(1),
                hysLo = a.optInt(2), hysHi = a.optInt(3),
                fccMa = a.optInt(4), fvMv = a.optInt(5), itermMa = a.optInt(6),
            )
        }
    }
}

/**
 * One rung of `wired_thermal_table` ([level] is 1-based). Currents: buck 5V/9V input, buck
 * 5V/9V charge, then div1/div2/div4 single and multi.
 */
data class ThermalLadderRow(
    val level: Int,
    val currentsMa: List<Int>,
) {
    fun toJsonArray(): JSONArray = JSONArray().apply {
        put(level)
        currentsMa.forEach { put(it) }
    }

    companion object {
        const val COLS = 10

        fun flat(level: Int, inputMa: Int, packMa: Int) = ThermalLadderRow(
            level = level,
            currentsMa = List(2) { inputMa } + List(COLS - 2) { packMa },
        )

        fun fromJsonArray(a: JSONArray): ThermalLadderRow? {
            if (a.length() != COLS + 1) return null
            return ThermalLadderRow(
                level = a.optInt(0),
                currentsMa = (1..COLS).map { a.optInt(it) },
            )
        }
    }
}

/**
 * Hybrid bypass regulator (Buck ZDFB v3 + SC8581 DIV2 direct-drive CP-Bypass).
 * Buck ZDFB v3 handles <12 W and non-PPS adapters; when [cpEnable] is true on any
 * >=25 W PPS adapter and system load exceeds [cpEnterMw], SC8581 takes over at ~98% eff.
 */
data class BypassTuning(
    /** Pack current to hold, mA, charge-positive. 0 = true bypass. */
    val targetMa: Int = 0,
    val fccMaxMa: Int = 3_000,
    val kp: Int = 15,
    /** ZDFB v3 deadband around [targetMa] (mA). 30 mA prevents buck step hunting. */
    val deadbandMa: Int = 30,
    /** ZDFB v3 coarse adjustment step (mA). */
    val stepMa: Int = 50,
    val in9vMa: Int = 3_000,
    val in5vMa: Int = 3_000,
    /** Enable SC8581 DIV2 direct-drive CP-Bypass for heavy loads on PPS adapters. */
    val cpEnable: Boolean = true,
    /** System load threshold (mW) to hand off from buck to SC8581 CP-Bypass (6000..25000). */
    val cpEnterMw: Int = 12_000,
    /** Max cell voltage (mV) allowed in CP-Bypass before returning to buck (4200..4480). */
    val cpVbatMaxMv: Int = 4_450,
    val mode: BypassMode = BypassMode.AUTO,
) {
    fun normalized(): BypassTuning = copy(
        targetMa = targetMa.coerceIn(-500, 3_000),
        fccMaxMa = fccMaxMa.coerceIn(0, ChargingEnvelope.FCC_MAX_MA),
        kp = kp.coerceIn(0, 1_000),
        deadbandMa = deadbandMa.coerceIn(0, 500),
        stepMa = stepMa.coerceIn(1, 1_000),
        in9vMa = in9vMa.coerceIn(0, ChargingEnvelope.IIN_MAX_MA),
        in5vMa = in5vMa.coerceIn(0, ChargingEnvelope.IIN_MAX_MA),
        cpEnterMw = cpEnterMw.coerceIn(ChargingEnvelope.CP_ENTER_MIN_MW, ChargingEnvelope.CP_ENTER_MAX_MW),
        cpVbatMaxMv = cpVbatMaxMv.coerceIn(ChargingEnvelope.CP_VBAT_MIN_MV, ChargingEnvelope.CP_VBAT_MAX_MV),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("target", targetMa); put("fcc_max", fccMaxMa)
        put("kp", kp); put("deadband", deadbandMa); put("step", stepMa)
        put("in9v", in9vMa); put("in5v", in5vMa)
        put("cp_en", if (cpEnable) 1 else 0)
        put("cp_mw", cpEnterMw)
        put("cp_vbat", cpVbatMaxMv)
        put("mode", mode.wire)
    }

    companion object {
        fun fromJson(o: JSONObject) = BypassTuning(
            targetMa = o.optInt("target", 0),
            fccMaxMa = o.optInt("fcc_max", 3_000),
            kp = o.optInt("kp", 15),
            deadbandMa = o.optInt("deadband", 30),
            stepMa = o.optInt("step", 50),
            in9vMa = o.optInt("in9v", 3_000),
            in5vMa = o.optInt("in5v", 3_000),
            cpEnable = o.optInt("cp_en", 1) != 0,
            cpEnterMw = o.optInt("cp_mw", 12_000),
            cpVbatMaxMv = o.optInt("cp_vbat", 4_450),
            mode = BypassMode.fromWire(o.optInt("mode", BypassMode.AUTO.wire)),
        )
    }
}

/** Matches `BYPASS_CHG_MODE_*` in mca_smart_charge.h. */
enum class BypassMode(val wire: Int, val label: String) {
    OFF(0, "Off"),
    FORCE(1, "Always on"),
    AUTO(2, "Games only");

    companion object {
        fun fromWire(v: Int) = entries.firstOrNull { it.wire == v } ?: AUTO
    }
}

/**
 * Absolute silicon and chemistry limits (OCP/OVP, Li-ion plating); must match the kernel's
 * `mca_policy.h` and the daemon's `config.rs`. A profile may widen the vendor default up to these.
 */
object ChargingEnvelope {
    const val FV_MAX_MV = 4_750
    const val FV_MIN_MV = 3_000
    const val TEMP_MIN_C = -20
    const val TEMP_MAX_C = 60
    const val FCC_MAX_MA = 25_000
    const val IIN_MAX_MA = 8_000
    const val ITERM_MIN_MA = 50
    const val ITERM_MAX_MA = 6_000
    const val CP_ENTER_MIN_MW = 6_000
    const val CP_ENTER_MAX_MW = 25_000
    const val CP_VBAT_MIN_MV = 4_200
    const val CP_VBAT_MAX_MV = 4_480

    /** Vendor policy defaults (kernel power-on). Profiles without an explicit
     *  [PolicyEnvelope] run inside these. */
    const val POLICY_FCC_DEFAULT_MA = 20_000
    const val POLICY_IIN_DEFAULT_MA = 6_000
    const val POLICY_FV_DEFAULT_MV = 4_600

    fun isSentinel(c: Int) = c <= -1000 || c >= 1000

    fun clampFv(mv: Int) = if (mv == 0) 0 else mv.coerceIn(FV_MIN_MV, FV_MAX_MV)
    fun clampFcc(ma: Int) = ma.coerceIn(0, FCC_MAX_MA)
    fun clampIin(ma: Int) = ma.coerceIn(0, IIN_MAX_MA)
    fun clampIterm(ma: Int) = if (ma == 0) 0 else ma.coerceIn(ITERM_MIN_MA, ITERM_MAX_MA)
    fun clampTemp(c: Int) = if (isSentinel(c)) c else c.coerceIn(TEMP_MIN_C, TEMP_MAX_C)

    fun clampBand(b: JeitaBand) = b.copy(
        tLoC = clampTemp(b.tLoC),
        tHiC = clampTemp(b.tHiC),
        fccMa = clampFcc(b.fccMa),
        fvMv = clampFv(b.fvMv),
        itermMa = clampIterm(b.itermMa),
    )
}

/** Per-adapter input and charge ceilings. Keys match the kernel `chg_caps` node. */
data class AdapterCaps(
    val inputMa: Map<String, Int> = emptyMap(),
    val chargeMa: Map<String, Int> = emptyMap(),
    val ffcTempLowC: Int? = null,
    val ffcTempHighC: Int? = null,
) {
    val isEmpty: Boolean
        get() = inputMa.isEmpty() && chargeMa.isEmpty() &&
                ffcTempLowC == null && ffcTempHighC == null

    fun toJson(): JSONObject = JSONObject().apply {
        inputMa.forEach { (k, v) -> put("in_$k", ChargingEnvelope.clampIin(v)) }
        chargeMa.forEach { (k, v) -> put("chg_$k", ChargingEnvelope.clampFcc(v)) }
        ffcTempLowC?.let { put("ffc_temp_low", ChargingEnvelope.clampTemp(it)) }
        ffcTempHighC?.let { put("ffc_temp_high", ChargingEnvelope.clampTemp(it)) }
    }

    companion object {
        /** Adapter classes the driver exposes, in the order the editor shows them. */
        val KEYS = listOf(
            "pd", "pps", "hvdcp3", "hvdcp3p5", "hvdcp", "dcp", "cdp", "sdp", "float",
        )

        fun fromJson(o: JSONObject): AdapterCaps {
            val ins = mutableMapOf<String, Int>()
            val chg = mutableMapOf<String, Int>()
            KEYS.forEach { k ->
                if (o.has("in_$k")) ins[k] = o.optInt("in_$k")
                if (o.has("chg_$k")) chg[k] = o.optInt("chg_$k")
            }
            return AdapterCaps(
                inputMa = ins,
                chargeMa = chg,
                ffcTempLowC = if (o.has("ffc_temp_low")) o.optInt("ffc_temp_low") else null,
                ffcTempHighC = if (o.has("ffc_temp_high")) o.optInt("ffc_temp_high") else null,
            )
        }
    }
}

/**
 * Control-temperature source and slew, published as `persist.sys.mithermal.chg.*`. The board
 * sensor reads ~6 °C above the cell, hence the default offset.
 */
data class ChargeDynamics(
    val cellSensor: String = "battery",
    val boardSensor: String = "batt_therm",
    val pumpSensor: String = "charger_therm",
    /** Board→cell calibration, deci-°C. */
    val boardOffsetDc: Int = 60,
    val wCell: Int = 800,
    val wBoard: Int = 200,
    /** mca charge path the votes are addressed to. "all" reaches the charge pump. */
    val chgPath: String = "all",
    val suspendHoldCycles: Int = 30,
    val predAheadS: Int = 12,
    /** Per-mille. */
    val predGain: Int = 600,
    /** Deci-°C. */
    val predCapDc: Int = 20,
    /** µA per 1 Hz cycle; 0 = unlimited. */
    val slewUa: Int = 500_000,
    val netzero: Boolean = false,
    val netzeroTargetMa: Int = 0,
) {
    /** Property map consumed by `config::ChargeTuning::reload()`. */
    fun toProps(): Map<String, String> = mapOf(
        "persist.sys.mithermal.chg.cell_sensor" to cellSensor,
        "persist.sys.mithermal.chg.board_sensor" to boardSensor,
        "persist.sys.mithermal.chg.pump_sensor" to pumpSensor,
        "persist.sys.mithermal.chg.board_offset" to boardOffsetDc.toString(),
        "persist.sys.mithermal.chg.w_cell" to wCell.toString(),
        "persist.sys.mithermal.chg.w_board" to wBoard.toString(),
        "persist.sys.mithermal.chg.path" to chgPath,
        "persist.sys.mithermal.chg.suspend_hold" to suspendHoldCycles.toString(),
        "persist.sys.mithermal.chg.pred_ahead" to predAheadS.toString(),
        "persist.sys.mithermal.chg.pred_gain" to predGain.toString(),
        "persist.sys.mithermal.chg.pred_cap" to predCapDc.toString(),
        "persist.sys.mithermal.chg.slew" to slewUa.toString(),
        "persist.sys.mithermal.chg.netzero" to netzero.toString(),
        "persist.sys.mithermal.chg.netzero_ma" to netzeroTargetMa.toString(),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("cell", cellSensor); put("board", boardSensor); put("pump", pumpSensor)
        put("board_off", boardOffsetDc); put("w_cell", wCell); put("w_board", wBoard)
        put("path", chgPath); put("hold", suspendHoldCycles)
        put("ahead", predAheadS); put("gain", predGain); put("cap", predCapDc)
        put("slew", slewUa); put("netzero", netzero); put("netzero_ma", netzeroTargetMa)
    }

    companion object {
        fun fromJson(o: JSONObject) = ChargeDynamics(
            cellSensor = o.optString("cell", "battery"),
            boardSensor = o.optString("board", "batt_therm"),
            pumpSensor = o.optString("pump", "charger_therm"),
            boardOffsetDc = o.optInt("board_off", 60),
            wCell = o.optInt("w_cell", 800),
            wBoard = o.optInt("w_board", 200),
            chgPath = o.optString("path", "all"),
            suspendHoldCycles = o.optInt("hold", 30),
            predAheadS = o.optInt("ahead", 12),
            predGain = o.optInt("gain", 600),
            predCapDc = o.optInt("cap", 20),
            slewUa = o.optInt("slew", 500_000),
            netzero = o.optBoolean("netzero", false),
            netzeroTargetMa = o.optInt("netzero_ma", 0),
        )
    }
}

/** Kernel envelope to request before the tables; null keeps the vendor default (20 A / 4600 mV). */
data class PolicyEnvelope(
    val fccMaxMa: Int? = null,
    val iinMaxMa: Int? = null,
    val fvMaxMv: Int? = null,
    val tempMinC: Int? = null,
    val tempMaxC: Int? = null,
) {
    val isEmpty: Boolean
        get() = fccMaxMa == null && iinMaxMa == null && fvMaxMv == null &&
                tempMinC == null && tempMaxC == null

    fun toJson(): JSONObject = JSONObject().apply {
        fccMaxMa?.let { put("fcc_max", it) }
        iinMaxMa?.let { put("iin_max", it) }
        fvMaxMv?.let { put("fv_max", it) }
        tempMinC?.let { put("temp_min", it) }
        tempMaxC?.let { put("temp_max", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = PolicyEnvelope(
            fccMaxMa = if (o.has("fcc_max")) o.optInt("fcc_max") else null,
            iinMaxMa = if (o.has("iin_max")) o.optInt("iin_max") else null,
            fvMaxMv = if (o.has("fv_max")) o.optInt("fv_max") else null,
            tempMinC = if (o.has("temp_min")) o.optInt("temp_min") else null,
            tempMaxC = if (o.has("temp_max")) o.optInt("temp_max") else null,
        )
    }
}

/** The limits charging runs inside; a null section resets the kernel default for it. */
data class ChargingPolicy(
    val envelope: PolicyEnvelope = PolicyEnvelope(),
    val jeitaNormal: List<JeitaBand> = emptyList(),
    val jeitaFfc: List<JeitaBand> = emptyList(),
    /** Float voltage above which a hot band steps down instead of cutting off. */
    val hotFvGateMv: Int? = null,
    val hotRechargeHysMv: Int? = null,
    val hotTermHysMv: Int? = null,
    val caps: AdapterCaps = AdapterCaps(),
    val ladder: List<ThermalLadderRow> = emptyList(),
    val bypass: BypassTuning? = null,
    val dynamics: ChargeDynamics = ChargeDynamics(),
) {
    val isEmpty: Boolean
        get() = envelope.isEmpty && jeitaNormal.isEmpty() && jeitaFfc.isEmpty() && caps.isEmpty &&
                ladder.isEmpty() && bypass == null && hotFvGateMv == null

    /** Clamp every field into the ABS silicon envelope. Pure and idempotent. */
    fun normalized(): ChargingPolicy = copy(
        jeitaNormal = jeitaNormal.map(ChargingEnvelope::clampBand),
        jeitaFfc = jeitaFfc.map(ChargingEnvelope::clampBand),
        hotFvGateMv = hotFvGateMv?.coerceIn(ChargingEnvelope.FV_MIN_MV, ChargingEnvelope.FV_MAX_MV),
        ladder = ladder.map { row ->
            row.copy(currentsMa = row.currentsMa.mapIndexed { i, v ->
                if (i < 2) ChargingEnvelope.clampIin(v) else ChargingEnvelope.clampFcc(v)
            })
        },
        caps = caps.copy(
            inputMa = caps.inputMa.mapValues { (_, v) -> v.coerceIn(0, ChargingEnvelope.IIN_MAX_MA) },
            chargeMa = caps.chargeMa.mapValues { (_, v) -> v.coerceIn(0, ChargingEnvelope.FCC_MAX_MA) },
        ),
        bypass = bypass?.normalized(),
    )

    /**
     * Daemon wire form. Envelope FIRST (the daemon pushes it before tables so
     * an OC table is not clamped), then sections after `tiers`, because the
     * daemon's tier parser takes the first `[`..`]` pair following `"tiers"`.
     */
    fun writeInto(target: JSONObject) {
        if (!envelope.isEmpty) target.put("envelope", envelope.toJson())
        if (jeitaNormal.isNotEmpty() || jeitaFfc.isNotEmpty()) {
            target.put("jeita", JSONObject().apply {
                if (jeitaNormal.isNotEmpty()) put("normal", bandsJson(jeitaNormal))
                if (jeitaFfc.isNotEmpty()) put("ffc", bandsJson(jeitaFfc))
            })
        }
        val params = JSONObject()
        hotFvGateMv?.let { params.put("hot_fv_gate", it) }
        hotRechargeHysMv?.let { params.put("hot_recharge_hys", it) }
        hotTermHysMv?.let { params.put("hot_term_hys", it) }
        if (params.length() > 0) target.put("jeita_params", params)

        if (!caps.isEmpty) target.put("caps", caps.toJson())
        if (ladder.isNotEmpty()) {
            target.put("ladder", JSONArray().apply { ladder.forEach { put(it.toJsonArray()) } })
        }
        bypass?.let { target.put("bypass", it.toJson()) }
    }

    /** Persistence form — same shape plus the dynamics, which do not go on the wire. */
    fun toJson(): JSONObject = JSONObject().apply {
        writeInto(this)
        put("dynamics", dynamics.toJson())
    }

    private fun bandsJson(bands: List<JeitaBand>): JSONArray =
        JSONArray().apply { bands.forEach { put(it.toJsonArray()) } }

    companion object {
        fun fromJson(o: JSONObject): ChargingPolicy {
            val jeita = o.optJSONObject("jeita")
            val params = o.optJSONObject("jeita_params")
            return ChargingPolicy(
                envelope = o.optJSONObject("envelope")?.let(PolicyEnvelope::fromJson)
                    ?: PolicyEnvelope(),
                jeitaNormal = bands(jeita?.optJSONArray("normal")),
                jeitaFfc = bands(jeita?.optJSONArray("ffc")),
                hotFvGateMv = params?.takeIf { it.has("hot_fv_gate") }?.optInt("hot_fv_gate"),
                hotRechargeHysMv =
                    params?.takeIf { it.has("hot_recharge_hys") }?.optInt("hot_recharge_hys"),
                hotTermHysMv = params?.takeIf { it.has("hot_term_hys") }?.optInt("hot_term_hys"),
                caps = o.optJSONObject("caps")?.let(AdapterCaps::fromJson) ?: AdapterCaps(),
                ladder = o.optJSONArray("ladder")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONArray(i)?.let(ThermalLadderRow::fromJsonArray)
                    }
                }.orEmpty(),
                bypass = o.optJSONObject("bypass")?.let(BypassTuning::fromJson),
                dynamics = o.optJSONObject("dynamics")?.let(ChargeDynamics::fromJson)
                    ?: ChargeDynamics(),
            )
        }

        private fun bands(a: JSONArray?): List<JeitaBand> =
            a?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONArray(i)?.let(JeitaBand::fromJsonArray)
                }
            }.orEmpty()
    }
}
