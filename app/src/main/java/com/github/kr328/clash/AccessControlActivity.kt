package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.design.AccessControlQuickDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class AccessControlActivity : BaseActivity<AccessControlQuickDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = withContext(Dispatchers.IO) {
            try {
                service.accessControlPackages.toMutableSet()
            } catch (e: Exception) {
                e.printStackTrace()
                mutableSetOf<String>()
            }
        }

        var currentMode = withContext(Dispatchers.IO) {
            try {
                service.accessControlMode
            } catch (e: Exception) {
                e.printStackTrace()
                AccessControlMode.AcceptAll
            }
        }

        defer {
            withContext(Dispatchers.IO) {
                try {
                    val changed = selected != service.accessControlPackages || currentMode != service.accessControlMode
                    service.accessControlPackages = selected
                    service.accessControlMode = currentMode
                    if (clashRunning && changed) {
                        stopClashService()
                        var retries = 0
                        while (clashRunning && retries < 50) {
                            delay(200)
                            retries++
                        }
                        startClashService()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val design = AccessControlQuickDesign(this, uiStore, selected, currentMode)

        setContentDesign(design)

        design.requests.send(AccessControlQuickDesign.Request.ReloadApps)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive { request ->
                    try {
                        when (request) {
                            AccessControlQuickDesign.Request.ReloadApps -> {
                                val apps = loadApps(selected)
                                design.patchApps(apps)
                                design.updateSelectedCount()
                            }

                            AccessControlQuickDesign.Request.SelectAll -> {
                                val all = withContext(Dispatchers.Default) {
                                    design.apps.map(AppInfo::packageName)
                                }

                                selected.clear()
                                selected.addAll(all)

                                design.rebindAll()
                                design.updateSelectedCount()
                            }

                            AccessControlQuickDesign.Request.SelectNone -> {
                                selected.clear()

                                design.rebindAll()
                                design.updateSelectedCount()
                            }

                            AccessControlQuickDesign.Request.SelectInvert -> {
                                val all = withContext(Dispatchers.Default) {
                                    design.apps.map(AppInfo::packageName).toSet() - selected
                                }

                                selected.clear()
                                selected.addAll(all)

                                design.rebindAll()
                                design.updateSelectedCount()
                            }

                            AccessControlQuickDesign.Request.Import -> {
                                val clipboard = getSystemService<ClipboardManager>()
                                val data = clipboard?.primaryClip

                                if (data != null && data.itemCount > 0) {
                                    val text = data.getItemAt(0).text
                                    if (text != null) {
                                        val packages = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                        val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                        selected.clear()
                                        selected.addAll(all)
                                    }
                                }

                                design.rebindAll()
                                design.updateSelectedCount()
                            }

                            AccessControlQuickDesign.Request.Export -> {
                                val clipboard = getSystemService<ClipboardManager>()

                                val data = ClipData.newPlainText(
                                    "packages",
                                    selected.joinToString("\n")
                                )

                                clipboard?.setPrimaryClip(data)
                            }

                            is AccessControlQuickDesign.Request.ChangeMode -> {
                                currentMode = request.mode
                                design.updateSelectedCount()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private suspend fun loadApps(selected: Set<String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            try {
                val reverse = uiStore.accessControlReverse
                val sort = uiStore.accessControlSort
                val systemApp = uiStore.accessControlSystemApp

                val base = compareByDescending<AppInfo> { it.packageName in selected }
                val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

                val pm = packageManager
                val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

                packages.asSequence()
                    .filter {
                        it.packageName != packageName
                    }
                    .filter {
                        it.applicationInfo != null
                    }
                    .filter {
                        val hasInternet = it.requestedPermissions?.contains(INTERNET) == true
                        val isSystemUid = it.applicationInfo?.uid?.let { uid ->
                            uid < android.os.Process.FIRST_APPLICATION_UID
                        } ?: false
                        hasInternet || isSystemUid
                    }
                    .filter {
                        systemApp || !it.isSystemApp
                    }
                    .mapNotNull {
                        try {
                            it.toAppInfo(pm)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                    }
                    .sortedWith(comparator)
                    .toList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    private val PackageInfo.isSystemApp: Boolean
        get() {
            return applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        }
}
