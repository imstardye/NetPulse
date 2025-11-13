package com.github.kr328.clash.design.adapter

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterAppBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class AppAdapter(
    private val context: Context,
    private val selected: MutableSet<String>,
    private val onSelectionChanged: () -> Unit = {},
) : RecyclerView.Adapter<AppAdapter.Holder>() {
    companion object {
        private const val TAG = "AppAdapter"
    }

    class Holder(val binding: AdapterAppBinding) : RecyclerView.ViewHolder(binding.root)

    var apps: List<AppInfo> = emptyList()

    fun rebindAll() {
        try {
            notifyItemRangeChanged(0, itemCount)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebind all items", e)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return try {
            Holder(
                AdapterAppBinding
                    .inflate(context.layoutInflater, context.root, false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create view holder", e)
            throw e
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        try {
            val current = apps.getOrNull(position) ?: return

            holder.binding.app = current
            holder.binding.selected = current.packageName in selected
            holder.binding.root.setOnClickListener {
                try {
                    if (holder.binding.selected) {
                        selected.remove(current.packageName)
                        holder.binding.selected = false
                    } else {
                        selected.add(current.packageName)
                        holder.binding.selected = true
                    }
                    onSelectionChanged()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle click for ${current.packageName}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind view holder at position $position", e)
        }
    }

    override fun getItemCount(): Int {
        return apps.size
    }
}
