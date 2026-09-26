package com.ireum.ytdl.util.terminal

/**
 * Typed Terminal output authority.
 *
 * Display formatting is not authority.  A persisted `content://` tree grant
 * proves app/provider writability, never native yt-dlp filesystem
 * writability, so it must never be reconstructed into a raw pathname and then
 * used as a native output destination.  Only a [NativeRaw] path that was
 * independently established as natively writable may be handed to yt-dlp
 * directly; a [ProviderTree] destination is publishable only by the app.
 */
sealed interface TerminalDestinationAuthority {
    /**
     * A raw filesystem path native yt-dlp can write itself.  [path] is the
     * normalized native value that is safe to emit as `-P`/`--paths`.
     */
    data class NativeRaw(val path: String) : TerminalDestinationAuthority

    /**
     * The exact persisted provider tree URI, including its grant.  It is the
     * final publication destination and is never a native output path.
     */
    data class ProviderTree(val treeUri: String) : TerminalDestinationAuthority

    /**
     * A persisted value that is neither a usable raw path nor a usable
     * provider tree.  It is not a storage authority at all.
     */
    data class Unusable(val persistedValue: String) : TerminalDestinationAuthority

    companion object {
        private const val CONTENT_SCHEME = "content://"

        /**
         * Classifies a persisted Terminal destination.  The value is never
         * rewritten here; a provider value keeps its exact original URI so the
         * grant stays the authority.
         */
        fun classify(configuredPath: String): TerminalDestinationAuthority {
            val trimmed = configuredPath.trim()
            if (trimmed.isEmpty()) return Unusable(configuredPath)
            if (!trimmed.startsWith(CONTENT_SCHEME)) return NativeRaw(trimmed)
            // A provider value must name an authority, otherwise it can never
            // be published through and is not a usable location.
            val authority = trimmed.removePrefix(CONTENT_SCHEME).substringBefore('/')
            return if (authority.isBlank()) Unusable(trimmed) else ProviderTree(trimmed)
        }

        /**
         * Narrows a provider destination for execution, or `null` when this
         * authority is a raw path that native yt-dlp may own directly.
         */
        fun providerTreeOrNull(authority: TerminalDestinationAuthority): ProviderTree? =
            authority as? ProviderTree
    }
}
