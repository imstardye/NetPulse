package com.github.kr328.clash.design.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.util.Log
import com.github.kr328.clash.common.compat.foreground
import com.github.kr328.clash.design.model.AppInfo

private const val TAG = "AppUtil"

fun PackageInfo.toAppInfo(pm: PackageManager): AppInfo {
    val appInfo = applicationInfo ?: throw IllegalStateException("applicationInfo is null for $packageName")
    
    val icon = try {
        appInfo.loadIcon(pm).foreground()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load icon for $packageName, using placeholder", e)
        ColorDrawable(0xFF757575.toInt())
    }
    
    val label = try {
        appInfo.loadLabel(pm).toString()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load label for $packageName, using package name", e)
        packageName
    }
    
    return AppInfo(
        packageName = packageName,
        icon = icon,
        label = label,
        installTime = firstInstallTime,
        updateDate = lastUpdateTime,
    )
}
