package com.ireum.ytdl.ui.downloads

import android.animation.AnimatorSet
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ireum.ytdl.R
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.PendingUndoResolutionIntent
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.database.viewmodel.YTDLPViewModel
import com.ireum.ytdl.ui.adapter.QueuedDownloadAdapter
import com.ireum.ytdl.ui.UndoPresentationLifetime
import com.ireum.ytdl.ui.setManualUndoAction
import com.ireum.ytdl.util.Extensions.enableFastScroll
import com.ireum.ytdl.util.Extensions.forceFastScrollMode
import com.ireum.ytdl.util.Extensions.toListString
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.UiUtil
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class QueuedDownloadsFragment : Fragment(), QueuedDownloadAdapter.OnItemClickListener {
    private var fragmentView: View? = null
    private lateinit var downloadViewModel : DownloadViewModel
    private lateinit var ytdlpViewModel : YTDLPViewModel
    private lateinit var queuedRecyclerView : RecyclerView
    private lateinit var adapter : QueuedDownloadAdapter
    private lateinit var noResults : RelativeLayout
    private lateinit var notificationUtil: NotificationUtil
    private lateinit var fileSize: TextView
    private lateinit var dragHandleToggle: TextView
    private lateinit var sharedPreferences: SharedPreferences
    private var totalSize: Int = 0
    private var actionMode : ActionMode? = null
    private var undoPresentationOwner: DownloadRepository.UndoPresentationOwner? = null
    private val undoPresentationLifetime = UndoPresentationLifetime()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        fragmentView = inflater.inflate(R.layout.fragment_inqueue, container, false)
        notificationUtil = NotificationUtil(requireContext())
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        undoPresentationOwner = downloadViewModel.beginUndoPresentationOwner()
        ytdlpViewModel = ViewModelProvider(this)[YTDLPViewModel::class.java]
        return fragmentView
    }

    @SuppressLint("SetTextI18n", "RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        undoPresentationLifetime.attach(viewLifecycleOwner.lifecycleScope)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        fileSize = view.findViewById(R.id.filesize)
        dragHandleToggle = view.findViewById(R.id.drag)
        val itemTouchHelper = ItemTouchHelper(queuedDragDropHelper)
        adapter = QueuedDownloadAdapter(this, requireActivity(), itemTouchHelper)

        noResults = view.findViewById(R.id.no_results)
        queuedRecyclerView = view.findViewById(R.id.download_recyclerview)
        queuedRecyclerView.forceFastScrollMode()
        queuedRecyclerView.adapter = adapter
        queuedRecyclerView.enableFastScroll()
        itemTouchHelper.attachToRecyclerView(queuedRecyclerView)

        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (preferences.getStringSet("swipe_gesture", requireContext().resources.getStringArray(R.array.swipe_gestures_values).toSet())!!.toList().contains("queued")){
            val itemTouchHelper = ItemTouchHelper(simpleCallback)
            itemTouchHelper.attachToRecyclerView(queuedRecyclerView)
        }
        queuedRecyclerView.layoutManager = GridLayoutManager(context, resources.getInteger(R.integer.grid_size))

        viewLifecycleOwner.lifecycleScope.launch {
            downloadViewModel.queuedDownloads.collectLatest {
                adapter.submitData(it)
            }
        }

        adapter.addLoadStateListener { loadState ->
            viewLifecycleOwner.lifecycleScope.launch {
                if (loadState.append.endOfPaginationReached )
                {
                    if ( adapter.itemCount < 1){
                        fileSize.visibility = View.GONE
                    }else{
                        val size = withContext(Dispatchers.IO){
                            FileUtil.convertFileSize(downloadViewModel.getQueuedCollectedFileSize())
                        }
                        if (size == "?")  fileSize.visibility = View.GONE
                        else {
                            fileSize.visibility = View.VISIBLE
                            fileSize.text = "${getString(R.string.file_size)}: ~ $size"
                        }
                    }
                }
            }
        }

        downloadViewModel.getTotalSize(
            listOf(
                DownloadRepository.Status.Queued,
                DownloadRepository.Status.WaitingForMembership
            )
        ).observe(viewLifecycleOwner){
            totalSize = it
            noResults.isVisible = it == 0
            dragHandleToggle.isVisible = it > 1
        }

        dragHandleToggle.setOnClickListener {
            adapter.toggleShowDragHandle()
        }
    }

    private fun removeItem(id: Long){
        val owner = undoPresentationOwner ?: return
        val anchor = queuedRecyclerView
        val presentationScope = undoPresentationLifetime.current() ?: return
        val context = requireContext()
        presentationScope.launch {
            val item = withContext(Dispatchers.IO){
                downloadViewModel.getItemByID(id)
            }
            if (!downloadViewModel.isUndoPresentationOwnerActive(owner)) return@launch
            val deleteDialog = MaterialAlertDialogBuilder(context)
            deleteDialog.setTitle(getString(R.string.you_are_going_to_delete) + " \"" + item.title.ifEmpty { item.url } + "\"!")
            deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
            deleteDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                val wasWaitingForMembership =
                    item.status == DownloadRepository.Status.WaitingForMembership.toString()
                presentationScope.launch {
                        val pendingToken = withContext(Dispatchers.IO) {
                            downloadViewModel.beginUndoableCancellation(item.id, owner)
                        }
                        try {
                            if (!downloadViewModel.isUndoPresentationOwnerActive(owner)) {
                                pendingToken?.let(downloadViewModel::abandonUndoCapabilityAfterConsumerFailure)
                                return@launch
                            }
                            var resolutionAccepted = false
                            var selectedIntent: PendingUndoResolutionIntent? = null
                            lateinit var snackbar: Snackbar
                            snackbar = Snackbar.make(
                                anchor,
                                getString(R.string.cancelled) + ": " + item.title.ifEmpty { item.url },
                                Snackbar.LENGTH_INDEFINITE,
                            ).setManualUndoAction(getString(R.string.undo)) {
                                if (pendingToken != null) {
                                    resolutionAccepted = downloadViewModel.undoPendingCancellation(
                                        item,
                                        pendingToken,
                                        owner,
                                    )
                                    if (resolutionAccepted) {
                                        selectedIntent = PendingUndoResolutionIntent.RESTORE
                                    }
                                    resolutionAccepted
                                } else {
                                    presentationScope.launch(Dispatchers.IO) {
                                        if (wasWaitingForMembership) {
                                            downloadViewModel.restoreMembershipWaiting(item)
                                        } else {
                                            downloadViewModel.undoCancelledDownload(item)
                                        }
                                    }
                                    selectedIntent = PendingUndoResolutionIntent.RESTORE
                                    true
                                }
                            }
                            if (pendingToken != null) {
                                snackbar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                                    override fun onShown(transientBottomBar: Snackbar?) {
                                        downloadViewModel.acknowledgeUndoPublication(pendingToken, owner)
                                    }

                                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                        if (selectedIntent == null && event != DISMISS_EVENT_ACTION) {
                                            val committed = downloadViewModel.commitPendingCancellation(
                                                item.id,
                                                pendingToken,
                                                owner,
                                            )
                                            if (
                                                !committed &&
                                                downloadViewModel.reofferCancellationUndoCapabilityAfterResolutionFailure(
                                                    pendingToken,
                                                    PendingUndoResolutionIntent.COMMIT,
                                                    owner,
                                                )
                                            ) {
                                                runCatching { snackbar.show() }
                                                    .onFailure {
                                                        downloadViewModel.abandonUndoCapabilityAfterConsumerFailure(
                                                            pendingToken,
                                                        )
                                                    }
                                            }
                                        } else if (selectedIntent == null &&
                                            !resolutionAccepted &&
                                            downloadViewModel.reofferCancellationUndoCapabilityAfterResolutionFailure(
                                                pendingToken,
                                                PendingUndoResolutionIntent.RESTORE,
                                                owner,
                                            )
                                        ) {
                                            runCatching { snackbar.show() }
                                                .onFailure {
                                                    downloadViewModel.abandonUndoCapabilityAfterConsumerFailure(
                                                        pendingToken,
                                                    )
                                                }
                                        }
                                    }
                                })
                            }
                            snackbar.show()
                        } catch (failure: Throwable) {
                            pendingToken?.let(downloadViewModel::abandonUndoCapabilityAfterConsumerFailure)
                            throw failure
                        }
            }
            }
            deleteDialog.show()
        }
    }

    private val contextualActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.queued_menu_context, menu)
            mode.title = "${adapter.getSelectedObjectsCount(totalSize)} ${getString(R.string.selected)}"
            return true
        }

        override fun onPrepareActionMode(
            mode: ActionMode?,
            menu: Menu?
        ): Boolean {
            return false
        }

        override fun onActionItemClicked(
            mode: ActionMode?,
            item: MenuItem?
        ): Boolean {
            return when (item!!.itemId) {
                R.id.select_between -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val selectedIDs = getSelectedIDs().sortedBy { it }
                        val idsInMiddle = withContext(Dispatchers.IO){
                            downloadViewModel.getIDsBetweenTwoItems(selectedIDs.first(), selectedIDs.last(), listOf(
                                DownloadRepository.Status.Queued).toListString())
                        }.toMutableList()
                        idsInMiddle.addAll(selectedIDs)
                        if (idsInMiddle.isNotEmpty()){
                            adapter.checkMultipleItems(idsInMiddle)
                            actionMode?.title = "${idsInMiddle.count()} ${getString(R.string.selected)}"
                        }
                        mode?.menu?.findItem(R.id.select_between)?.isVisible = false
                    }
                    true
                }
                R.id.delete_results -> {
                    val deleteDialog = MaterialAlertDialogBuilder(requireContext())
                    deleteDialog.setTitle(getString(R.string.you_are_going_to_delete_multiple_items))
                    deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                    deleteDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            val selectedObjects = getSelectedIDs()
                            adapter.clearCheckedItems()
                            for (id in selectedObjects){
                                downloadViewModel.cancelDownloadOnly(id)
                            }
                            downloadViewModel.deleteAllWithID(selectedObjects)
                            actionMode?.finish()
                        }

                    }
                    deleteDialog.show()
                    true
                }
                R.id.select_all -> {
                    adapter.checkAll()
                    mode?.title = "(${adapter.getSelectedObjectsCount(totalSize)}) ${resources.getString(R.string.all_items_selected)}"
                    true
                }
                R.id.invert_selected -> {
                    adapter.invertSelected()
                    val selectedObjects = adapter.getSelectedObjectsCount(totalSize)
                    actionMode!!.title = "$selectedObjects ${getString(R.string.selected)}"
                    if (selectedObjects == 0) actionMode?.finish()
                    true
                }
                R.id.up -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val selectedObjects = getSelectedIDs()
                        adapter.clearCheckedItems()
                        withContext(Dispatchers.IO){
                            downloadViewModel.putAtTopOfQueue(selectedObjects)
                        }
                        actionMode?.finish()
                    }
                    true
                }
                R.id.down -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val selectedObjects = getSelectedIDs()
                        adapter.clearCheckedItems()
                        withContext(Dispatchers.IO){
                            downloadViewModel.putAtBottomOfQueue(selectedObjects)
                        }
                        actionMode?.finish()
                    }
                    true
                }
                R.id.copy_urls -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val selectedObjects = getSelectedIDs()
                        val urls = withContext(Dispatchers.IO){
                            downloadViewModel.getURLsByIds(selectedObjects)
                        }
                        UiUtil.copyToClipboard(urls.joinToString("\n"), requireActivity())
                        actionMode?.finish()
                    }
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            adapter.clearCheckedItems()
        }

        suspend fun getSelectedIDs() : List<Long>{
            return if (adapter.inverted || adapter.checkedItems.isEmpty()){
                withContext(Dispatchers.IO){
                    downloadViewModel.getItemIDsNotPresentIn(
                        adapter.checkedItems.toList(),
                        listOf(
                            DownloadRepository.Status.Queued,
                            DownloadRepository.Status.WaitingForMembership
                        )
                    )
                }
            }else{
                adapter.checkedItems.toList()
            }
        }
    }


    private var simpleCallback: ItemTouchHelper.SimpleCallback =
        object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView,viewHolder: RecyclerView.ViewHolder,target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val itemID = viewHolder.itemView.tag?.toString()?.toLongOrNull()
                val position = viewHolder.bindingAdapterPosition
                if (itemID == null) {
                    if (position != RecyclerView.NO_POSITION) adapter.notifyItemChanged(position) else adapter.notifyDataSetChanged()
                    return
                }
                when (direction) {
                    ItemTouchHelper.LEFT -> {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val deletedItem = withContext(Dispatchers.IO){
                                runCatching { downloadViewModel.getItemByID(itemID) }.getOrNull()
                            } ?: return@launch
                            if (position != RecyclerView.NO_POSITION) adapter.notifyItemChanged(position) else adapter.notifyDataSetChanged()
                            removeItem(deletedItem.id)
                        }
                    }

                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                RecyclerViewSwipeDecorator.Builder(
                    requireContext(),
                    c,
                    recyclerView,
                    viewHolder,
                    dX,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
                    .addSwipeLeftBackgroundColor(Color.RED)
                    .addSwipeLeftActionIcon(R.drawable.baseline_delete_24)
                    .addSwipeRightBackgroundColor(
                        MaterialColors.getColor(
                            requireContext(),
                            R.attr.colorOnSurfaceInverse, Color.TRANSPARENT
                        )
                    )
                    .create()
                    .decorate()
                super.onChildDraw(
                    c,
                    recyclerView,
                    viewHolder,
                    dX,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
            }
        }

    var movedToNewPositionID = 0L
    private val queuedDragDropHelper: ItemTouchHelper.SimpleCallback =
        object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                if (
                    fromPosition == RecyclerView.NO_POSITION ||
                    toPosition == RecyclerView.NO_POSITION ||
                    adapter.isWaitingForMembership(fromPosition) ||
                    adapter.isWaitingForMembership(toPosition)
                ) {
                    return false
                }
                val targetId = target.itemView.tag?.toString()?.toLongOrNull() ?: return false
                movedToNewPositionID = targetId
                adapter.notifyItemMoved(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (ItemTouchHelper.ACTION_STATE_DRAG == actionState) {
                    movedToNewPositionID =
                        viewHolder?.itemView?.tag?.toString()?.toLongOrNull() ?: 0L
                    /**
                     * Change alpha, scale and elevation on drag.
                     */
                    (viewHolder?.itemView as? MaterialCardView)?.also {
                        AnimatorSet().apply {
                            this.duration = 100L
                            this.interpolator = AccelerateDecelerateInterpolator()

                            playTogether(
                                UiUtil.getAlphaAnimator(it, 0.7f),
                                UiUtil.getScaleXAnimator(it, 1.02f),
                                UiUtil.getScaleYAnimator(it, 1.02f),
                                UiUtil.getElevationAnimator(it, R.dimen.elevation_6dp)
                            )
                        }.start()
                    }
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                /**
                 * Clear alpha, scale and elevation after drag/swipe
                 */
                (viewHolder.itemView as? MaterialCardView)?.also {
                    AnimatorSet().apply {
                        this.duration = 100L
                        this.interpolator = AccelerateDecelerateInterpolator()

                        playTogether(
                            UiUtil.getAlphaAnimator(it, 1f),
                            UiUtil.getScaleXAnimator(it, 1f),
                            UiUtil.getScaleYAnimator(it, 1f),
                            UiUtil.getElevationAnimator(it, R.dimen.elevation_2dp)
                        )
                    }.start()
                }

                val sourceId = viewHolder.itemView.tag?.toString()?.toLongOrNull() ?: return
                if (movedToNewPositionID > 0L && sourceId != movedToNewPositionID) {
                    downloadViewModel.putAtPosition(sourceId, movedToNewPositionID)
                }
                movedToNewPositionID = 0L
            }

            override fun isLongPressDragEnabled(): Boolean {
                return false
            }
        }

    override fun onMoveQueuedItemToTop(itemID: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            downloadViewModel.putAtTopOfQueue(listOf(itemID))
        }
    }

    override fun onMoveQueuedItemToBottom(itemID: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            downloadViewModel.putAtBottomOfQueue(listOf(itemID))
        }
    }

    override fun onQueuedCardClick(itemID: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val item = withContext(Dispatchers.IO){
                downloadViewModel.getItemByID(itemID)
            }

            UiUtil.showDownloadItemDetailsCard(
                item,
                requireActivity(),
                DownloadRepository.Status.valueOf(item.status),
                ytdlpViewModel,
                sharedPreferences,
                removeItem = { it: DownloadItem, sheet: BottomSheetDialog ->
                    sheet.hide()
                    removeItem(it.id)
                },
                downloadItem = {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        downloadViewModel.rescheduleExistingDownload(it.id, 0L)
                    }
                },
                longClickDownloadButton = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        it.status = DownloadRepository.Status.Saved.toString()
                        withContext(Dispatchers.IO){
                            downloadViewModel.updateToStatus(it.id, DownloadRepository.Status.Saved)
                        }
                        findNavController().navigate(R.id.downloadBottomSheetDialog, bundleOf(
                                Pair("downloadItem", it),
                                Pair("result", downloadViewModel.createResultItemFromDownload(it)),
                                Pair("type", it.type)
                            )
                        )
                    }
                },
                scheduleButtonClick = {downloadItem ->
                    UiUtil.showDatePicker(parentFragmentManager, sharedPreferences) {
                        Toast.makeText(context, getString(R.string.download_rescheduled_to) + " " + it.time, Toast.LENGTH_LONG).show()
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            downloadViewModel.rescheduleExistingDownload(
                                downloadItem.id,
                                it.timeInMillis
                            )
                        }
                    }
                }
            )
        }
    }

    override fun onQueuedCardSelect(isChecked: Boolean, position: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val selectedObjects = adapter.getSelectedObjectsCount(totalSize)
            if (actionMode == null) actionMode = (getActivity() as AppCompatActivity?)!!.startSupportActionMode(contextualActionBar)
            actionMode?.apply {
                if (selectedObjects == 0){
                    this.finish()
                }else{
                    this.title = "$selectedObjects ${getString(R.string.selected)}"
                    this.menu.findItem(R.id.up).isVisible = position > 0
                    this.menu.findItem(R.id.down).isVisible = position < totalSize
                    this.menu.findItem(R.id.select_between).isVisible = false
                    if(selectedObjects == 2){
                        val selectedIDs = contextualActionBar.getSelectedIDs().sortedBy { it }
                        val idsInMiddle = withContext(Dispatchers.IO){
                            downloadViewModel.getIDsBetweenTwoItems(selectedIDs.first(), selectedIDs.last(), listOf(DownloadRepository.Status.Queued).toListString())
                        }
                        this.menu.findItem(R.id.select_between).isVisible = idsInMiddle.isNotEmpty()
                    }
                }
            }
        }
    }

    override fun onQueuedCancelClick(itemID: Long) {
        cancelDownload(itemID)
    }

    private fun cancelDownload(itemID: Long){
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO){
                downloadViewModel.cancelDownload(itemID)
            }
        }
    }

    override fun onDestroyView() {
        undoPresentationLifetime.cancel()
        if (::downloadViewModel.isInitialized) {
            undoPresentationOwner?.let(downloadViewModel::abandonPendingUndoCapabilitiesForView)
        }
        undoPresentationOwner = null
        actionMode?.finish()
        queuedRecyclerView.adapter = null
        fragmentView = null
        super.onDestroyView()
    }

}

