package com.ireum.ytdl.ui.adapter

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ireum.ytdl.R
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.util.Extensions.loadThumbnail

class VideoQueueAdapter(
    private val activity: Activity,
    private val onItemClick: (HistoryItem) -> Unit
) : ListAdapter<HistoryItem, VideoQueueAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var currentItemId: Long? = null
    private val indexById: MutableMap<Long, Int> = mutableMapOf()

    fun setCurrentItemId(id: Long?) {
        val previousId = currentItemId
        if (previousId == id) return
        currentItemId = id
        if (previousId != null) {
            val previousIndex = indexById[previousId]
            if (previousIndex != null) {
                notifyItemChanged(previousIndex, PAYLOAD_SELECTION)
            }
        }
        if (id != null) {
            val currentIndex = indexById[id]
            if (currentIndex != null) {
                notifyItemChanged(currentIndex, PAYLOAD_SELECTION)
            }
        }
    }

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.video_queue_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCurrentListChanged(previousList: List<HistoryItem>, currentList: List<HistoryItem>) {
        super.onCurrentListChanged(previousList, currentList)
        indexById.clear()
        currentList.forEachIndexed { index, item -> indexById[item.id] = index }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isCurrent = currentItemId != null && item.id == currentItemId
        holder.bind(item, isCurrent)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            val item = getItem(position)
            holder.updateSelection(currentItemId != null && item.id == currentItemId)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.video_thumb)
        private val title: TextView = itemView.findViewById(R.id.video_title)
        private val meta: TextView = itemView.findViewById(R.id.video_meta)

        fun bind(item: HistoryItem, isCurrent: Boolean) {
            title.text = item.title.ifEmpty { item.url }
            val author = sanitizeAuthorForDisplay(item.author)
            meta.text = listOf(author, item.duration).filter { it.isNotBlank() }.joinToString(" ")
            val hideThumb = sharedPreferences.getStringSet("hide_thumbnails", emptySet())!!.contains("downloads")
            val preview = if (item.customThumb.isNotBlank()) item.customThumb else item.thumb
            val resolved = when {
                preview.startsWith("content://") || preview.startsWith("file://") -> preview
                preview.startsWith("http://") || preview.startsWith("https://") -> preview
                preview.isNotBlank() -> java.io.File(preview).toURI().toString()
                else -> preview
            }
            thumb.loadThumbnail(hideThumb, resolved, reqWidth = 240, reqHeight = 136)
            updateSelection(isCurrent)
            itemView.setOnClickListener { onItemClick(item) }
        }

        fun updateSelection(isCurrent: Boolean) {
            if (isCurrent) {
                itemView.setBackgroundColor(0x33FFFFFF)
            } else {
                itemView.setBackgroundColor(0x00000000)
            }
        }

        private fun sanitizeAuthorForDisplay(author: String): String {
            var value = author.trim()
            while (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1).trim()
            }
            return value
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HistoryItem>() {
            override fun areItemsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}

