package com.ireum.ytdl.ui.adapter

import android.animation.ValueAnimator
import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ireum.ytdl.R
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.util.Extensions.dp
import com.ireum.ytdl.util.Extensions.loadBlurryThumbnail
import com.ireum.ytdl.util.Extensions.loadThumbnail
import com.ireum.ytdl.util.FileUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator


class ActiveDownloadAdapter(onItemClickListener: OnItemClickListener, activity: Activity) : ListAdapter<DownloadItem?, ActiveDownloadAdapter.ViewHolder>(AsyncDifferConfig.Builder(
    DIFF_CALLBACK
).build()) {
    private val onItemClickListener: OnItemClickListener
    private val activity: Activity
    private val sharedPreferences: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        this.onItemClickListener = onItemClickListener
        this.activity = activity
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
    }

    class ViewHolder(itemView: View, onItemClickListener: OnItemClickListener?) : RecyclerView.ViewHolder(itemView) {
        val cardView: MaterialCardView

        init {
            cardView = itemView.findViewById(R.id.active_download_card_view)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val cardView = LayoutInflater.from(parent.context)
                .inflate(R.layout.active_download_card, parent, false)
        return ViewHolder(cardView, onItemClickListener)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val card = holder.cardView
        card.tag = "${item!!.id}##card"
        val thumbnail = card.findViewById<ImageView>(R.id.image_view)

        // THUMBNAIL ----------------------------------
        val hideThumb = sharedPreferences.getStringSet("hide_thumbnails", emptySet())!!.contains("queue")
        mainHandler.post { thumbnail.loadBlurryThumbnail(activity, hideThumb, item.thumb) }

        // PROGRESS BAR ----------------------------------------------------
        val progressBar = card.findViewById<LinearProgressIndicator>(R.id.progress)
        progressBar.tag = "${item.id}##progress"

        // TITLE  ----------------------------------
        val itemTitle = card.findViewById<TextView>(R.id.title)
        var title = item.title.ifEmpty { item.playlistTitle.ifEmpty { item.url } }
        if (title.length > 100) {
            title = title.substring(0, 40) + "..."
        }
        itemTitle.text = title

        // Author ----------------------------------
        val author = card.findViewById<TextView>(R.id.author)
        var info = item.author
        if (item.duration.isNotEmpty() && item.duration != "-1") {
            if (item.author.isNotEmpty()) info += " ??"
            info += item.duration
        }
        author.text = info

        val type = card.findViewById<MaterialButton>(R.id.download_type)
        when(item.type){
            DownloadType.audio -> type.setIconResource(R.drawable.ic_music)
            DownloadType.video -> type.setIconResource(R.drawable.ic_video)
            DownloadType.command -> type.setIconResource(R.drawable.ic_terminal)
            else -> {}
        }

        val formatDetailsChip = card.findViewById<Chip>(R.id.format_note)

        val sideDetails = mutableListOf<String>()
        sideDetails.add(item.format.format_note.uppercase().replace("\n", " "))
        sideDetails.add(item.container.uppercase().ifEmpty { item.format.container.uppercase() })

        val fileSize = FileUtil.convertFileSize(item.format.filesize)
        if (fileSize != "?" && item.downloadSections.isBlank()) sideDetails.add(fileSize)
        formatDetailsChip.text = sideDetails.filter { it.isNotBlank() }.joinToString("  쨌  ")

        //OUTPUT
        val output = card.findViewById<TextView>(R.id.output)
        output.tag = "${item.id}##output"
        output.text = ""

        output.setOnClickListener {
            onItemClickListener.onOutputClick(item)
        }

        // CANCEL BUTTON ----------------------------------
        bindControls(card, item)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ACTIONS)) {
            val item = getItem(position) ?: return
            bindControls(holder.cardView, item)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindControls(card: MaterialCardView, item: DownloadItem) {
        val progressBar = card.findViewById<LinearProgressIndicator>(R.id.progress)
        val output = card.findViewById<TextView>(R.id.output)

        val cancelButton = card.findViewById<MaterialButton>(R.id.active_download_delete)
        if (cancelButton.hasOnClickListeners()) cancelButton.setOnClickListener(null)
        cancelButton.setOnClickListener { onItemClickListener.onCancelClick(item.id) }

        val resumeButton = card.findViewById<MaterialButton>(R.id.active_download_resume)
        resumeButton.isEnabled = true
        if (resumeButton.hasOnClickListeners()) resumeButton.setOnClickListener(null)
        val isPaused = item.status == DownloadRepository.Status.Paused.toString()
        if (isPaused) {
            resumeButton.setIconResource(R.drawable.exomedia_ic_play_arrow_white)
            resumeButton.setOnClickListener {
                resumeButton.isEnabled = false
                onItemClickListener.onResumeClick(item.id)
            }
        } else {
            resumeButton.setIconResource(R.drawable.exomedia_ic_pause_white)
            resumeButton.setOnClickListener {
                resumeButton.isEnabled = false
                onItemClickListener.onPauseClick(item.id)
            }
        }

        if (isPaused) {
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            cancelButton.isEnabled = true
            output.text = activity.getString(R.string.exo_download_paused)
        } else {
            progressBar.isIndeterminate = progressBar.progress <= 0
            cancelButton.isEnabled = true
        }
    }
    interface OnItemClickListener {
        fun onCancelClick(itemID: Long)
        fun onOutputClick(item: DownloadItem)
        fun onPauseClick(itemID: Long)
        fun onResumeClick(itemID: Long)
    }

    companion object {
        val PAYLOAD_ACTIONS = Any()
        private val DIFF_CALLBACK: DiffUtil.ItemCallback<DownloadItem> = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
                val ranged = arrayListOf(oldItem.id, newItem.id)
                return ranged[0] == ranged[1]
            }

            override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
                return oldItem.id == newItem.id && oldItem.title == newItem.title && oldItem.author == newItem.author && oldItem.thumb == newItem.thumb && oldItem.status == newItem.status
            }
        }
    }
}
