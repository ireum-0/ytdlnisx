package com.ireum.ytdl.util

import android.content.Context
import android.util.Log
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.LogItem
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class CrashListener(private val context: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(p0: Thread, p1: Throwable) {
        Log.e("CrashListener", "Uncaught exception", p1)
        CoroutineScope(SupervisorJob()).launch(Dispatchers.IO) {
            createLog("${p1.message}\n\n${p1.stackTrace.joinToString("\n")}")
        }
    }

    private suspend fun createLog(message: String){
        kotlin.runCatching {
            val db = DBManager.getInstance(context)
            val dao = db.logDao
            dao.insert(LogItem(
                id = 0L,
                title = "APP CRASH",
                content = message,
                format = Format("", "", "", "", "", 0, "", "", "", "", "", ""),
                downloadType = DownloadType.command,
                downloadTime = System.currentTimeMillis()
            ))
        }
        Log.e("ExitTrace", "exitProcess requested (CrashListener)", Throwable())
        exitProcess(0)
    }

    fun registerExceptionHandler(){
        Thread.setDefaultUncaughtExceptionHandler(this)
    }
}
