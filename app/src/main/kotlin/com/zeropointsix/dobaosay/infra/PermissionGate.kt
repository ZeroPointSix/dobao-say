package com.zeropointsix.dobaosay.infra

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Unified runtime permission checks for microphone and notification posting.
 */
object PermissionGate {
    const val REQUEST_VOICE_PERMISSIONS = 2101

    fun missingPermissions(context: Context): List<String> {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }
        return required.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasRecordAudio(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun ensureVoicePermissions(
        activity: Activity,
        onReady: () -> Unit,
    ) {
        val missing = missingPermissions(activity)
        if (missing.isEmpty()) {
            onReady()
            return
        }

        val needsRationale =
            missing.any { permission ->
                activity.shouldShowRequestPermissionRationale(permission)
            }
        if (needsRationale) {
            AlertDialog
                .Builder(activity)
                .setTitle("需要系统权限")
                .setMessage(
                    "dobao-say 只在你主动开始录音时使用麦克风，并通过前台通知标明正在录音。" +
                        "音频会发送给豆包 ASR 以生成文本。",
                )
                .setPositiveButton("继续") { _, _ ->
                    activity.requestPermissions(missing.toTypedArray(), REQUEST_VOICE_PERMISSIONS)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            activity.requestPermissions(missing.toTypedArray(), REQUEST_VOICE_PERMISSIONS)
        }
    }

    fun handlePermissionResult(
        activity: Activity,
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        onGranted: () -> Unit,
        onDenied: (permanentlyDenied: Boolean) -> Unit,
    ): Boolean {
        if (requestCode != REQUEST_VOICE_PERMISSIONS) return false
        val granted =
            grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED } &&
                missingPermissions(activity).isEmpty()
        if (granted) {
            onGranted()
            return true
        }

        val permanentlyDenied =
            permissions.any { permission ->
                grantResults.isNotEmpty() &&
                    !activity.shouldShowRequestPermissionRationale(permission) &&
                    activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
            }
        onDenied(permanentlyDenied)
        return true
    }

    fun openAppSettings(activity: Activity) {
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.packageName, null),
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}
