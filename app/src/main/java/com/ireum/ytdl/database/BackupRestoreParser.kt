package com.ireum.ytdl.database

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistGroup
import com.ireum.ytdl.database.models.PlaylistGroupMember
import com.ireum.ytdl.database.models.PlaylistItemCrossRef

/**
 * Production parser for the format-4 playlist relationship payload.
 *
 * The four arrays form one relationship graph. If any graph field is
 * present, all four must be present; otherwise a malformed payload could be
 * accepted as a successful graph with silently missing relationships.
 */
internal object BackupRestoreParser {
    data class PlaylistPayload(
        val playlists: List<Playlist>?,
        val playlistItemCrossRefs: List<PlaylistItemCrossRef>?,
        val playlistGroups: List<PlaylistGroup>?,
        val playlistGroupMembers: List<PlaylistGroupMember>?,
    )

    fun parsePlaylistPayload(json: JsonObject, gson: Gson): PlaylistPayload {
        val keys = listOf(
            "playlists",
            "playlist_item_cross_refs",
            "playlist_groups",
            "playlist_group_members",
        )
        val present = keys.filter(json::has)
        if (present.isEmpty()) {
            return PlaylistPayload(null, null, null, null)
        }
        require(present.size == keys.size) {
            "Incomplete playlist relationship payload; present fields: ${present.joinToString()}"
        }
        return PlaylistPayload(
            playlists = json.getAsJsonArray("playlists").map {
                gson.fromJson(it, Playlist::class.java)
            },
            playlistItemCrossRefs = json.getAsJsonArray("playlist_item_cross_refs").map {
                gson.fromJson(it, PlaylistItemCrossRef::class.java)
            },
            playlistGroups = json.getAsJsonArray("playlist_groups").map {
                gson.fromJson(it, PlaylistGroup::class.java)
            },
            playlistGroupMembers = json.getAsJsonArray("playlist_group_members").map {
                gson.fromJson(it, PlaylistGroupMember::class.java)
            },
        )
    }
}
