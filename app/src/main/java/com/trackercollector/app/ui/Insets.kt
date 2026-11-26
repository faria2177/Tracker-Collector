package com.trackercollector.app.ui

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Light edge-to-edge bars with dark icons; pads the header and the bottom panel. */
fun ComponentActivity.setupLightBars(root: View, bottom: View?) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    )
    val bottomBase = bottom?.paddingBottom ?: 0
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(bars.left, bars.top, bars.right, if (bottom == null) bars.bottom else 0)
        bottom?.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bottomBase + bars.bottom)
        insets
    }
}
