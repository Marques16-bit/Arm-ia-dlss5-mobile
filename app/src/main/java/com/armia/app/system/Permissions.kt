package com.armia.app.system

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Insets
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Size
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

object Permissions {

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    fun usageAccessSettingsIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            intent
        } else {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

fun realScreenSize(context: Context): Point {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ?: return Point(0, 0)

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getRealScreenSizeApi30Plus(windowManager)
    } else {
        getRealScreenSizeLegacy(windowManager)
    }
}

fun usableScreenSize(context: Context): Size {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ?: return Size(0, 0)

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars()
        )
        val bounds = metrics.bounds
        Size(
            bounds.width() - insets.left - insets.right,
            bounds.height() - insets.top - insets.bottom
        )
    } else {
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getSize(point)
        Size(point.x, point.y)
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private fun getRealScreenSizeApi30Plus(windowManager: WindowManager): Point {
    val metrics = windowManager.maximumWindowMetrics
    val bounds = metrics.bounds
    return Point(bounds.width(), bounds.height())
}

@Suppress("DEPRECATION")
private fun getRealScreenSizeLegacy(windowManager: WindowManager): Point {
    val display = windowManager.defaultDisplay ?: return Point(0, 0)
    val point = Point()
    display.getRealSize(point)
    return point
}
