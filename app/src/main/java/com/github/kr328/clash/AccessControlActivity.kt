package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
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
    companion object {
        private const val TAG = "AccessControlActivity"
    }

    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = withContext(Dispatchers.IO) {
            try {
                service.accessControlPackages.toMutableSet()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load access control packages", e)
                mutableSetOf<String>()
            }
        }

        var currentMode = withContext(Dispatchers.IO) {
            try {
                service.accessControlMode
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load access control mode", e)
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
                    Log.e(TAG, "Failed to save access control settings", e)
                }
            }
        }

        val design = try {
            AccessControlQuickDesign(this, uiStore, selected, currentMode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create design", e)
            finish()
            return
        }

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
                                try {
                                    val clipboard = getSystemService<ClipboardManager>()
                                    val data = clipboard?.primaryClip

                                    if (data != null && data.itemCount > 0) {
                                        val text = data.getItemAt(0)?.text
                                        if (text != null && text.isNotEmpty()) {
                                            val packages = text.split("\n")
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() }
                                                .toSet()
                                            val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                            selected.clear()
                                            selected.addAll(all)
                                        }
                                    }

                                    design.rebindAll()
                                    design.updateSelectedCount()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to import from clipboard", e)
                                }
                            }

                            AccessControlQuickDesign.Request.Export -> {
                                try {
                                    val clipboard = getSystemService<ClipboardManager>()

                                    val data = ClipData.newPlainText(
                                        "packages",
                                        selected.joinToString("\n")
                                    )

                                    clipboard?.setPrimaryClip(data)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to export to clipboard", e)
                                }
                            }

                            is AccessControlQuickDesign.Request.ChangeMode -> {
                                currentMode = request.mode
                                design.updateSelectedCount()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to handle request: $request", e)
                    }
                }
            }
        }
    }

    private suspend fun loadApps(selected: Set<String>): List<AppInfo> =
        withContext(Dispatchers.IO) {
            try {
                val reverse = try {
                    uiStore.accessControlReverse
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load reverse setting", e)
                    false
                }

                val sort = try {
                    uiStore.accessControlSort
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load sort setting", e)
                    com.github.kr328.clash.design.model.AppInfoSort.Label
                }

                val systemApp = try {
                    uiStore.accessControlSystemApp
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load systemApp setting", e)
                    false
                }

                val base = compareByDescending<AppInfo> { it.packageName in selected }
                val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

                val pm = packageManager
                val packages = try {
                    pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get installed packages", e)
                    emptyList()
                }

                packages.asSequence()
                    .filter {
                        it.packageName != packageName
                    }
                    .filter {
                        it.applicationInfo != null
                    }
                    .filter {
                        val hasInternet = try {
                            it.requestedPermissions?.contains(INTERNET) == true
                        } catch (e: Exception) {
                            false
                        }
                        val isSystemUid = try {
                            it.applicationInfo?.uid?.let { uid ->
                                uid < android.os.Process.FIRST_APPLICATION_UID
                            } ?: false
                        } catch (e: Exception) {
                            false
                        }
                        hasInternet || isSystemUid
                    }
                    .filter {
                        systemApp || !it.isSystemApp
                    }
                    .mapNotNull {
                        try {
                            it.toAppInfo(pm)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load app info for ${it.packageName}", e)
                            null
                        }
                    }
                    .sortedWith(comparator)
                    .toList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load apps", e)
                emptyList()
            }
        }

    private val PackageInfo.isSystemApp: Boolean
        get() {
            return try {
                applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (e: Exception) {
                false
            }
        }
}
