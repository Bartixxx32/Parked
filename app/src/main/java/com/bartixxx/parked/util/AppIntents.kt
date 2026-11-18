package com.bartixxx.parked.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.bartixxx.parked.R

object AppIntents {

    fun openAppDetails(context: Context): Boolean = start(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )

    fun openLocationSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    )

    fun openBluetoothSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
    )

    fun openNotificationSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        },
    )

    fun openBatteryOptimizationSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        if (start(context, direct)) return true
        return openBatteryOptimizationSettings(context)
    }

    /**
     * Opens OEM autostart screens when available (ColorOS/coloros, OxygenOS, MIUI, EMUI),
     * otherwise falls back to the app details screen.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val components = listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.startupapp.StartupAppListActivity",
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        components.forEach { component ->
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (start(context, intent)) return true
        }
        return openAppDetails(context)
    }

    fun navigateTo(context: Context, latitude: Double, longitude: Double, label: String): Boolean {
        val encodedLabel = Uri.encode(label.ifBlank { context.getString(R.string.parked_car_default) })
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($encodedLabel)")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(intent, context.getString(R.string.open_navigation)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, chooser)
    }

    fun shareText(context: Context, text: String, subject: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val chooser = Intent.createChooser(intent, subject).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, chooser)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (!(context is android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
