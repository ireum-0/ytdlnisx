package com.ireum.ytdl.work

import com.ireum.ytdl.util.DownloadConfigurationDuplicatePolicy
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandTokenizer
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpOptionOwnership
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpOutputPlan
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Immutable producer/output compatibility witness.
 *
 * Runtime-generated config, cache, archive, and staging paths are replaced by
 * stable placeholders.  The remaining yt-dlp command tokens and the output
 * plan are semantic producer inputs; operationId and retry labels are not.
 */
internal object DownloadProducerSemanticFingerprint {
    private val generatedPathOptions = setOf(
        "--config-locations",
        "--cache-dir",
        "--download-archive",
    )

    fun fingerprint(
        command: String,
        outputPlan: YtdlpOutputPlan? = null,
        publicationSemantics: Map<String, String> = emptyMap(),
    ): String {
        // Reuse the role-aware source-token policy used by Manual/Observe
        // duplicate matching. This canonicalizes supported source spellings
        // only; option-owned URL values remain byte-for-byte identity.
        val normalizedCommand = DownloadConfigurationDuplicatePolicy
            .normalizeCommandForComparison(command)
        val tokens = YtdlpCommandTokenizer.tokenize(normalizedCommand)
        val normalized = if (tokens == null) {
            normalizedCommand
        } else {
            normalizeTokens(tokens, outputPlan)
        }
        val plan = outputPlan?.let {
            listOf(
                "final=${it.finalDestination}",
                "directNoCache=${it.directNoCache}",
                "explicitCommandPath=${it.explicitCommandPath}",
                "directDestination=${it.directDestinationDirectory?.canonicalPath.orEmpty()}",
                "pathHome=${it.commandPathMap?.home?.canonicalPath.orEmpty()}",
                "pathTemp=${it.commandPathMap?.temp?.canonicalPath.orEmpty()}",
            ).joinToString("\n")
        }.orEmpty()
        val publication = publicationSemantics
            .toSortedMap()
            .entries
            .joinToString("\n") { (key, value) -> "${key.trim()}=${value.trim()}" }
        val payload = listOf(normalized, plan, publication)
            .filter(String::isNotBlank)
            .joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizeTokens(
        tokens: List<String>,
        outputPlan: YtdlpOutputPlan?,
    ): String {
        val generatedPaths = listOfNotNull(
            outputPlan?.ytdlpDirectory?.canonicalPath,
            outputPlan?.directStagingDirectory?.canonicalPath,
            outputPlan?.directStagingParent?.canonicalPath,
            outputPlan?.structuredOutputMarker?.canonicalPath,
            outputPlan?.ownershipMarker?.canonicalPath,
        )
        val normalized = ArrayList<String>(tokens.size)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (YtdlpOptionOwnership.isOptionToken(token)) {
                val ownership = YtdlpOptionOwnership.inspect(tokens, index)
                val normalizedOption = if (
                    ownership.canonicalName in generatedPathOptions &&
                        ownership.inlineValue != null &&
                        token.contains('=')
                ) {
                    token.substringBefore('=') + "=<runtime>"
                } else {
                    token
                }
                normalized += normalizedOption
                repeat(ownership.consumedFollowingTokenCount) { offset ->
                    val raw = tokens[index + 1 + offset]
                    val value = if (ownership.canonicalName in generatedPathOptions) {
                        "<runtime>"
                    } else {
                        replaceGeneratedPath(raw, generatedPaths)
                    }
                    normalized += value
                }
                index += ownership.nextIndexDelta
            } else {
                normalized += replaceGeneratedPath(token, generatedPaths)
                index += 1
            }
        }
        return YtdlpCommandTokenizer.render(normalized)
    }

    private fun replaceGeneratedPath(value: String, generatedPaths: List<String>): String {
        var result = value
        generatedPaths.forEach { path ->
            if (path.isNotBlank()) result = result.replace(path, "<runtime>")
        }
        return result
    }
}
