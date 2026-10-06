package com.han2.app

import android.content.Context

data class SharedRiskState(
    val hasDraft: Boolean,
    val level: RiskLevel,
    val appPackage: String,
    val updatedAt: Long
)

/** Stores metadata only. The user's draft text never leaves the IME process. */
object RiskStateStore {
    fun write(context: Context, state: SharedRiskState) {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(KeyboardPreferences.NAME, Context.MODE_MULTI_PROCESS)
            .edit()
            .putBoolean(KeyboardPreferences.HAS_DRAFT, state.hasDraft)
            .putString(KeyboardPreferences.RISK_LEVEL, state.level.name)
            .putString(KeyboardPreferences.RISK_APP_PACKAGE, state.appPackage)
            .putLong(KeyboardPreferences.RISK_UPDATED_AT, state.updatedAt)
            .commit()
    }

    fun read(context: Context): SharedRiskState {
        @Suppress("DEPRECATION")
        val prefs = context.getSharedPreferences(KeyboardPreferences.NAME, Context.MODE_MULTI_PROCESS)
        val level = runCatching {
            RiskLevel.valueOf(prefs.getString(KeyboardPreferences.RISK_LEVEL, RiskLevel.SAFE.name)!!)
        }.getOrDefault(RiskLevel.SAFE)
        return SharedRiskState(
            prefs.getBoolean(KeyboardPreferences.HAS_DRAFT, false),
            level,
            prefs.getString(KeyboardPreferences.RISK_APP_PACKAGE, "").orEmpty(),
            prefs.getLong(KeyboardPreferences.RISK_UPDATED_AT, 0L)
        )
    }
}
