package com.github.kr328.clash.design

import android.content.Context
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
    sealed class Request {
        object ReloadApps : Request()
        object SelectAll : Request()
        object SelectNone : Request()
        object SelectInvert : Request()
        object Import : Request()
        object Export : Request()
        data class ChangeMode(val mode: AccessControlMode) : Request()
    }

    private val binding = DesignAccessControlQuickBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = AppAdapter(context, selected, ::notifySelectionChanged)

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
        adapter.swapDataSet(adapter::apps, apps, false)
    }

    suspend fun rebindAll() {
        withContext(Dispatchers.Main) {
            adapter.rebindAll()
        }
    }

    init {
        binding.self = this
        binding.surface = surface

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
                menu.show()
            }
        }

        binding.searchView.setOnClickListener {
            if (canSelectApps()) {
                launch {
                    try {
                        requestSearch()
                    } catch (e: Exception) {
                        e.printStackTrace()
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
    }

    private fun canSelectApps(): Boolean {
        return currentMode != AccessControlMode.AcceptAll
    }

    private fun updateUiState() {
        binding.canSelectApps = canSelectApps()
        binding.selectedCount = selected.size
    }

    suspend fun updateSelectedCount() {
        withContext(Dispatchers.Main) {
            binding.selectedCount = selected.size
        }
    }

    private fun notifySelectionChanged() {
        binding.selectedCount = selected.size
    }

    private suspend fun requestSearch() {
        coroutineScope {
            val searchBinding = DialogSearchBinding
                .inflate(context.layoutInflater, context.root, false)
            val searchAdapter = AppAdapter(context, selected, ::notifySelectionChanged)
            val dialog = FullScreenDialog(context)
            val filter = Channel<Unit>(Channel.CONFLATED)

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
                        apps.filter {
                            it.label.contains(keyword, ignoreCase = true) ||
                                    it.packageName.contains(keyword, ignoreCase = true)
                        }
                    }
                }

                searchAdapter.patchDataSet(searchAdapter::apps, filteredApps, false, AppInfo::packageName)

                delay(200)
            }
        }
    }
}
