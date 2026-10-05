package com.ireum.ytdl.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.ireum.ytdl.BuildConfig
import com.ireum.ytdl.R
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.models.GithubRelease
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpRuntimeAuthority
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL


class UpdateUtil(context: Context) {
    private val context = context.applicationContext
    private val tag = "UpdateUtil"
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val provenancePreferences = destinationProvenancePreferences(this.context)

    private val channelMap = mapOf(
        Pair<String, YoutubeDL.UpdateChannel>("stable", YoutubeDL.UpdateChannel.STABLE),
        Pair<String, YoutubeDL.UpdateChannel>("nightly", YoutubeDL.UpdateChannel.NIGHTLY),
        Pair<String, YoutubeDL.UpdateChannel>("master", YoutubeDL.UpdateChannel.MASTER)
    )

    private fun String.tagNameToVersionNumber() : Int {
        return this.replace("-beta", "").replace(".", "").padEnd(10,'0').toInt()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun tryGetNewVersion() : Result<GithubRelease> {
        try {
            val skippedVersions = sharedPreferences.getString("skip_updates", "")?.split(",")?.distinct()?.toMutableList() ?: mutableListOf()
            val res = getGithubReleases()

            if (res.isEmpty()){
                return Result.failure(Error(context.getString(R.string.network_error)))
            }

            val currentVersion = BuildConfig.VERSION_NAME
            val currentVerNumber = currentVersion.tagNameToVersionNumber()

            val useBeta = sharedPreferences.getBoolean("update_beta", false)
            var isInLatest = true

            var v: GithubRelease
            if (useBeta) {
                v = res.firstOrNull { it.tag_name.contains("beta", true) } ?: res.first()
                val stableV = res.first { !it.tag_name.contains("beta", true) }

                val incomingVerNumber = v.tag_name.removePrefix("v").tagNameToVersionNumber()
                val incomingStableVerNumber = stableV.tag_name.removePrefix("v").tagNameToVersionNumber()

                //if in beta but latest stable higher
                if (currentVerNumber < incomingStableVerNumber) {
                    isInLatest = false
                    v = stableV
                }else{
                    isInLatest = currentVerNumber >= incomingVerNumber
                }
            }else {
                v = res.first { !it.tag_name.contains("beta", true) }
                val incomingVerNumber = v.tag_name.removePrefix("v").tagNameToVersionNumber()

                //if current version is beta but wants to downgrade to stable, allow it
                isInLatest = if (currentVersion.contains("beta", true)) {
                    false
                }else {
                    currentVerNumber >= incomingVerNumber
                }
            }

            if (skippedVersions.contains(v.tag_name)) isInLatest = true
            if (isInLatest) return Result.failure(Error(context.getString(R.string.you_are_in_latest_version)))
            return Result.success(v)
        }catch (e: Exception){
            e.printStackTrace()
            return Result.failure(e)
        }
    }

    fun getGithubReleases(): List<GithubRelease> {
        val url = "https://api.github.com/repos/ireum-0/ytdlnisx/releases"
        val conn: HttpURLConnection
        var json = listOf<GithubRelease>()
        try {
            val req = URL(url)
            conn = req.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 5000
            if (conn.responseCode < 300) {
                val myType = object : TypeToken<List<GithubRelease>>() {}.type
                json = Gson().fromJson(InputStreamReader(conn.inputStream), myType)
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(tag, e.toString())
        }
        return json
    }

    data class YTDLPUpdateResponse (
        val status: YTDLPUpdateStatus,
        val message: String = ""
    )

    enum class YTDLPUpdateStatus {
        DONE, ALREADY_UP_TO_DATE, PROCESSING, SUPERSEDED, ERROR
    }

    data class DesiredSource(val source: String, val generation: Long)

    /** Persist a source choice before any updater request can use it. */
    fun selectSource(source: String, label: String): Long {
        val selectedSource = source.trim()
        require(selectedSource.isNotEmpty()) { "yt-dlp source must not be blank" }
        return RestoreMutationAdmission.withOrdinaryMutationBlocking(context) {
            synchronized(stateLock) {
                retirePreChangeProvenance(sharedPreferences, provenancePreferences)
                val previousSource = sharedPreferences.getString(PREF_SOURCE, DEFAULT_SOURCE)
                    ?: DEFAULT_SOURCE
                val sourceWasPersisted = sharedPreferences.contains(PREF_SOURCE)
                val previousGeneration = desiredSourceGeneration(sharedPreferences, provenancePreferences)
                val changed = !sourceWasPersisted || previousSource != selectedSource
                val generation = if (changed) {
                    check(previousGeneration < Long.MAX_VALUE) { "yt-dlp source generation exhausted" }
                    previousGeneration + 1L
                } else {
                    previousGeneration
                }
                val persisted = sharedPreferences.edit()
                    .putString(PREF_SOURCE, selectedSource)
                    .putString(PREF_SOURCE_LABEL, label)
                    .putLong(PREF_DESIRED_GENERATION, generation)
                    .commit()
                check(persisted) { "yt-dlp source selection was not durable" }
                generation
            }
        }
    }

    fun desiredSource(): DesiredSource = synchronized(stateLock) {
        readDesiredSourceLocked()
    }

    /**
     * Manual and worker requests bind to the desired source observed at call
     * admission. A queued old request is rejected after a newer source wins.
     */
    suspend fun updateYoutubeDL(expectedGeneration: Long? = null): YTDLPUpdateResponse {
        val requested = desiredSource()
        if (expectedGeneration != null && requested.generation != expectedGeneration) {
            return YTDLPUpdateResponse(YTDLPUpdateStatus.SUPERSEDED)
        }
        return update(requested, skipWhenAlreadyCommitted = false)
    }

    /** Startup repairs a durable desired/committed mismatch even when automatic updates are off. */
    suspend fun updateOnStartup(automaticUpdatesEnabled: Boolean): YTDLPUpdateResponse {
        val requested = desiredSource()
        return update(
            requested,
            skipWhenAlreadyCommitted = !automaticUpdatesEnabled,
        )
    }

    internal suspend fun updateStartupGeneration(
        requested: DesiredSource,
        automaticUpdatesEnabled: Boolean,
    ): YTDLPUpdateResponse = update(
        requested,
        skipWhenAlreadyCommitted = !automaticUpdatesEnabled,
    )

    internal fun startupGenerationIsCommitted(requested: DesiredSource): Boolean =
        desiredSource() == requested && committedMatches(requested) && !pendingMutationExists()

    internal suspend fun migrateDestinationProvenance() {
        RestoreMutationAdmission.withOrdinaryMutation(context) {
            retirePreChangeProvenance(sharedPreferences, provenancePreferences)
        }
    }

    private suspend fun update(
        requested: DesiredSource,
        skipWhenAlreadyCommitted: Boolean,
    ): YTDLPUpdateResponse {
        val coalesceWithAdmittedRequest = reserveUpdateRequest(requested)
        try {
            updateRequestAdmittedForTesting?.invoke()
            return updateMutex.withLock {
                withContext(Dispatchers.IO) {
                    // Startup reaches this only after its real readiness and
                    // Restore recovery prerequisites. Ordinary admission also
                    // fences manual/worker requests against an active Restore.
                    migrateDestinationProvenance()
                    val current = desiredSource()
                    if (current != requested) {
                        return@withContext YTDLPUpdateResponse(YTDLPUpdateStatus.SUPERSEDED)
                    }

                    val alreadyCommitted = committedMatches(current)
                    val hasPendingMutation = pendingMutationExists()
                    if ((skipWhenAlreadyCommitted || coalesceWithAdmittedRequest) &&
                        alreadyCommitted && !hasPendingMutation
                    ) {
                        return@withContext YTDLPUpdateResponse(YTDLPUpdateStatus.ALREADY_UP_TO_DATE)
                    }

                    // This durable marker orders mutation admission against source
                    // selection and lets startup recover an interrupted native update.
                    val mutationAdmitted = beginMutation(current)
                    if (!mutationAdmitted) {
                        return@withContext YTDLPUpdateResponse(YTDLPUpdateStatus.SUPERSEDED)
                    }

                    val response = runInterruptible(Dispatchers.IO) {
                        YtdlpRuntimeAuthority.withMutation(context) { authority ->
                            // A Restore may replace the published intent while this
                            // request waits for real runtime consumers to release.
                            if (RestoreGate.isRestoreInProgress(context) ||
                                desiredSource() != current || !pendingMutationMatches(current)
                            ) {
                                return@withMutation YTDLPUpdateResponse(YTDLPUpdateStatus.SUPERSEDED)
                            }
                            val nativeResponse = runBlocking {
                                updaterForTesting?.invoke(context, current.source)
                                    ?: performYoutubeDLUpdate(current.source, authority)
                            }
                            if (nativeResponse.status == YTDLPUpdateStatus.DONE ||
                                nativeResponse.status == YTDLPUpdateStatus.ALREADY_UP_TO_DATE
                            ) {
                                val request = YoutubeDLRequest(emptyList()).apply { addOption("--version") }
                                val verified = YoutubeDLCompat.executeUnderMutation(context, request, authority)
                                check(verified.exitCode == 0 && verified.out.isNotBlank()) {
                                    "yt-dlp promoted runtime did not pass native validation"
                                }
                            }
                            nativeResponse
                        }
                    }
                    if (response.status == YTDLPUpdateStatus.DONE ||
                        response.status == YTDLPUpdateStatus.ALREADY_UP_TO_DATE
                    ) {
                        if (!persistCommittedResult(current, response)) {
                            return@withContext YTDLPUpdateResponse(YTDLPUpdateStatus.SUPERSEDED)
                        }
                    }
                    response
                }
            }
        } finally {
            releaseUpdateRequest(requested)
        }
    }

    private fun reserveUpdateRequest(requested: DesiredSource): Boolean = synchronized(stateLock) {
        val existingCount = admittedRequests[requested] ?: 0
        admittedRequests[requested] = existingCount + 1
        existingCount > 0
    }

    private fun releaseUpdateRequest(requested: DesiredSource) {
        synchronized(stateLock) {
            val existingCount = checkNotNull(admittedRequests[requested])
            if (existingCount == 1) {
                admittedRequests.remove(requested)
            } else {
                admittedRequests[requested] = existingCount - 1
            }
        }
    }

    private fun readDesiredSourceLocked(): DesiredSource {
        val source = if (sharedPreferences.contains(PREF_SOURCE)) {
            checkNotNull(sharedPreferences.getString(PREF_SOURCE, null))
                .also { check(it.isNotBlank()) { "yt-dlp source preference is blank" } }
        } else {
            DEFAULT_SOURCE
        }
        val generation = desiredSourceGeneration(sharedPreferences, provenancePreferences)
        return DesiredSource(source, generation)
    }

    private fun committedMatches(desired: DesiredSource): Boolean = synchronized(stateLock) {
        destinationProvenanceEpochIsCurrent(provenancePreferences) &&
            sharedPreferences.contains(PREF_COMMITTED_GENERATION) &&
            sharedPreferences.contains(PREF_COMMITTED_SOURCE) &&
            sharedPreferences.contains(PREF_COMMITTED_RESULT) &&
            sharedPreferences.getLong(PREF_COMMITTED_GENERATION, -1L) == desired.generation &&
            sharedPreferences.getString(PREF_COMMITTED_SOURCE, null) == desired.source
    }

    private fun pendingMutationExists(): Boolean = synchronized(stateLock) {
        sharedPreferences.contains(PREF_PENDING_GENERATION) ||
            sharedPreferences.contains(PREF_PENDING_SOURCE)
    }

    private fun pendingMutationMatches(desired: DesiredSource): Boolean = synchronized(stateLock) {
        destinationProvenanceEpochIsCurrent(provenancePreferences) &&
            sharedPreferences.getLong(PREF_PENDING_GENERATION, -1L) == desired.generation &&
            sharedPreferences.getString(PREF_PENDING_SOURCE, null) == desired.source
    }

    private suspend fun beginMutation(desired: DesiredSource): Boolean =
        RestoreMutationAdmission.withOrdinaryMutation(context) {
            synchronized(stateLock) {
                if (readDesiredSourceLocked() != desired) return@withOrdinaryMutation false
                val persisted = sharedPreferences.edit()
                    .putLong(PREF_PENDING_GENERATION, desired.generation)
                    .putString(PREF_PENDING_SOURCE, desired.source)
                    .commit()
                check(persisted) { "yt-dlp update intent was not durable" }
                true
            }
        }

    private suspend fun persistCommittedResult(
        desired: DesiredSource,
        response: YTDLPUpdateResponse,
    ): Boolean =
        RestoreMutationAdmission.withOrdinaryMutation(context) {
            synchronized(stateLock) {
                // Restored state may have retired/replaced the exact pending
                // intent. An old success cannot prove that newer generation.
                if (!pendingMutationMatches(desired) ||
                    readDesiredSourceLocked() != desired
                ) return@withOrdinaryMutation false
                val editor = sharedPreferences.edit()
                    .putLong(PREF_COMMITTED_GENERATION, desired.generation)
                    .putString(PREF_COMMITTED_SOURCE, desired.source)
                    .putString(PREF_COMMITTED_RESULT, "${response.status.name}:${response.message}")
                val pendingMatches = sharedPreferences.getLong(PREF_PENDING_GENERATION, -1L) ==
                    desired.generation &&
                    sharedPreferences.getString(PREF_PENDING_SOURCE, null) == desired.source
                if (pendingMatches) {
                    editor.remove(PREF_PENDING_GENERATION)
                        .remove(PREF_PENDING_SOURCE)
                }
                check(editor.commit()) { "yt-dlp installed runtime provenance was not durable" }
                true
            }
        }

    private fun performYoutubeDLUpdate(
        channel: String,
        authority: YtdlpRuntimeAuthority.Mutation,
    ): YTDLPUpdateResponse {
        authority.requireOwned()
        val result = when (channel) {
            "stable", "nightly", "master" -> {
                val res = YoutubeDL.updateYoutubeDL(context, channelMap[channel]!!)
                val version = YoutubeDL.version(context)
                if (res != YoutubeDL.UpdateStatus.DONE) {
                    YTDLPUpdateResponse(
                        YTDLPUpdateStatus.ALREADY_UP_TO_DATE,
                        "${channel}@${version}",
                    )
                } else {
                    YTDLPUpdateResponse(
                        YTDLPUpdateStatus.DONE,
                        "Updated yt-dlp to ${channel}@${version}",
                    )
                }
            }
            else -> {
                val request = YoutubeDLRequest(emptyList())
                request.addOption("--update-to", "${channel}@latest")
                val response = YoutubeDLCompat.executeUnderMutation(context, request, authority)
                customUpdateResponse(response.out)
            }
        }
        return result
    }

    internal fun customUpdateResponse(output: String): YTDLPUpdateResponse {
        val lastOutput = output.lines().last { it.isNotBlank() }
        return when {
            lastOutput.contains("ERROR") -> YTDLPUpdateResponse(YTDLPUpdateStatus.ERROR, lastOutput)
            lastOutput.contains("yt-dlp is up to date") -> YTDLPUpdateResponse(
                YTDLPUpdateStatus.ALREADY_UP_TO_DATE,
                lastOutput,
            )
            else -> YTDLPUpdateResponse(YTDLPUpdateStatus.DONE, lastOutput)
        }
    }

    companion object {
        private const val PREF_SOURCE = "ytdlp_source"
        private const val PREF_SOURCE_LABEL = "ytdlp_source_label"
        private const val PREF_DESIRED_GENERATION = "ytdlp_source_generation"
        private const val PREF_COMMITTED_GENERATION = "ytdlp_committed_source_generation"
        private const val PREF_COMMITTED_SOURCE = "ytdlp_committed_source"
        private const val PREF_COMMITTED_RESULT = "ytdlp_committed_result"
        private const val PREF_PENDING_GENERATION = "ytdlp_pending_source_generation"
        private const val PREF_PENDING_SOURCE = "ytdlp_pending_source"
        private const val PREF_PROVENANCE_EPOCH = "ytdlp_provenance_epoch"
        private const val PROVENANCE_PREFERENCES = "ytdlp_provenance_migration"
        private const val PREF_LOCAL_PROVENANCE_EPOCH = "epoch"
        private const val CURRENT_PROVENANCE_EPOCH = 1
        private const val PREF_DESIRED_GENERATION_DOMAIN = "desired_generation_domain"
        private const val CURRENT_DESIRED_GENERATION_DOMAIN = 1
        private const val DEFAULT_SOURCE = "stable"

        private val stateLock = Any()
        private val updateMutex = Mutex()
        private val admittedRequests = mutableMapOf<DesiredSource, Int>()
        private var provenanceEpochPublicationUnconfirmed = false
        private var desiredGenerationDomainPublicationUnconfirmed = false

        // Generic backup/restore publishes only the default preference graph.
        // This independent private file was never part of that legacy writer.
        internal fun destinationProvenancePreferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PROVENANCE_PREFERENCES, Context.MODE_PRIVATE)

        internal fun destinationProvenanceEpochIsCurrent(localState: SharedPreferences): Boolean =
            synchronized(stateLock) {
                !provenanceEpochPublicationUnconfirmed &&
                    localState.getInt(PREF_LOCAL_PROVENANCE_EPOCH, 0) == CURRENT_PROVENANCE_EPOCH &&
                    destinationDesiredGenerationDomainIsCurrent(localState)
            }

        internal fun destinationDesiredGenerationDomainIsCurrent(localState: SharedPreferences): Boolean =
            synchronized(stateLock) {
                !desiredGenerationDomainPublicationUnconfirmed &&
                    localState.getInt(PREF_DESIRED_GENERATION_DOMAIN, 0) == CURRENT_DESIRED_GENERATION_DOMAIN
            }

        private fun legacyDesiredGeneration(snapshot: Map<String, *>): Long =
            if (snapshot.containsKey(PREF_SOURCE) || snapshot.containsKey(PREF_DESIRED_GENERATION)) 1L else 0L

        internal fun desiredSourceGeneration(
            preferences: SharedPreferences,
            localState: SharedPreferences,
        ): Long = synchronized(stateLock) {
            if (!destinationDesiredGenerationDomainIsCurrent(localState)) {
                // Request capture may precede startup recovery/Restore readiness.
                // Observe only the generation the migration owner will publish;
                // never typed-read or trust the foreign carrier. Mutation/proof
                // admission still requires confirmed durable migration first.
                return@synchronized legacyDesiredGeneration(preferences.all)
            }
            preferences.getLong(PREF_DESIRED_GENERATION, 0L).also {
                check(it >= 0L) { "yt-dlp source generation is invalid" }
            }
        }

        // The production caller holds RestoreMutationAdmission. Preserve source
        // intent, but allocate ambiguous identity in the new generation domain.
        internal fun retirePreChangeProvenance(
            preferences: SharedPreferences,
            localState: SharedPreferences,
        ) = synchronized(stateLock) {
            val epoch = localState.getInt(PREF_LOCAL_PROVENANCE_EPOCH, 0)
            val generationDomain = localState.getInt(PREF_DESIRED_GENERATION_DOMAIN, 0)
            check(epoch in 0..CURRENT_PROVENANCE_EPOCH) { "Unsupported yt-dlp provenance epoch" }
            check(generationDomain in 0..CURRENT_DESIRED_GENERATION_DOMAIN) {
                "Unsupported yt-dlp desired generation domain"
            }
            val rebaseGeneration = generationDomain != CURRENT_DESIRED_GENERATION_DOMAIN ||
                desiredGenerationDomainPublicationUnconfirmed
            if (epoch == CURRENT_PROVENANCE_EPOCH && !provenanceEpochPublicationUnconfirmed &&
                !rebaseGeneration
            ) {
                return@synchronized
            }
            // commit() can publish its process map even when its disk write
            // fails. Keep such an epoch untrusted until a confirmed publication.
            provenanceEpochPublicationUnconfirmed = true
            if (rebaseGeneration) desiredGenerationDomainPublicationUnconfirmed = true
            // Every default-file marker, including Int 1 and incompatible
            // legacy storage types, is ambiguous. Never read it as authority.
            val snapshot = preferences.all
            val editor = preferences.edit()
                .remove(PREF_PROVENANCE_EPOCH)
                .remove(PREF_COMMITTED_GENERATION)
                .remove(PREF_COMMITTED_SOURCE)
                .remove(PREF_COMMITTED_RESULT)
                .remove(PREF_PENDING_GENERATION)
                .remove(PREF_PENDING_SOURCE)
            if (rebaseGeneration &&
                (snapshot.containsKey(PREF_SOURCE) || snapshot.containsKey(PREF_DESIRED_GENERATION))
            ) {
                editor.putLong(PREF_DESIRED_GENERATION, legacyDesiredGeneration(snapshot))
            }
            check(editor.commit()) { "Legacy yt-dlp provenance retirement was not durable" }
            // Old provenance epoch=1 did not prove generation origin. Publish
            // the new private discriminator only after rebase/retirement is
            // durable. Death or false commit replays without trusting memory.
            check(localState.edit()
                .putInt(PREF_LOCAL_PROVENANCE_EPOCH, CURRENT_PROVENANCE_EPOCH)
                .putInt(PREF_DESIRED_GENERATION_DOMAIN, CURRENT_DESIRED_GENERATION_DOMAIN)
                .commit()) {
                "yt-dlp provenance epoch publication was not durable"
            }
            provenanceEpochPublicationUnconfirmed = false
            desiredGenerationDomainPublicationUnconfirmed = false
        }

        internal fun isDestinationLocalPreferenceKey(key: String): Boolean =
            key.startsWith("ytdlp_") && key != PREF_SOURCE && key != PREF_SOURCE_LABEL

        // Callers hold RestoreMutationAdmission first. Keep the preference
        // snapshot, source reconciliation and commit atomic to updater readers.
        internal fun <T> withRestoredSourcePublication(context: Context, block: () -> T): T =
            synchronized(stateLock) {
                // Both real publishers already hold RestoreMutationAdmission.
                // Make the destination snapshot safe before reconciliation or
                // reset can copy/cast a previously imported generation.
                retirePreChangeProvenance(
                    PreferenceManager.getDefaultSharedPreferences(context),
                    destinationProvenancePreferences(context),
                )
                block()
            }

        internal fun restoredSourceGeneration(
            previousSource: String,
            previousGeneration: Long,
            restoredSource: String,
        ): Long {
            check(previousGeneration >= 0L) { "yt-dlp source generation is invalid" }
            if (previousSource == restoredSource) return previousGeneration
            check(previousGeneration < Long.MAX_VALUE) { "yt-dlp source generation exhausted" }
            return previousGeneration + 1L
        }

        internal fun reconcileRestoredSource(
            editor: SharedPreferences.Editor,
            snapshot: Map<String, *>,
            settings: List<BackupSettingsItem>,
            reset: Boolean,
        ) {
            check(Thread.holdsLock(stateLock)) { "Restored source publication is not owned" }
            val previousSource = snapshot[PREF_SOURCE] as String? ?: DEFAULT_SOURCE
            val previousGeneration = snapshot[PREF_DESIRED_GENERATION] as Long? ?: 0L
            val restoredSource = settings.lastOrNull { it.key == PREF_SOURCE }?.value
                ?: if (reset) DEFAULT_SOURCE else previousSource
            val generation = restoredSourceGeneration(previousSource, previousGeneration, restoredSource)
            // Do not materialize an absent/default source or generation on a
            // same-source restore. The existing local proof remains compatible.
            if (previousSource == restoredSource) return
            editor.putLong(PREF_DESIRED_GENERATION, generation)
                .remove(PREF_COMMITTED_GENERATION)
                .remove(PREF_COMMITTED_SOURCE)
                .remove(PREF_COMMITTED_RESULT)
                .remove(PREF_PENDING_GENERATION)
                .remove(PREF_PENDING_SOURCE)
        }

        @Volatile
        internal var updaterForTesting: (suspend (Context, String) -> YTDLPUpdateResponse)? = null

        @Volatile
        internal var updateRequestAdmittedForTesting: (() -> Unit)? = null

        var updatingApp = false
    }
}

