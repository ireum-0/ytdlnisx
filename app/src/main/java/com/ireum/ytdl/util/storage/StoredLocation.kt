package com.ireum.ytdl.util.storage

enum class StoredLocationKind {
    RAW_PATH,
    CONTENT_URI,
    FILE_URI
}

data class StoredLocation(
    val kind: StoredLocationKind,
    val value: String,
    val parentValue: String?,
    val isAppPrivate: Boolean
)

enum class OpenStoredLocationResult {
    OPENED,
    COPY_PARENT_FALLBACK,
    UNAVAILABLE
}
