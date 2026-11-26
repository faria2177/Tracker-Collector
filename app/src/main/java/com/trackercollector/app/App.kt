package com.trackercollector.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyNightMode(this)
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_DARK = "dark_mode"
        private const val KEY_TUTORIAL = "tutorial_shown"

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isDark(c: Context) = prefs(c).getBoolean(KEY_DARK, false)

        fun setDark(c: Context, dark: Boolean) {
            prefs(c).edit().putBoolean(KEY_DARK, dark).apply()
            applyNightMode(c)
        }

        fun applyNightMode(c: Context) = AppCompatDelegate.setDefaultNightMode(
            if (isDark(c)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        /** True only the first time it is called. */
        fun firstTutorial(c: Context): Boolean {
            val p = prefs(c)
            if (p.getBoolean(KEY_TUTORIAL, false)) return false
            p.edit().putBoolean(KEY_TUTORIAL, true).apply()
            return true
        }
    }
}
