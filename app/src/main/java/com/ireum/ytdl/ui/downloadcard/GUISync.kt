package com.ireum.ytdl.ui.downloadcard

import com.ireum.ytdl.database.models.ResultItem

interface GUISync {
    fun updateTitleAuthor(t: String, a: String)
    fun updateUI(res: ResultItem?)
}
