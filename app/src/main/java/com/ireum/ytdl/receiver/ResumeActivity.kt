package com.ireum.ytdl.receiver

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.ireum.ytdl.R
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.ui.BaseActivity
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.ThemeUtil
import com.ireum.ytdl.util.download.DownloadRetryDecision
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResumeActivity : BaseActivity() {

    lateinit var context: Context
    private lateinit var downloadViewModel: DownloadViewModel


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtil.updateTheme(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }
        window.run {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
        }

        setContentView(R.layout.activity_share)
        this.setFinishOnTouchOutside(false)
        context = baseContext
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        val intent = intent
        handleIntents(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    private fun handleIntents(intent: Intent) {
        val id = intent.getIntExtra("itemID", 0)
        val retryError = intent.getBooleanExtra("retryError", false)
        if (id != 0) {
            try {
                val loadingBottomSheet = BottomSheetDialog(this)
                loadingBottomSheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
                loadingBottomSheet.setContentView(R.layout.please_wait_bottom_sheet)

                loadingBottomSheet.setOnShowListener {
                    lifecycleScope.launch {
                        try {
                            val decision = withContext(Dispatchers.IO) {
                                if (retryError) {
                                    downloadViewModel.retryFailedDownload(id.toLong())
                                } else {
                                    downloadViewModel.reQueueDownloadItemsAndWait(listOf(id.toLong()))
                                    null
                                }
                            }
                            if (decision !is DownloadRetryDecision.Blocked) {
                                val notificationId = if (retryError) {
                                    NotificationUtil.DOWNLOAD_ERRORED_NOTIFICATION_ID + id
                                } else {
                                    NotificationUtil.DOWNLOAD_RESUME_NOTIFICATION_ID + id
                                }
                                NotificationUtil(this@ResumeActivity)
                                    .cancelDownloadNotification(notificationId)
                            }
                            if (decision is DownloadRetryDecision.Blocked) {
                                Toast.makeText(
                                    this@ResumeActivity,
                                    downloadViewModel.retryBlockedMessage(decision),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } catch (_: Exception) {
                            Toast.makeText(
                                this@ResumeActivity,
                                getString(R.string.error_restarting_download),
                                Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            loadingBottomSheet.dismiss()
                            finishAffinity()
                        }
                    }

                }
                loadingBottomSheet.show()
            }catch (e: Exception){
                Toast.makeText(this, getString(R.string.error_restarting_download), Toast.LENGTH_SHORT).show()
                finishAffinity()
            }
        }
    }
}
