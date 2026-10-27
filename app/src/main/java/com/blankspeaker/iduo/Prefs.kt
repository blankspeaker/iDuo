package com.blankspeaker.iduo

import android.content.Context

data class CrackSettings(
    val severity: Int = 55,      // 0 = hairline, 100 = shattered
    val deadLine: Boolean = true,
    val flicker: Boolean = true,
    val seed: Long = 20261023L,
)

object Prefs {
    private const val NAME = "iduo"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): CrackSettings = sp(ctx).let {
        CrackSettings(
            severity = it.getInt("severity", 55),
            deadLine = it.getBoolean("deadLine", true),
            flicker = it.getBoolean("flicker", true),
            seed = it.getLong("seed", 20261023L),
        )
    }

    fun save(ctx: Context, s: CrackSettings) {
        sp(ctx).edit()
            .putInt("severity", s.severity)
            .putBoolean("deadLine", s.deadLine)
            .putBoolean("flicker", s.flicker)
            .putLong("seed", s.seed)
            .apply()
    }
}
