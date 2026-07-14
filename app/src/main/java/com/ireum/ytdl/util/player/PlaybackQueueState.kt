package com.ireum.ytdl.util.player

import kotlin.random.Random

data class PlaybackQueuePreparedData(
    val itemIds: List<Long>,
    val playablePathById: Map<Long, String>,
    val mediaKeyById: Map<Long, String>,
    val idByMediaKey: Map<String, Long>,
    val indexById: Map<Long, Int>,
    val playbackPositionsById: Map<Long, Long>
)

class PlaybackQueueState<T>(
    private val idOf: (T) -> Long,
    private val pathsOf: (T) -> List<String>,
    private val playbackPositionOf: (T) -> Long
) {
    var items: List<T> = emptyList()
        private set
    var baseItems: List<T> = emptyList()
        private set
    var isShuffled: Boolean = false
        private set

    private var playablePathById: Map<Long, String> = emptyMap()
    private var mediaKeyById: Map<Long, String> = emptyMap()
    private var idByMediaKey: Map<String, Long> = emptyMap()
    private var indexById: Map<Long, Int> = emptyMap()
    private val playbackPositionsById = mutableMapOf<Long, Long>()

    fun setBaseItems(value: List<T>) {
        baseItems = value.toList()
    }

    fun setShuffled(value: Boolean) {
        isShuffled = value
    }

    fun prepare(
        value: List<T>,
        preferredPlayablePaths: Map<Long, String> = emptyMap(),
        previousPlayablePaths: Map<Long, String> = emptyMap(),
        mediaKeyForPath: (String) -> String
    ): PlaybackQueuePreparedData {
        val playablePaths = LinkedHashMap<Long, String>(value.size)
        val mediaKeys = LinkedHashMap<Long, String>(value.size)
        val idsByMediaKey = LinkedHashMap<String, Long>(value.size)
        val indices = LinkedHashMap<Long, Int>(value.size)
        val playbackPositions = LinkedHashMap<Long, Long>(value.size)
        val itemIds = ArrayList<Long>(value.size)

        value.forEachIndexed { index, item ->
            val id = idOf(item)
            itemIds += id
            indices[id] = index
            playbackPositions[id] = playbackPositionOf(item)

            val playablePath = preferredPlayablePaths[id]
                ?: previousPlayablePaths[id]
                ?: pathsOf(item).firstOrNull(String::isNotBlank)
                ?: pathsOf(item).firstOrNull()
                ?: return@forEachIndexed
            val mediaKey = mediaKeyForPath(playablePath)
            playablePaths[id] = playablePath
            mediaKeys[id] = mediaKey
            idsByMediaKey[mediaKey] = id
        }

        return PlaybackQueuePreparedData(
            itemIds = itemIds,
            playablePathById = playablePaths,
            mediaKeyById = mediaKeys,
            idByMediaKey = idsByMediaKey,
            indexById = indices,
            playbackPositionsById = playbackPositions
        )
    }

    fun replaceItems(value: List<T>, preparedData: PlaybackQueuePreparedData) {
        require(preparedData.itemIds == value.map(idOf)) {
            "Prepared queue data does not match the queue order"
        }
        items = value.toList()
        playablePathById = preparedData.playablePathById.toMap()
        mediaKeyById = preparedData.mediaKeyById.toMap()
        idByMediaKey = preparedData.idByMediaKey.toMap()
        indexById = preparedData.indexById.toMap()
        playbackPositionsById.clear()
        playbackPositionsById.putAll(preparedData.playbackPositionsById)
    }

    fun updateItem(updated: T) {
        val id = idOf(updated)
        items = items.map { if (idOf(it) == id) updated else it }
        baseItems = baseItems.map { if (idOf(it) == id) updated else it }
    }

    fun shuffledOrderKeepingCurrent(currentItemId: Long?, random: Random = Random.Default): List<T> {
        val current = currentItemId?.let { id -> items.firstOrNull { idOf(it) == id } }
        val remaining = items.filter { idOf(it) != currentItemId }.shuffled(random)
        return if (current == null) remaining else listOf(current) + remaining
    }

    fun itemAtOffset(currentItemId: Long, offset: Int): T? {
        val currentIndex = indexById[currentItemId] ?: return null
        return items.getOrNull(currentIndex + offset)
    }

    fun containsId(id: Long): Boolean = indexById.containsKey(id)

    fun indexOf(id: Long): Int? = indexById[id]

    fun playablePath(id: Long): String? = playablePathById[id]

    fun mediaKey(id: Long): String? = mediaKeyById[id]

    fun idForMediaKey(mediaKey: String): Long? = idByMediaKey[mediaKey]

    fun playablePathsSnapshot(): Map<Long, String> = playablePathById.toMap()

    fun playbackPosition(id: Long): Long? = playbackPositionsById[id]

    fun recordPlaybackPosition(id: Long, positionMs: Long) {
        playbackPositionsById[id] = positionMs
    }
}
