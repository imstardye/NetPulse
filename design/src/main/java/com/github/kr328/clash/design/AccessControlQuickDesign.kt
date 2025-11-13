package com.github.kr328.clash.design

import android.content.Context
import android.util.Log
import android.view.View
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.design.adapter.AppAdapter
import com.github.kr328.clash.design.component.AccessControlQuickMenu
import com.github.kr328.clash.design.databinding.DesignAccessControlQuickBinding
import com.github.kr328.clash.design.databinding.DialogSearchBinding
import com.github.kr328.clash.design.dialog.FullScreenDialog
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.AccessControlMode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class AccessControlQuickDesign(
    context: Context,
    uiStore: UiStore,
    private val selected: MutableSet<String>,
    private var currentMode: AccessControlMode,
) : Design<AccessControlQuickDesign.Request>(context) {
    companion object {
        private const val TAG = "AccessControlQuickDesign"
    }

    sealed class Request {
        object ReloadApps : Request()
        object SelectAll : Request()
        object SelectNone : Request()
        object SelectInvert : Request()
        object Import : Request()
        object Export : Request()
        data class ChangeMode(val mode: AccessControlMode) : Request()
    }

    private val binding: DesignAccessControlQuickBinding
    private val adapter: AppAdapter
    private val menu: AccessControlQuickMenu by lazy {
        AccessControlQuickMenu(context, binding.menuView, uiStore, requests)
    }

    private var isInitialized = false

    val apps: List<AppInfo>
        get() = adapter.apps

    val mode: AccessControlMode
        get() = currentMode

    override val root: View
        get() = binding.root

    suspend fun patchApps(apps: List<AppInfo>) {
        try {
            adapter.swapDataSet(adapter::apps, apps, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to patch apps", e)
        }
    }

    suspend fun rebindAll() {
        try {
            withContext(Dispatchers.Main) {
                adapter.rebindAll()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebind all", e)
        }
    }

    suspend fun updateSelectedCount() {
        try {
            withContext(Dispatchers.Main) {
                binding.selectedCount = selected.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update selected count", e)
        }
    }

    init {
        binding = try {
            DesignAccessControlQuickBinding
                .inflate(context.layoutInflater, context.root, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate binding", e)
            throw e
        }

        adapter = try {
            AppAdapter(context, selected, ::notifySelectionChanged)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create adapter", e)
            throw e
        }

        try {
            binding.self = this

            binding.activityBarLayout.applyFrom(context)

            binding.mainList.recyclerList.applyLinearAdapter(context, adapter)

            when (currentMode) {
                AccessControlMode.AcceptAll -> binding.modeAcceptAll.isChecked = true
                AccessControlMode.AcceptSelected -> binding.modeAcceptSelected.isChecked = true
                AccessControlMode.DenySelected -> binding.modeDenySelected.isChecked = true
            }

            binding.selectedCount = selected.size
            binding.canSelectApps = canSelectApps()

            binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
                if (!isInitialized) {
                    return@setOnCheckedChangeListener
                }

                val newMode = when (checkedId) {
                    binding.modeAcceptAll.id -> AccessControlMode.AcceptAll
                    binding.modeAcceptSelected.id -> AccessControlMode.AcceptSelected
                    binding.modeDenySelected.id -> AccessControlMode.DenySelected
                    else -> return@setOnCheckedChangeListener
                }

                if (newMode != currentMode) {
                    currentMode = newMode
                    updateUiState()
                    requests.trySend(Request.ChangeMode(newMode))
                }
            }

            binding.menuView.setOnClickListener {
                if (canSelectApps()) {
                    try {
                        menu.show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to show menu", e)
                    }
                }
            }

            binding.searchView.setOnClickListener {
                if (canSelectApps()) {
                    launch {
                        try {
                            requestSearch()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to request search", e)
                        } finally {
                            withContext(NonCancellable) {
                                rebindAll()
                            }
                        }
                    }
                }
            }

            updateUiState()
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize design", e)
            throw e
        }
    }

    private fun canSelectApps(): Boolean {
        return currentMode != AccessControlMode.AcceptAll
    }

    private fun updateUiState() {
        try {
            binding.canSelectApps = canSelectApps()
            binding.selectedCount = selected.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update UI state", e)
        }
    }

    private fun notifySelectionChanged() {
        try {
            binding.selectedCount = selected.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify selection changed", e)
        }
    }

    private suspend fun requestSearch() {
        coroutineScope {
            val searchBinding = try {
                DialogSearchBinding
                    .inflate(context.layoutInflater, context.root, false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inflate search dialog", e)
                return@coroutineScope
            }

            val searchAdapter = try {
                AppAdapter(context, selected, ::notifySelectionChanged)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create search adapter", e)
                return@coroutineScope
            }

            val dialog = try {
                FullScreenDialog(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create dialog", e)
                return@coroutineScope
            }

            val filter = Channel<Unit>(Channel.CONFLATED)

            try {
                dialog.setContentView(searchBinding.root)

                searchBinding.surface = dialog.surface
                searchBinding.mainList.applyLinearAdapter(context, searchAdapter)
                searchBinding.keywordView.addTextChangedListener {
                    filter.trySend(Unit)
                }
                searchBinding.closeView.setOnClickListener {
                    dialog.dismiss()
                }

                dialog.setOnDismissListener {
                    cancel()
                }

                dialog.setOnShowListener {
                    searchBinding.keywordView.requestTextInput()
                }

                dialog.show()

                while (isActive) {
                    filter.receive()

                    val keyword = searchBinding.keywordView.text?.toString() ?: ""

                    val filteredApps: List<AppInfo> = if (keyword.isEmpty()) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.Default) {
                            try {
                                apps.filter {
                                    it.label.contains(keyword, ignoreCase = true) ||
                                            it.packageName.contains(keyword, ignoreCase = true)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to filter apps", e)
                                emptyList()
                            }
                        }
                    }

                    try {
                        searchAdapter.patchDataSet(searchAdapter::apps, filteredApps, false, AppInfo::packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to patch search adapter", e)
                    }

                    delay(200)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during search", e)
            }
        }
    }
}
