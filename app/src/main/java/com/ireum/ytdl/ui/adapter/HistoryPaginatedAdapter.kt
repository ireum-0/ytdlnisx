package com.ireum.ytdl.ui.adapter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.paging.PagingDataAdapter
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.ireum.ytdl.R
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.KeywordInfo
import com.ireum.ytdl.database.models.UiModel
import com.ireum.ytdl.database.models.YoutuberInfo
import com.ireum.ytdl.util.Extensions.loadThumbnail
import com.ireum.ytdl.util.Extensions.popup
import com.ireum.ytdl.util.FileUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.squareup.picasso.Picasso
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class HistoryPaginatedAdapter(
    private val onItemClickListener: OnItemClickListener,
    private val activity: Activity
) : PagingDataAdapter<UiModel, RecyclerView.ViewHolder>(UiModelDiffCallback) {
    private val logTag = "HistoryThumb"
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        val PAYLOAD_SELECTION = Any()
    }

    val checkedItems: MutableSet<Long> = mutableSetOf()
    var inverted: Boolean = false
    private val selectedYoutubers: MutableSet<String> = mutableSetOf()
    private val selectedYoutuberGroups: MutableSet<Long> = mutableSetOf()
    private val selectedPlaylists: MutableSet<Long> = mutableSetOf()
    private val selectedPlaylistGroups: MutableSet<Long> = mutableSetOf()
    private val selectedKeywords: MutableSet<String> = mutableSetOf()
    private val selectedKeywordGroups: MutableSet<Long> = mutableSetOf()
    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private var disableGeneratedThumbnails: Boolean = false

    fun setDisableGeneratedThumbnails(disable: Boolean) {
        if (disableGeneratedThumbnails == disable) return
        disableGeneratedThumbnails = disable
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            R.layout.history_card, R.layout.history_card_multiple -> HistoryItemViewHolder(
                LayoutInflater.from(parent.context).inflate(viewType, parent, false)
            )
            R.layout.separator_view -> SeparatorViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.separator_view, parent, false)
            )
            R.layout.youtuber_card_item -> YoutuberInfoViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.youtuber_card_item, parent, false),
                onItemClickListener
            )
            R.layout.youtuber_group_card_item -> YoutuberGroupViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.youtuber_group_card_item, parent, false),
                onItemClickListener
            )
            R.layout.keyword_card_item -> KeywordInfoViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.keyword_card_item, parent, false),
                onItemClickListener
            )
            R.layout.keyword_group_card_item -> KeywordGroupViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.keyword_group_card_item, parent, false),
                onItemClickListener
            )
            else -> throw IllegalStateException("Unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val uiModel = getItem(position)
        uiModel?.let {
            when (uiModel) {
                is UiModel.HistoryItemModel -> (holder as HistoryItemViewHolder).bind(uiModel.historyItem)
                is UiModel.SeparatorModel -> (holder as SeparatorViewHolder).bind(uiModel.author)
                is UiModel.YoutuberInfoModel -> (holder as YoutuberInfoViewHolder).bind(uiModel.youtuberInfo)
                is UiModel.YoutuberGroupModel -> (holder as YoutuberGroupViewHolder).bind(uiModel.groupInfo)
                is UiModel.KeywordInfoModel -> (holder as KeywordInfoViewHolder).bind(uiModel.keywordInfo)
                is UiModel.KeywordGroupModel -> (holder as KeywordGroupViewHolder).bind(uiModel.groupInfo)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val uiModel = getItem(position)
        if (uiModel != null && payloads.contains(PAYLOAD_SELECTION)) {
            applySelectionState(holder, uiModel)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun applySelectionState(holder: RecyclerView.ViewHolder, uiModel: UiModel) {
        when (uiModel) {
            is UiModel.HistoryItemModel -> (holder as? HistoryItemViewHolder)?.bindSelection(uiModel.historyItem.id)
            is UiModel.YoutuberInfoModel -> (holder as? YoutuberInfoViewHolder)?.setSelectionState(selectedYoutubers.contains(uiModel.youtuberInfo.author))
            is UiModel.YoutuberGroupModel -> (holder as? YoutuberGroupViewHolder)?.setSelectionState(selectedYoutuberGroups.contains(uiModel.groupInfo.id))
            is UiModel.KeywordInfoModel -> (holder as? KeywordInfoViewHolder)?.setSelectionState(selectedKeywords.contains(uiModel.keywordInfo.keyword))
            is UiModel.KeywordGroupModel -> (holder as? KeywordGroupViewHolder)?.setSelectionState(selectedKeywordGroups.contains(uiModel.groupInfo.id))
            is UiModel.SeparatorModel -> Unit
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is UiModel.HistoryItemModel -> if (item.historyItem.downloadPath.size == 1) R.layout.history_card else R.layout.history_card_multiple
            is UiModel.SeparatorModel -> R.layout.separator_view
            is UiModel.YoutuberInfoModel -> R.layout.youtuber_card_item
            is UiModel.YoutuberGroupModel -> R.layout.youtuber_group_card_item
            is UiModel.KeywordInfoModel -> R.layout.keyword_card_item
            is UiModel.KeywordGroupModel -> R.layout.keyword_group_card_item
            null -> R.layout.history_card
        }
    }

    inner class HistoryItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView.findViewById(R.id.downloads_card_view)

        fun bind(item: HistoryItem) {
            itemView.tag = item.id.toString()
            cardView.tag = item.id.toString()
            cardView.popup()

            val thumbnail = cardView.findViewById<ImageView>(R.id.downloads_image_view)
            val hideThumb = sharedPreferences.getStringSet("hide_thumbnails", emptySet())!!.contains("downloads")
            loadHistoryThumbnail(thumbnail, hideThumb, item)

            val itemTitle = cardView.findViewById<TextView>(R.id.downloads_title)
            var title = item.title.ifEmpty { item.url }
            if (title.length > 100) {
                title = title.substring(0, 40) + "..."
            }
            itemTitle.text = title

            val author = cardView.findViewById<TextView>(R.id.downloads_info_bottom)
            author.text = item.author.replace("\"", "")

            val length = cardView.findViewById<TextView>(R.id.length)
            length.text = if (item.downloadPath.size == 1) item.duration else ""

            val datetime = cardView.findViewById<TextView>(R.id.downloads_info_time)
            datetime.text = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "ddMMMyyyy - HHmm"), Locale.getDefault()).format(item.time * 1000L)
            val progressBar = itemView.findViewById<LinearProgressIndicator>(R.id.downloads_progress)

            val btn = cardView.findViewById<FloatingActionButton>(R.id.downloads_download_button_type)
            val filesPresent = item.downloadPath.all { it.isNotBlank() && FileUtil.exists(it) }

            if (!filesPresent) {
                thumbnail.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                thumbnail.alpha = 0.7f
                btn.backgroundTintList = MaterialColors.getColorStateList(activity, R.attr.colorSurface, ContextCompat.getColorStateList(activity, android.R.color.transparent)!!)
            } else {
                thumbnail.alpha = 1f
                thumbnail.colorFilter = null
                btn.backgroundTintList = MaterialColors.getColorStateList(activity, R.attr.colorPrimaryContainer, ContextCompat.getColorStateList(activity, android.R.color.transparent)!!)
            }

            when (item.type) {
                DownloadType.audio -> btn.setImageResource(if (filesPresent) R.drawable.ic_music_downloaded else R.drawable.ic_music)
                DownloadType.video -> btn.setImageResource(if (filesPresent) R.drawable.ic_video_downloaded else R.drawable.ic_video)
                else -> btn.setImageResource(if (filesPresent) R.drawable.ic_terminal else R.drawable.baseline_code_off_24)
            }
            btn.isClickable = filesPresent

            val durationMs = parseDurationToMs(item.duration)
            if (filesPresent && item.playbackPositionMs >= 5_000L && durationMs > 0L) {
                val percent = ((item.playbackPositionMs * 100) / durationMs).toInt().coerceIn(0, 100)
                progressBar.visibility = View.VISIBLE
                progressBar.progress = percent
            } else {
                progressBar.visibility = View.GONE
            }

            bindSelection(item.id)

            cardView.setOnLongClickListener {
                checkCard(cardView, item.id, bindingAdapterPosition)
                true
            }
            cardView.setOnClickListener {
                if (checkedItems.isNotEmpty() || inverted) {
                    checkCard(cardView, item.id, bindingAdapterPosition)
                } else {
                    onItemClickListener.onCardClick(item.id, filesPresent)
                }
            }
            btn.setOnClickListener {
                onItemClickListener.onButtonClick(item.id, filesPresent)
            }
        }

        fun bindSelection(itemId: Long) {
            if ((checkedItems.contains(itemId) && !inverted) || (!checkedItems.contains(itemId) && inverted)) {
                cardView.isChecked = true
                cardView.strokeWidth = 5
            } else {
                cardView.isChecked = false
                cardView.strokeWidth = 0
            }
        }
    }

    inner class SeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val authorTextView: TextView = itemView.findViewById(R.id.separator_author)

        fun bind(author: String) {
            authorTextView.text = author
        }
    }

    fun clearCheckedItems() {
        inverted = false
        checkedItems.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun checkAll() {
        checkedItems.clear()
        inverted = true
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun checkMultipleItems(list: List<Long>) {
        checkedItems.clear()
        inverted = false
        checkedItems.addAll(list)
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun invertSelected() {
        inverted = !inverted
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun getHistoryItemIdAt(position: Int): Long? {
        return when (val item = peek(position)) {
            is UiModel.HistoryItemModel -> item.historyItem.id
            else -> null
        }
    }

    fun getSelectedObjectsCount(totalSize: Int): Int {
        return if (inverted) {
            totalSize - checkedItems.size
        } else {
            checkedItems.size
        }
    }

    private fun checkCard(card: MaterialCardView, itemID: Long, position: Int) {
        if (card.isChecked) {
            card.strokeWidth = 0
            if (inverted) checkedItems.add(itemID) else checkedItems.remove(itemID)
        } else {
            card.strokeWidth = 5
            if (inverted) checkedItems.remove(itemID) else checkedItems.add(itemID)
        }
        card.isChecked = !card.isChecked
        onItemClickListener.onCardSelect(card.isChecked, position)
    }

    interface OnItemClickListener {
        fun onButtonClick(itemID: Long, filePresent: Boolean)
        fun onCardClick(itemID: Long, filePresent: Boolean)
        fun onCardSelect(isChecked: Boolean, position: Int)
        fun onYoutuberSelected(youtuber: String)
        fun onYoutuberLongClick(youtuberInfo: YoutuberInfo)
        fun onYoutuberSelectionChanged(selectedCount: Int)
        fun onYoutuberGroupSelected(groupId: Long)
        fun onYoutuberGroupSelectionChanged(selectedCount: Int)
        fun onPlaylistSelected(playlistId: Long)
        fun onPlaylistSelectionChanged(selectedCount: Int)
        fun onPlaylistGroupSelected(groupId: Long)
        fun onPlaylistGroupSelectionChanged(selectedCount: Int)
        fun onPlaylistLongClick(playlistId: Long)
        fun onKeywordSelected(keyword: String)
        fun onKeywordLongClick(keywordInfo: KeywordInfo)
        fun onKeywordSelectionChanged(selectedCount: Int)
        fun onKeywordGroupSelected(groupId: Long)
        fun onKeywordGroupSelectionChanged(selectedCount: Int)
    }

    inner class YoutuberInfoViewHolder(itemView: View, private val onItemClickListener: OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.youtuber_thumbnail)
        private val name: TextView = itemView.findViewById(R.id.youtuber_name)
        private val videoCount: TextView = itemView.findViewById(R.id.video_count)
        private val card: MaterialCardView? = itemView as? MaterialCardView

        fun bind(youtuberInfo: YoutuberInfo) {
            name.text = youtuberInfo.author
            videoCount.text = "${youtuberInfo.videoCount} videos"
            val hideThumb = sharedPreferences.getStringSet("hide_thumbnails", emptySet())!!.contains("downloads")
            youtuberInfo.thumbnail?.let { thumbnailUrl ->
                thumbnail.visibility = View.VISIBLE
                mainHandler.post {
                    val resolved = when {
                        thumbnailUrl.startsWith("content://") || thumbnailUrl.startsWith("file://") -> thumbnailUrl
                        thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://") -> thumbnailUrl
                        thumbnailUrl.isNotBlank() -> File(thumbnailUrl).toURI().toString()
                        else -> thumbnailUrl
                    }
                    thumbnail.loadThumbnail(hideThumb, resolved)
                }
            } ?: run {
                thumbnail.visibility = View.GONE
            }

            setSelectionState(selectedYoutubers.contains(youtuberInfo.author))

            itemView.setOnClickListener {
                if (selectedYoutubers.isNotEmpty() || selectedYoutuberGroups.isNotEmpty()) {
                    toggleYoutuberSelection(youtuberInfo.author, card)
                } else {
                    onItemClickListener.onYoutuberSelected(youtuberInfo.author)
                }
            }
            itemView.setOnLongClickListener {
                toggleYoutuberSelection(youtuberInfo.author, card)
                true
            }
        }

        fun setSelectionState(isSelected: Boolean) {
            card?.isChecked = isSelected
            card?.strokeWidth = if (isSelected) 5 else 0
        }
    }

    inner class YoutuberGroupViewHolder(itemView: View, private val onItemClickListener: OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.youtuber_group_thumbnail)
        private val name: TextView = itemView.findViewById(R.id.youtuber_group_name)
        private val count: TextView = itemView.findViewById(R.id.youtuber_group_count)
        private val card: MaterialCardView? = itemView as? MaterialCardView

        fun bind(groupInfo: com.ireum.ytdl.database.models.YoutuberGroupInfo) {
            name.text = groupInfo.name
            count.text = itemView.context.getString(
                R.string.youtuber_group_count_format,
                groupInfo.memberCount,
                groupInfo.videoCount
            )
            val hideThumb = sharedPreferences.getStringSet("hide_thumbnails", emptySet())!!.contains("downloads")
            groupInfo.thumbnail?.let { thumbnailUrl ->
                thumbnail.visibility = View.VISIBLE
                mainHandler.post {
                    val resolved = when {
                        thumbnailUrl.startsWith("content://") || thumbnailUrl.startsWith("file://") -> thumbnailUrl
                        thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://") -> thumbnailUrl
                        thumbnailUrl.isNotBlank() -> File(thumbnailUrl).toURI().toString()
                        else -> thumbnailUrl
                    }
                    thumbnail.loadThumbnail(hideThumb, resolved)
                }
            } ?: run {
                thumbnail.visibility = View.GONE
            }

            setSelectionState(selectedYoutuberGroups.contains(groupInfo.id))

            itemView.setOnClickListener {
                if (selectedYoutubers.isNotEmpty() || selectedYoutuberGroups.isNotEmpty()) {
                    toggleYoutuberGroupSelection(groupInfo.id, card)
                } else {
                    onItemClickListener.onYoutuberGroupSelected(groupInfo.id)
                }
            }
            itemView.setOnLongClickListener {
                toggleYoutuberGroupSelection(groupInfo.id, card)
                true
            }
        }

        fun setSelectionState(isSelected: Boolean) {
            card?.isChecked = isSelected
            card?.strokeWidth = if (isSelected) 5 else 0
        }
    }

    inner class KeywordInfoViewHolder(itemView: View, private val onItemClickListener: OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.youtuber_thumbnail)
        private val name: TextView = itemView.findViewById(R.id.youtuber_name)
        private val videoCount: TextView = itemView.findViewById(R.id.video_count)
        private val card: MaterialCardView? = itemView as? MaterialCardView

        fun bind(keywordInfo: KeywordInfo) {
            name.text = keywordInfo.keyword
            val details = mutableListOf<String>()
            details.add("${keywordInfo.videoCount} videos")
            keywordInfo.uniqueCreator?.takeIf { it.isNotBlank() }?.let {
                details.add("creator: $it")
            }
            videoCount.text = details.joinToString("\n")
            val hideThumb = sharedPreferences.getStringSet("hide_thumbnails", emptySet())!!.contains("downloads")
            keywordInfo.thumbnail?.takeIf { it.isNotBlank() }?.let { thumbnailUrl ->
                thumbnail.visibility = View.VISIBLE
                mainHandler.post {
                    val resolved = when {
                        thumbnailUrl.startsWith("content://") || thumbnailUrl.startsWith("file://") -> thumbnailUrl
                        thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://") -> thumbnailUrl
                        else -> File(thumbnailUrl).toURI().toString()
                    }
                    thumbnail.loadThumbnail(hideThumb, resolved)
                }
            } ?: run {
                thumbnail.visibility = View.GONE
            }
            setSelectionState(selectedKeywords.contains(keywordInfo.keyword))

            itemView.setOnClickListener {
                if (selectedKeywords.isNotEmpty() || selectedKeywordGroups.isNotEmpty()) {
                    toggleKeywordSelection(keywordInfo.keyword, card)
                } else {
                    onItemClickListener.onKeywordSelected(keywordInfo.keyword)
                }
            }
            itemView.setOnLongClickListener {
                toggleKeywordSelection(keywordInfo.keyword, card)
                true
            }
        }

        fun setSelectionState(isSelected: Boolean) {
            card?.isChecked = isSelected
            card?.strokeWidth = if (isSelected) 5 else 0
        }
    }

    inner class KeywordGroupViewHolder(itemView: View, private val onItemClickListener: OnItemClickListener) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.youtuber_group_thumbnail)
        private val name: TextView = itemView.findViewById(R.id.youtuber_group_name)
        private val count: TextView = itemView.findViewById(R.id.youtuber_group_count)
        private val card: MaterialCardView? = itemView as? MaterialCardView

        fun bind(groupInfo: com.ireum.ytdl.database.models.KeywordGroupInfo) {
            name.text = groupInfo.name
            count.text = itemView.context.getString(
                R.string.keyword_group_count_format,
                groupInfo.memberCount,
                groupInfo.videoCount
            )
            val hideThumb = sharedPreferences.getStringSet("hide_thumbnails", emptySet())!!.contains("downloads")
            groupInfo.thumbnail?.takeIf { it.isNotBlank() }?.let { thumbnailUrl ->
                thumbnail.visibility = View.VISIBLE
                mainHandler.post {
                    val resolved = when {
                        thumbnailUrl.startsWith("content://") || thumbnailUrl.startsWith("file://") -> thumbnailUrl
                        thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://") -> thumbnailUrl
                        else -> File(thumbnailUrl).toURI().toString()
                    }
                    thumbnail.loadThumbnail(hideThumb, resolved)
                }
            } ?: run {
                thumbnail.visibility = View.GONE
            }

            setSelectionState(selectedKeywordGroups.contains(groupInfo.id))

            itemView.setOnClickListener {
                if (selectedKeywords.isNotEmpty() || selectedKeywordGroups.isNotEmpty()) {
                    toggleKeywordGroupSelection(groupInfo.id, card)
                } else {
                    onItemClickListener.onKeywordGroupSelected(groupInfo.id)
                }
            }
            itemView.setOnLongClickListener {
                toggleKeywordGroupSelection(groupInfo.id, card)
                true
            }
        }

        fun setSelectionState(isSelected: Boolean) {
            card?.isChecked = isSelected
            card?.strokeWidth = if (isSelected) 5 else 0
        }
    }

    object UiModelDiffCallback : DiffUtil.ItemCallback<UiModel>() {
        override fun areItemsTheSame(oldItem: UiModel, newItem: UiModel): Boolean {
            return (oldItem is UiModel.HistoryItemModel && newItem is UiModel.HistoryItemModel && oldItem.historyItem.id == newItem.historyItem.id) ||
                    (oldItem is UiModel.SeparatorModel && newItem is UiModel.SeparatorModel && oldItem.author == newItem.author) ||
                    (oldItem is UiModel.YoutuberInfoModel && newItem is UiModel.YoutuberInfoModel && oldItem.youtuberInfo.author == newItem.youtuberInfo.author) ||
                    (oldItem is UiModel.YoutuberGroupModel && newItem is UiModel.YoutuberGroupModel && oldItem.groupInfo.id == newItem.groupInfo.id) ||
                    (oldItem is UiModel.KeywordInfoModel && newItem is UiModel.KeywordInfoModel && oldItem.keywordInfo.keyword == newItem.keywordInfo.keyword) ||
                    (oldItem is UiModel.KeywordGroupModel && newItem is UiModel.KeywordGroupModel && oldItem.groupInfo.id == newItem.groupInfo.id)
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: UiModel, newItem: UiModel): Boolean {
            return oldItem == newItem
        }
    }

    fun clearYoutuberSelection() {
        selectedYoutubers.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun getSelectedYoutubers(): List<String> = selectedYoutubers.toList()

    fun clearYoutuberGroupSelection() {
        selectedYoutuberGroups.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun getSelectedYoutuberGroups(): List<Long> = selectedYoutuberGroups.toList()

    fun clearPlaylistSelection() {
        selectedPlaylists.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun getSelectedPlaylists(): List<Long> = selectedPlaylists.toList()

    fun clearPlaylistGroupSelection() {
        selectedPlaylistGroups.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun getSelectedPlaylistGroups(): List<Long> = selectedPlaylistGroups.toList()

    fun clearKeywordSelection() {
        selectedKeywords.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun getSelectedKeywords(): List<String> = selectedKeywords.toList()

    fun clearKeywordGroupSelection() {
        selectedKeywordGroups.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun getSelectedKeywordGroups(): List<Long> = selectedKeywordGroups.toList()

    private fun toggleYoutuberSelection(author: String, card: MaterialCardView?) {
        if (selectedYoutubers.contains(author)) {
            selectedYoutubers.remove(author)
        } else {
            selectedYoutubers.add(author)
        }
        card?.isChecked = selectedYoutubers.contains(author)
        card?.strokeWidth = if (card?.isChecked == true) 5 else 0
        onItemClickListener.onYoutuberSelectionChanged(selectedYoutubers.size)
    }

    private fun toggleYoutuberGroupSelection(groupId: Long, card: MaterialCardView?) {
        if (selectedYoutuberGroups.contains(groupId)) {
            selectedYoutuberGroups.remove(groupId)
        } else {
            selectedYoutuberGroups.add(groupId)
        }
        card?.isChecked = selectedYoutuberGroups.contains(groupId)
        card?.strokeWidth = if (card?.isChecked == true) 5 else 0
        onItemClickListener.onYoutuberGroupSelectionChanged(selectedYoutuberGroups.size)
    }

    private fun toggleKeywordSelection(keyword: String, card: MaterialCardView?) {
        if (selectedKeywords.contains(keyword)) {
            selectedKeywords.remove(keyword)
        } else {
            selectedKeywords.add(keyword)
        }
        card?.isChecked = selectedKeywords.contains(keyword)
        card?.strokeWidth = if (card?.isChecked == true) 5 else 0
        onItemClickListener.onKeywordSelectionChanged(selectedKeywords.size)
    }

    private fun toggleKeywordGroupSelection(groupId: Long, card: MaterialCardView?) {
        if (selectedKeywordGroups.contains(groupId)) {
            selectedKeywordGroups.remove(groupId)
        } else {
            selectedKeywordGroups.add(groupId)
        }
        card?.isChecked = selectedKeywordGroups.contains(groupId)
        card?.strokeWidth = if (card?.isChecked == true) 5 else 0
        onItemClickListener.onKeywordGroupSelectionChanged(selectedKeywordGroups.size)
    }

    private fun parseDurationToMs(duration: String): Long {
        if (duration.isBlank()) return 0L
        val parts = duration.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return 0L
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            1 -> parts[0] * 1000L
            else -> 0L
        }
    }

    private fun loadHistoryThumbnail(thumbnail: ImageView, hideThumb: Boolean, item: HistoryItem) {
        val path = item.downloadPath.firstOrNull().orEmpty()
        val requestKey = "history:${item.id}:${item.customThumb}:${item.thumb}:$path:$hideThumb:$disableGeneratedThumbnails"
        thumbnail.setTag(R.id.downloads_image_view, requestKey)

        if (hideThumb) {
            mainHandler.post {
                if (thumbnail.getTag(R.id.downloads_image_view) == requestKey) {
                    thumbnail.loadThumbnail(true, "")
                }
            }
            return
        }
        val customThumb = item.customThumb
        if (customThumb.isNotBlank() && FileUtil.exists(customThumb)) {
            val resolved = if (customThumb.startsWith("content://") || customThumb.startsWith("file://")) {
                customThumb
            } else {
                File(customThumb).toURI().toString()
            }
            mainHandler.post {
                if (thumbnail.getTag(R.id.downloads_image_view) == requestKey) {
                    thumbnail.loadThumbnail(false, resolved)
                }
            }
            return
        }
        if (item.thumb.isNotBlank()) {
            mainHandler.post {
                if (thumbnail.getTag(R.id.downloads_image_view) == requestKey) {
                    thumbnail.loadThumbnail(false, item.thumb)
                }
            }
            return
        }
        if (disableGeneratedThumbnails) {
            mainHandler.post {
                if (thumbnail.getTag(R.id.downloads_image_view) == requestKey) {
                    thumbnail.loadThumbnail(false, "")
                }
            }
            return
        }
        if (path.isBlank()) {
            Log.d(logTag, "thumb missing: empty path id=${item.id} title=${item.title}")
            mainHandler.post {
                if (thumbnail.getTag(R.id.downloads_image_view) == requestKey) {
                    thumbnail.loadThumbnail(false, "")
                }
            }
            return
        }
        mainHandler.post {
            if (thumbnail.getTag(R.id.downloads_image_view) == requestKey) {
                thumbnail.loadThumbnail(false, "")
            }
        }
        thread(start = true) {
            var retriever: MediaMetadataRetriever? = null
            val bitmap = runCatching {
                retriever = MediaMetadataRetriever()
                if (path.startsWith("content://")) {
                    retriever?.setDataSource(activity, Uri.parse(path))
                } else {
                    retriever?.setDataSource(path)
                }
                retriever?.getFrameAtTime(0)
            }.getOrNull()
            if (bitmap == null) {
                Log.d(logTag, "thumb extract failed id=${item.id} path=$path title=${item.title}")
            }
            runCatching { retriever?.release() }
            mainHandler.post {
                if (thumbnail.getTag(R.id.downloads_image_view) != requestKey) return@post
                if (bitmap != null) {
                    thumbnail.setImageBitmap(bitmap)
                } else {
                    thumbnail.loadThumbnail(false, "")
                }
            }
        }
    }
}

