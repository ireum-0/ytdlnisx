package com.ireum.ytdl.util.extractors.ytdlp

import com.ireum.ytdl.util.MediaPublishedDateSource
import com.ireum.ytdl.util.WebUrlInput
import java.util.zip.CRC32

internal data class InfoJsonCacheKey(
    val dispatchSource: String,
    val authoritativeIdentity: String,
    val authoritativePrefix: String,
) {
    fun writeFileName(timestamp: Long): String =
        "${authoritativePrefix}${timestamp}video.info.json"
}

internal object InfoJsonCacheKeyPolicy {
    private const val HASH_SEPARATOR = "-"

    fun resolve(source: String): InfoJsonCacheKey {
        val trimmed = source.trim()
        val extractorInput = WebUrlInput.resolveExtractorInput(trimmed)
        val dispatchSource = extractorInput?.dispatchValue ?: trimmed
        val authoritativeIdentity = MediaPublishedDateSource.youtubeVideoId(dispatchSource)
            ?: dispatchSource
        val authoritativePrefix = prefix(authoritativeIdentity)
        return InfoJsonCacheKey(
            dispatchSource = dispatchSource,
            authoritativeIdentity = authoritativeIdentity,
            authoritativePrefix = authoritativePrefix,
        )
    }

    fun legacySchemeLessPrefix(source: String, authoritativeKey: InfoJsonCacheKey): String? {
        val trimmed = source.trim()
        if (!WebUrlInput.isSchemeLessWebAddress(trimmed)) return null
        val legacyIdentity = MediaPublishedDateSource.youtubeVideoId(trimmed) ?: trimmed
        return prefix(legacyIdentity).takeUnless { it == authoritativeKey.authoritativePrefix }
    }

    fun hash(identity: String): String {
        val crc = CRC32()
        crc.update(identity.toByteArray())
        return crc.value.toString(16)
    }

    private fun prefix(identity: String): String = "${hash(identity)}$HASH_SEPARATOR"
}
