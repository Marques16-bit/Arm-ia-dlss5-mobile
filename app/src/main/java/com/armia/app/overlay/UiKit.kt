package com.armia.app.overlay

import android.content.Context

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()
