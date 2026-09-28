package com.ireum.ytdl.database.cookies

import android.content.Context
import android.system.Os
import com.ireum.ytdl.database.models.CookieItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One process-wide owner for Room-to-runtime cookie projection. */
internal object CookieProjectionCoordinator {
    enum class Failure {
        MUTATION_FAILED,
        READ_FAILED,
        NO_COOKIE_DATA,
        WRITE_FAILED,
        VERIFY_FAILED,
    }

    sealed interface Outcome {
        data class Ready(val generation: String) : Outcome
        data class Unavailable(val failure: Failure) : Outcome
    }

    sealed interface AcquisitionOutcome {
        data class Ready(
            val requestId: String,
            val roomId: Long,
            val projectionGeneration: String,
        ) : AcquisitionOutcome

        data class Failed(val failure: Failure) : AcquisitionOutcome
    }

    private val mutex = Mutex()

    /** Test seam that can stop an acquisition after its Room write. */
    @Volatile
    internal var beforeProjectionForTesting: ((String?) -> Unit)? = null

    /** Test seam for a deterministic projection-write failure. */
    @Volatile
    internal var failProjectionForTesting: Boolean = false

    suspend fun acquireAndProject(
        context: Context,
        cookieHeader: String,
        requestId: String,
        upsert: suspend () -> Long,
        readExactRow: suspend (Long) -> CookieItem?,
        readEnabledRows: suspend () -> List<CookieItem>,
    ): AcquisitionOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (requestId.isBlank()) {
                return@withLock AcquisitionOutcome.Failed(Failure.MUTATION_FAILED)
            }
            if (!retireRuntimeProjection(context)) {
                return@withLock AcquisitionOutcome.Failed(Failure.WRITE_FAILED)
            }
            val roomId = try {
                upsert()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock AcquisitionOutcome.Failed(Failure.MUTATION_FAILED)
            }
            val exactRow = try {
                readExactRow(roomId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock AcquisitionOutcome.Failed(Failure.READ_FAILED)
            }
            if (exactRow == null || !exactRow.enabled) {
                return@withLock AcquisitionOutcome.Failed(Failure.READ_FAILED)
            }
            val rows = try {
                readEnabledRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock AcquisitionOutcome.Failed(Failure.READ_FAILED)
            }
            when (val projection = projectLocked(context, cookieHeader, rows, requestId)) {
                is Outcome.Ready -> {
                    val finalExactRow = try {
                        readExactRow(roomId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        retireRuntimeProjection(context)
                        return@withLock AcquisitionOutcome.Failed(Failure.READ_FAILED)
                    }
                    if (finalExactRow != exactRow) {
                        retireRuntimeProjection(context)
                        return@withLock AcquisitionOutcome.Failed(Failure.READ_FAILED)
                    }
                    AcquisitionOutcome.Ready(
                        requestId = requestId,
                        roomId = roomId,
                        projectionGeneration = projection.generation,
                    )
                }
                is Outcome.Unavailable -> AcquisitionOutcome.Failed(projection.failure)
            }
        }
    }

