package com.redxbot.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.redxbot.databinding.ItemConversationBinding
import com.redxbot.storage.ConversationStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onOpen:   (String) -> Unit,
    private val onDelete: (String) -> Unit
) : ListAdapter<ConversationStorage.ConvMeta, ConversationAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ConversationStorage.ConvMeta>() {
            override fun areItemsTheSame(a: ConversationStorage.ConvMeta, b: ConversationStorage.ConvMeta) = a.id == b.id
            override fun areContentsTheSame(a: ConversationStorage.ConvMeta, b: ConversationStorage.ConvMeta) = a == b
        }
    }

    inner class VH(private val b: ItemConversationBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(meta: ConversationStorage.ConvMeta) {
            b.tvTitle.text = meta.title.ifBlank { "New Chat" }
            b.tvDate.text  = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(meta.updatedAt))
            b.root.setOnClickListener { onOpen(meta.id) }
            b.btnDelete.setOnClickListener { onDelete(meta.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
