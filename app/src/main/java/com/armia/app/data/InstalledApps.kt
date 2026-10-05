package com.armia.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

object InstalledApps {

    /** Apps com ícone no launcher, jogos primeiro. Não inclui o próprio Arm-IA. */
    @Suppress("DEPRECATION")
    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val app = info.applicationInfo
                val isGame = app != null && (
                    app.category == ApplicationInfo.CATEGORY_GAME ||
                        (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    )
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    isLikelyGame = isGame
                )
            }
            .sortedWith(compareByDescending<InstalledApp> { it.isLikelyGame }
                .thenBy { it.label.lowercase() })
            .toList()
    }
}