    suspend fun mutateAndProject(
        context: Context,
        cookieHeader: String,
        mutation: suspend () -> Unit,
        readEnabledRows: suspend () -> List<CookieItem>,
    ): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!retireRuntimeProjection(context)) {
                return@withLock Outcome.Unavailable(Failure.WRITE_FAILED)
            }
            try {
                mutation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock Outcome.Unavailable(Failure.MUTATION_FAILED)
            }
            val rows = try {
                readEnabledRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock Outcome.Unavailable(Failure.READ_FAILED)
            }
            projectLocked(context, cookieHeader, rows, requestId = null)
        }
    }

    suspend fun projectCurrent(
        context: Context,
        cookieHeader: String,
        readEnabledRows: suspend () -> List<CookieItem>,
    ): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!retireRuntimeProjection(context)) {
                return@withLock Outcome.Unavailable(Failure.WRITE_FAILED)
            }
            val rows = try {
                readEnabledRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock Outcome.Unavailable(Failure.READ_FAILED)
            }
            projectLocked(context, cookieHeader, rows, requestId = null)
        }
    }

    /**
     * Require a generated, readable Netscape cookie file at the request
     * boundary. Configuration intent (`use_cookies`) alone is insufficient.
     */
    fun requireUsableFile(context: Context): File {
        val cookieFile = File(context.cacheDir, "cookies.txt")
        if (!isUsableCookieFile(cookieFile)) {
            throw CookieReadinessUnavailableException()
        }
        return cookieFile
    }

    internal fun isUsableCookieFile(cookieFile: File): Boolean = runCatching {
        if (!cookieFile.isFile || !cookieFile.canRead() || cookieFile.length() <= 0L) return false
        val text = cookieFile.readText(Charsets.UTF_8)
        text.lineSequence().any(::isNetscapeCookieRecord)
    }.getOrDefault(false)

    private fun projectLocked(
        context: Context,
        cookieHeader: String,
        enabledRows: List<CookieItem>,
        requestId: String?,
    ): Outcome {
        val cookieFile = File(context.cacheDir, "cookies.txt")
        val cookieLines = mutableListOf<String>()
        for (row in enabledRows) {
            val lines = row.content.lineSequence()
                .map(String::trimEnd)
                .toList()
            val rowLines = lines.filterNot(::isCookieComment)
            if (rowLines.isEmpty() || rowLines.any { !isNetscapeCookieRecord(it) }) {
                return Outcome.Unavailable(Failure.NO_COOKIE_DATA)
            }
            cookieLines += rowLines
        }
        val uniqueCookieLines = cookieLines.distinct()
        if (uniqueCookieLines.isEmpty()) {
            // Retire stale runtime credentials when no Room cookie is enabled.
            return if (writeAtomically(cookieFile, "")) {
                Outcome.Unavailable(Failure.NO_COOKIE_DATA)
            } else {
                Outcome.Unavailable(Failure.WRITE_FAILED)
            }
        }

        val expected = buildString {
            append(cookieHeader.trimEnd())
            append('\n')
            uniqueCookieLines.forEach { line ->
                append(line)
                append('\n')
            }
        }
        beforeProjectionForTesting?.invoke(requestId)
        if (failProjectionForTesting) return Outcome.Unavailable(Failure.WRITE_FAILED)
        if (!writeAtomically(cookieFile, expected)) {
            return Outcome.Unavailable(Failure.WRITE_FAILED)
        }
        val verified = runCatching { cookieFile.readText(Charsets.UTF_8) == expected }
            .getOrDefault(false)
        if (!verified || !isUsableCookieFile(cookieFile)) {
            return Outcome.Unavailable(Failure.VERIFY_FAILED)
        }
        return Outcome.Ready(UUID.randomUUID().toString())
    }

    private fun isCookieComment(line: String): Boolean =
        line.isBlank() || line.startsWith("#") && !line.startsWith("#HttpOnly_")

    private fun isNetscapeCookieRecord(line: String): Boolean {
        if (isCookieComment(line)) return false
        val fields = line.split('\t', limit = 7)
        if (fields.size != 7) return false
        if (fields.last().contains('\t')) return false
        val domain = fields[0].removePrefix("#HttpOnly_")
        return domain.isNotBlank() &&
            fields[1].uppercase() in setOf("TRUE", "FALSE") &&
            fields[2].isNotBlank() &&
            fields[3].uppercase() in setOf("TRUE", "FALSE") &&
            fields[4].toLongOrNull() != null &&
            fields[5].isNotBlank()
    }

    private fun retireRuntimeProjection(context: Context): Boolean = runCatching {
        val cookieFile = File(context.cacheDir, "cookies.txt")
        !cookieFile.exists() || cookieFile.delete() && !cookieFile.exists()
    }.getOrDefault(false)

    private fun writeAtomically(destination: File, text: String): Boolean = runCatching {
        val parent = destination.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, destination.absolutePath)
            destination.isFile
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.getOrDefault(false)
}

internal class CookieReadinessUnavailableException : java.io.IOException(
    "Cookie authentication is enabled, but no usable runtime cookie projection is available",
)
