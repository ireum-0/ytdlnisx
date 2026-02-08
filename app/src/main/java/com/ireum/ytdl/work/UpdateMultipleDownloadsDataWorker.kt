package com.ireum.ytdl.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.App
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.util.NotificationUtil


class UpdateMultipleDownloadsDataWorker(private val context: Context,workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val workNotif = NotificationUtil(App.instance).createDataUpdateNotification()

        return ForegroundInfo(
            2000000000,
            workNotif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }


    override suspend fun doWork(): Result {
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val resDao = dbManager.resultDao
        val commandTemplateDao = dbManager.commandTemplateDao
        val resultRepo = ResultRepository(resDao,commandTemplateDao, context)
        val ids = inputData.getLongArray("ids")?.toMutableList() ?: return Result.failure()

        setForegroundSafely()
        try{
            ids.forEach {
                if (!isStopped){
                    val d = dao.getDownloadById(it)
                    if (d.title.isNotBlank() && d.author.isNotBlank() && d.thumb.isNotBlank()) {
                        return@forEach
                    }

                    runCatching {
                        resultRepo.updateDownloadItem(d)?.apply {
                            val dd = dao.getNullableDownloadById(it)
                            if (dd != null) {
                                d.status = dd.status
                                dao.updateWithoutUpsert(this)
                            }
                        }
                    }
                }else{
                    throw Exception()
                }
            }


        }catch (e: Exception){
            ids.clear()
            return Result.failure()
        }

        return Result.success()
    }

}
