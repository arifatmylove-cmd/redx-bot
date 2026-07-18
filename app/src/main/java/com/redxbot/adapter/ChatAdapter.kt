package com.redxbot.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.redxbot.R
import com.redxbot.model.Message
import io.noties.markwon.Markwon

class ChatAdapter(private val markwon: Markwon) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<Message>()

    companion object {
        const val VIEW_TYPE_USER = 0
        const val VIEW_TYPE_AI = 1
    }

    fun submitMessages(newMessages: List<Message>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(content: String) {
        if (messages.isNotEmpty()) {
            val last = messages.last()
            messages[messages.size - 1] = last.copy(content = content, isLoading = false)
            notifyItemChanged(messages.size - 1)
        }
    }

    fun removeLastIfLoading() {
        if (messages.isNotEmpty() && messages.last().isLoading) {
            messages.removeAt(messages.size - 1)
            notifyItemRemoved(messages.size)
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].role == Message.Role.USER) VIEW_TYPE_USER else VIEW_TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_ai, parent, false)
            AiViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is UserViewHolder) holder.bind(message)
        else if (holder is AiViewHolder) holder.bind(message, markwon)
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.tvMessage)
        private val imageView: ImageView = view.findViewById(R.id.ivImage)

        fun bind(message: Message) {
            textView.text = message.content
            textView.visibility = if (message.content.isEmpty()) View.GONE else View.VISIBLE

            if (message.imageUri != null) {
                imageView.visibility = View.VISIBLE
                Glide.with(imageView.context)
                    .load(Uri.parse(message.imageUri))
                    .centerCrop()
                    .into(imageView)
            } else {
                imageView.visibility = View.GONE
            }
        }
    }

    class AiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.tvMessage)
        private val progressBar: ProgressBar = view.findViewById(R.id.progressBar)

        fun bind(message: Message, markwon: Markwon) {
            if (message.isLoading) {
                textView.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
            } else {
                progressBar.visibility = View.GONE
                textView.visibility = View.VISIBLE
                markwon.setMarkdown(textView, message.content)
            }
        }
    }
}
