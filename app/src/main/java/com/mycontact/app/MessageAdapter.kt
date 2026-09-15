package com.mycontact.app

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter(
    private val items: MutableList<Message>,
    private val onOpenFile: (Message) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SYSTEM = 0
        private const val TYPE_BUBBLE = 1
        private val FONT_SCALES = floatArrayOf(0.88f, 1f, 1.18f, 1.4f)
        private const val BASE_TEXT_SP = 14f
    }

    private fun currentFontScale(): Float {
        val idx = Session.prefs.fontScaleIndex.coerceIn(0, FONT_SCALES.size - 1)
        return FONT_SCALES[idx]
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].type == "system") TYPE_SYSTEM else TYPE_BUBBLE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SYSTEM) {
            SystemHolder(inflater.inflate(R.layout.item_message_system, parent, false))
        } else {
            BubbleHolder(inflater.inflate(R.layout.item_message_bubble, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val m = items[position]
        if (holder is SystemHolder) {
            holder.text.text = m.text
            return
        }
        holder as BubbleHolder
        holder.tvText.textSize = BASE_TEXT_SP * currentFontScale()
        val isMe = m.dir == "out"
        holder.row.gravity = if (isMe) Gravity.START else Gravity.END
        holder.bubble.setBackgroundResource(if (isMe) R.drawable.bubble_me else R.drawable.bubble_them)

        if (!m.senderName.isNullOrBlank()) {
            holder.senderName.visibility = View.VISIBLE
            holder.senderName.text = m.senderName
        } else {
            holder.senderName.visibility = View.GONE
        }

        if (m.type == "file") {
            holder.tvText.visibility = View.GONE
            holder.fileRow.visibility = View.VISIBLE
            holder.fileName.text = m.fileName ?: ""
            holder.fileSize.text = formatBytes(m.fileSize ?: 0L)
            when {
                m.status == "failed" -> {
                    holder.fileStatus.visibility = View.VISIBLE
                    holder.fileStatus.text = m.failReason ?: holder.itemView.context.getString(R.string.transfer_failed)
                    holder.openFile.visibility = View.GONE
                }
                m.filePath != null -> {
                    holder.fileStatus.visibility = View.GONE
                    holder.openFile.visibility = View.VISIBLE
                    holder.openFile.setOnClickListener { onOpenFile(m) }
                }
                m.progress != null && m.progress < 100 -> {
                    holder.fileStatus.visibility = View.VISIBLE
                    holder.fileStatus.text = holder.itemView.context.getString(R.string.transferring) + " ${m.progress}%"
                    holder.openFile.visibility = View.GONE
                }
                else -> {
                    holder.fileStatus.visibility = View.VISIBLE
                    holder.fileStatus.text = holder.itemView.context.getString(R.string.file_no_longer_available)
                    holder.openFile.visibility = View.GONE
                }
            }
        } else {
            holder.fileRow.visibility = View.GONE
            holder.fileStatus.visibility = View.GONE
            holder.openFile.visibility = View.GONE
            holder.tvText.visibility = View.VISIBLE
            holder.tvText.text = m.text
        }
        holder.meta.text = m.time ?: ""
    }

    override fun getItemCount(): Int = items.size

    fun addMessage(m: Message) {
        items.add(m)
        notifyItemInserted(items.size - 1)
    }

    fun updateTransfer(transferId: String, transform: (Message) -> Message) {
        val idx = items.indexOfFirst { it.transferId == transferId }
        if (idx >= 0) {
            items[idx] = transform(items[idx])
            notifyItemChanged(idx)
        }
    }

    fun setAll(newItems: List<Message>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun formatBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
        else -> String.format("%.1f MB", n / 1024.0 / 1024.0)
    }

    class SystemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    class BubbleHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row: LinearLayout = view.findViewById(R.id.row)
        val bubble: LinearLayout = view.findViewById(R.id.bubble)
        val senderName: TextView = view.findViewById(R.id.tvSenderName)
        val tvText: TextView = view.findViewById(R.id.tvText)
        val fileRow: LinearLayout = view.findViewById(R.id.fileRow)
        val fileName: TextView = view.findViewById(R.id.tvFileName)
        val fileSize: TextView = view.findViewById(R.id.tvFileSize)
        val fileStatus: TextView = view.findViewById(R.id.tvFileStatus)
        val openFile: TextView = view.findViewById(R.id.tvOpenFile)
        val meta: TextView = view.findViewById(R.id.tvMeta)
    }
}
