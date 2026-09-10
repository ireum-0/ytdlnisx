package com.ireum.ytdl.util.extractors.ytdlp

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.Collections
import java.util.WeakHashMap

/**
 * Keeps the effective generated-config semantics alongside the request that
 * materialized them.  The value is process-local only; the caller persists
 * the resulting fingerprint, never the config text.
 */
internal object YtdlpProducerSemanticSnapshot {
    internal data class Snapshot(
        val configContents: String,
        val runtimePaths: Set<String> = emptySet(),
    )

    private val snapshots = Collections.synchronizedMap(
        WeakHashMap<YoutubeDLRequest, Snapshot>(),
    )

    fun register(
        request: YoutubeDLRequest,
        configContents: String,
        runtimePaths: Iterable<String> = emptyList(),
    ) {
        snapshots[request] = Snapshot(
            configContents = configContents,
            runtimePaths = runtimePaths
                .mapNotNull { path -> path.trim().takeIf(String::isNotBlank) }
                .toSet(),
        )
    }

    fun forRequest(request: YoutubeDLRequest): Snapshot? = snapshots[request]
}
