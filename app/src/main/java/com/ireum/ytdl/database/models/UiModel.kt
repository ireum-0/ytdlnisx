package com.ireum.ytdl.database.models

import androidx.room.Embedded

sealed class UiModel {
    data class HistoryItemModel(val historyItem: HistoryItem) : UiModel()
    data class SeparatorModel(val author: String) : UiModel()
    data class YoutuberInfoModel(val youtuberInfo: YoutuberInfo) : UiModel()
    data class YoutuberGroupModel(val groupInfo: YoutuberGroupInfo) : UiModel()
    data class PlaylistInfoModel(val playlistInfo: PlaylistInfo) : UiModel()
    data class PlaylistGroupModel(val groupInfo: PlaylistGroupInfo) : UiModel()
}

