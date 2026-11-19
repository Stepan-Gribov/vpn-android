package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.databinding.ItemRecyclerLogcatBinding
import com.v2ray.ang.viewmodel.LogcatViewModel

class LogcatRecyclerAdapter(
    private val onLongClick: ((String) -> Boolean)? = null
) : RecyclerView.Adapter<LogcatRecyclerAdapter.MainViewHolder>() {

    private var items: List<LogcatViewModel.LogLine> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<LogcatViewModel.LogLine>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val line = items[position]

        val meta = buildList {
            add(levelLabel(line.priority))
            if (line.tag.isNotEmpty()) add(line.tag)
            if (line.time.isNotEmpty()) add(line.time)
        }.joinToString(" · ")

        holder.binding.logTag.text = meta
        holder.binding.logContent.text = line.message

        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(line.raw) ?: false
        }
    }

    private fun levelLabel(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "F"
        else -> "?"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerLogcatBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val binding: ItemRecyclerLogcatBinding) : RecyclerView.ViewHolder(binding.root)
}
