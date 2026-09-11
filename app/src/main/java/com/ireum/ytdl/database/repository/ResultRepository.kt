package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.preference.PreferenceManager
import com.ireum.ytdl.database.dao.CommandTemplateDao
import com.ireum.ytdl.database.dao.ResultDao
import com.ireum.ytdl.database.models.ChapterItem
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.viewmodel.ResultViewModel
import com.ireum.ytdl.util.Extensions.getIDFromYoutubeURL
import com.ireum.ytdl.util.Extensions.isYoutubeChannelURL
import com.ireum.ytdl.util.Extensions.isYoutubeURL
import com.ireum.ytdl.util.Extensions.isYoutubeWatchVideosURL
import com.ireum.ytdl.util.DownloadMetadataEnrichmentPolicy
import com.ireum.ytdl.util.ExtractorSourceIdentity
import com.ireum.ytdl.util.ExtractorSourceIdentityPolicy
import com.ireum.ytdl.util.LinkUtil
import com.ireum.ytdl.util.MediaPublishedDate
import com.ireum.ytdl.util.MetadataEnrichmentResolver
import com.ireum.ytdl.util.SourceSnapshot
import com.ireum.ytdl.util.SourceSnapshotAuthority
import com.ireum.ytdl.util.WebUrlInput
import com.ireum.ytdl.util.extractors.GoogleApiUtil
import com.ireum.ytdl.util.extractors.newpipe.NewPipeUtil
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.ireum.ytdl.util.extractors.YoutubeApiUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ResultRepository(private val resultDao: ResultDao, private val commandTemplateDao: CommandTemplateDao, private val context: Context) {
    val YTDLNIS_SEARCH = "YTDLNIS_SEARCH"
    val allResults : Flow<List<ResultItem>> = resultDao.getResults()
    var itemCount = MutableStateFlow(-1)

    fun getFiltered(playlistName : String = "") : List<ResultItem> {
        return resultDao.getResultsWithPlaylistName(playlistName)
    }

    private val youtubeApiUtil = YoutubeApiUtil(context)
    private val ytdlpUtil = YTDLPUtil(context, commandTemplateDao)
    private var newPipeUtil = NewPipeUtil(context)
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    enum class SourceType {
        YOUTUBE_VIDEO,
        YOUTUBE_WATCHVIDEOS,
        YOUTUBE_PLAYLIST,
        YOUTUBE_CHANNEL,
        SEARCH_QUERY,
        YT_DLP
    }

    enum class DownloadMetadataLookupOrder {
        FRESH_FIRST,
        CACHE_FIRST,
    }

    private fun isUsingNewPipeExtractorDataFetching() = sharedPreferences.getString("youtube_data_fetching_extractor", "NEWPIPE") == "NEWPIPE"

    private suspend fun insertAndSetIds(items: List<ResultItem>) {
        if (items.isEmpty()) return
        val ids = resultDao.insertMultiple(items)
        ids.forEachIndexed { index, id ->
            items[index].id = id
        }
    }

    suspend fun insert(it: ResultItem){
        resultDao.insert(it)
    }

    fun getFirstResult() : ResultItem{
        return resultDao.getFirstResult()
    }

    suspend fun getHomeRecommendations(){
        deleteAll()
        val category = sharedPreferences.getString("recommendations_home", "")
        val items = when(category) {
            "newpipe" -> newPipeUtil.getTrending()
            "yt_api" -> youtubeApiUtil.getTrending()
            "yt_dlp_watch_later" -> ytdlpUtil.getYoutubeWatchLater()
            "yt_dlp_recommendations" -> ytdlpUtil.getYoutubeRecommendations()
            "yt_dlp_liked" -> ytdlpUtil.getYoutubeLikedVideos()
            "yt_dlp_watch_history" -> ytdlpUtil.getYoutubeWatchHistory()
            "custom" -> {
                val customURL = sharedPreferences.getString("custom_home_recommendation_url", "")
                if (customURL.isNullOrBlank()) arrayListOf()
                else ytdlpUtil.getFromYTDL(customURL, resultsGenerated = {})
            }
            else -> arrayListOf()
        }

        itemCount.value = items.size
        resultDao.insertMultiple(items)
    }

    fun getSearchSuggestions(searchQuery: String) : ArrayList<String> {
        return GoogleApiUtil.getSearchSuggestions(searchQuery)
    }

    fun getStreamingUrlAndChapters(url: String) : Pair<List<String>, List<ChapterItem>?> {
//        val newPipeTrial = if (isUsingNewPipeExtractorDataFetching()) {
//            newPipeUtil.getStreamingUrlAndChapters(url)
//        }else {
//            Result.failure(Throwable())
//        }
//        if (newPipeTrial.isFailure){
//            val res = ytdlpUtil.getStreamingUrlAndChapters(url)
//            return res.getOrDefault(Pair(listOf(""), null))
//        }

        val result = ytdlpUtil.getStreamingUrlAndChapters(url)
            .getOrDefault(Pair(emptyList(), null))
        return Pair(result.first.filter { it.isNotBlank() }, result.second)
    }

    suspend fun search(inputQuery: String, resetResults: Boolean, addToResults: Boolean) : List<ResultItem>{
        if (resetResults) deleteAll()
        if (WebUrlInput.routeInput(inputQuery) is WebUrlInput.InputRoute.UnsupportedExplicitScheme) {
            return emptyList()
        }
        val useLanguageForMetadata = sharedPreferences.getBoolean("use_app_language_for_metadata", true)
        val apiKey = sharedPreferences.getString("api_key", "") ?: ""
        val lang = resolveLanguage()
        val region = resolveRegion(lang)
        if (useLanguageForMetadata && apiKey.isNotBlank() && lang != "en") {
            val apiItems = runCatching {
                youtubeApiUtil.searchVideos(inputQuery, lang, region)
            }.getOrDefault(arrayListOf())
            if (youtubeApiUtil.wasQuotaExceeded()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context,
                        com.ireum.ytdl.R.string.api_quota_exceeded_fallback,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            if (apiItems.isNotEmpty()) {
                android.util.Log.d("ResultRepository", "search using YouTube API query=$inputQuery lang=$lang region=$region")
                itemCount.value = apiItems.size
                if (addToResults) {
                    val ids = resultDao.insertMultiple(apiItems)
                    ids.forEachIndexed { index, id ->
                        apiItems[index].id = id
                    }
                }
                return apiItems
            }
        }

        val res = when(sharedPreferences.getString("search_engine", "ytsearch")) {
            "ytsearch" -> newPipeUtil.search(inputQuery)
            "ytsearchmusic" -> newPipeUtil.searchMusic(inputQuery)
            else -> Result.failure(Throwable())
        }

        val items = if (res.isSuccess && res.getOrNull().orEmpty().isNotEmpty()) {
            android.util.Log.d("ResultRepository", "search using NewPipe success query=$inputQuery")
            res.getOrNull()!!
        }else{
            android.util.Log.d("ResultRepository", "search fallback to yt-dlp query=$inputQuery")
            //fallback to yt-dlp
            ytdlpUtil.getFromYTDL(inputQuery, resultsGenerated = {})
        }

        itemCount.value = items.size
        if (addToResults){
            val ids = resultDao.insertMultiple(items)
            ids.forEachIndexed { index, id ->
                items[index].id = id
            }
        }
        return items
    }

    private fun resolveLanguage(): String {
        val pref = sharedPreferences.getString("app_language", "") ?: ""
        return if (pref.isBlank() || pref == "system") {
            java.util.Locale.getDefault().language.ifBlank { "en" }
        } else {
            pref
        }
    }

    private fun resolveRegion(language: String): String {
        val pref = sharedPreferences.getString("locale", "") ?: ""
        if (pref.isNotBlank()) return pref
        if (language == "ko") return "KR"
        return java.util.Locale.getDefault().country.ifBlank { "US" }
    }

    private suspend fun getYoutubeWatchVideos(inputQuery: String, resetResults: Boolean, addToResults: Boolean) : List<ResultItem> {
        if (resetResults) deleteAll()

        //throw YoutubeDLException("Youtube Watch Videos is not yet supported in data fetching. You can download it directly by clicking Continue Anyway or by Quick Downloading it!")
        val items = mutableListOf<ResultItem>()
        val newpipeExtractorResult = if (isUsingNewPipeExtractorDataFetching()) {
            newPipeUtil.getPlaylistData(inputQuery) {
                items.addAll(it)
            }
        }else {
            Result.failure(Throwable())
        }

        val response = if (newpipeExtractorResult.isSuccess && newpipeExtractorResult.getOrNull().orEmpty().isNotEmpty()){
            newpipeExtractorResult.getOrElse { items }
        }else{
            val res = ytdlpUtil.getFromYTDL(inputQuery, resultsGenerated = {})
            res
        }

        itemCount.value = response.size
        if (addToResults) {
            insertAndSetIds(response)
        }
        return response
    }

    private suspend fun getYoutubeVideo(inputQuery: String, resetResults: Boolean, addToResults: Boolean) : List<ResultItem>{
        val theURL = inputQuery.replace("\\?list.*".toRegex(), "")
        val newpipeExtractorResult = if (isUsingNewPipeExtractorDataFetching()) {
            newPipeUtil.getVideoData(theURL)
        }else {
            Result.failure(Throwable())
        }

        val res = if (newpipeExtractorResult.isSuccess && newpipeExtractorResult.getOrNull().orEmpty().isNotEmpty()) {
            newpipeExtractorResult.getOrNull()!!
        }else{
            val youtubeID = inputQuery.getIDFromYoutubeURL()
            val url = if (youtubeID == null) {
                inputQuery
            } else {
                "https://youtu.be/${youtubeID}"
            }

            ytdlpUtil.getFromYTDL( url, resultsGenerated = {})
        }

        if (resetResults) {
            deleteAll()
            itemCount.value = res.size
        }else{
            res.filter { it.playlistTitle.isBlank() }.forEach { it.playlistTitle = YTDLNIS_SEARCH }
        }
        if (addToResults){
            val ids = resultDao.insertMultiple(res)
            ids.forEachIndexed { index, id ->
                res[index].id = id
            }
        }
        return res
    }

    private suspend fun getYoutubePlaylist(inputQuery: String, resetResults: Boolean, addToResults: Boolean) : List<ResultItem>{
        val id = inputQuery.split("list=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1].split("&").first()
        val playlistURL = "https://youtube.com/playlist?list=${id}"
        if (resetResults) deleteAll()
        val items = mutableListOf<ResultItem>()
        val ytExtractorResult = if (isUsingNewPipeExtractorDataFetching()){
            newPipeUtil.getPlaylistData(playlistURL) {
                items.addAll(it)
            }
        }else {
            Result.failure(Throwable())
        }

        val finalResults = mutableListOf<ResultItem>()
        if (ytExtractorResult.isSuccess && ytExtractorResult.getOrNull().orEmpty().isNotEmpty()) {
            ytExtractorResult.getOrElse { items }.apply {
                finalResults.addAll(this)
                itemCount.value = this.size
            }
        }else {
            var itemCounts = 0
            ytdlpUtil.getFromYTDL(inputQuery) {
                finalResults.addAll(it)
                itemCounts+=it.size
                itemCount.value = itemCounts
            }
        }

        if (addToResults) {
            insertAndSetIds(finalResults)
        }
        return finalResults
    }

    private suspend fun getYoutubeChannel(url: String, resetResults: Boolean, addToResults: Boolean) : List<ResultItem>{
        if (resetResults) deleteAll()
        val items = mutableListOf<ResultItem>()
        val ytExtractorResult = if (isUsingNewPipeExtractorDataFetching()) {
            newPipeUtil.getChannelData(url) {
                items.addAll(it)
            }
        }else {
            Result.failure(Throwable())
        }

        val finalResults = mutableListOf<ResultItem>()
        if (ytExtractorResult.isSuccess && ytExtractorResult.getOrNull().orEmpty().isNotEmpty()) {
            ytExtractorResult.getOrElse { items }.apply {
                finalResults.addAll(this)
                itemCount.value = this.size
            }
        }else {
            var itemCounts = 0
            ytdlpUtil.getFromYTDL(url) {
                finalResults.addAll(it)
                itemCounts+=it.size
                itemCount.value = itemCounts
            }
        }

        if (addToResults) {
            insertAndSetIds(finalResults)
        }
        return finalResults
    }

    private suspend fun getFromYTDLP(inputQuery: String, resetResults: Boolean, addToResults: Boolean, singleItem: Boolean = false) : List<ResultItem> {
        if (resetResults) {
            deleteAll()
        }

        var itemsCount = 0
        val itemsToReturn = mutableListOf<ResultItem>()

        ytdlpUtil.getFromYTDL(inputQuery, singleItem) { results ->
            if (resetResults) {
                itemsCount += results.size
                itemCount.value = itemsCount
            }
            results.filter { it.playlistTitle.isBlank() }.forEach { it.playlistTitle = YTDLNIS_SEARCH }
            itemsToReturn.addAll(results)
        }

        if (addToResults) {
            insertAndSetIds(itemsToReturn)
        }
        return itemsToReturn
    }

    fun getFormats(url: String, source : String? = null) : List<Format> {
        val formatSource = source ?: sharedPreferences.getString("formats_source", "yt-dlp")
        val res = if (url.isYoutubeURL()) {
            when(formatSource) {
                "newpipe" -> {
                    val tmpRes = NewPipeUtil(context).getFormats(url)
                    if (tmpRes.isFailure && source != null) {
                        Result.success(listOf())
                    }else{
                        tmpRes
                    }
                }
                else -> Result.failure(Throwable())
            }
        }else{
            Result.failure(Throwable())
        }

        return if (res.isSuccess){
            res.getOrNull()!!
        }else{
            ytdlpUtil.getFormats(url)
        }
    }

    suspend fun getFormatsMultiple(urls: List<String>, source: String? = null, progress: (progress: ResultViewModel.MultipleFormatProgress) -> Unit) : MutableList<MutableList<Format>> {
        val formatSource = source ?: sharedPreferences.getString("formats_source", "yt-dlp")
        val allYoutubeLinks = urls.all { it.isYoutubeURL() }

        val res = when(formatSource) {
            "newpipe" -> {
                if (!allYoutubeLinks) {
                    Result.failure(Throwable())
                }else{
                    val res = NewPipeUtil(context).getFormatsForAll(urls) {
                        progress(it)
                    }
                    res
                }

            }
            else -> {
                Result.failure(Throwable())
            }
        }

        if (res.isSuccess) {
            return res.getOrElse { mutableListOf() }
        }

        //last fallback
        val ytdlpRes = ytdlpUtil.getFormatsForAll(urls) {
            progress(it)
        }

        return ytdlpRes.getOrElse { mutableListOf() }
    }

    suspend fun delete(item: ResultItem){
        resultDao.delete(item.id)
    }

    suspend fun deleteByUrl(url: String) {
        resultDao.deleteByUrl(url)
    }

    suspend fun deleteAll(){
        itemCount.value = 0
        resultDao.deleteAll()
    }

    suspend fun update(item: ResultItem){
        resultDao.update(item)
    }

    fun getItemByID(id: Long) : ResultItem? {
        return resultDao.getResultByID(id)
    }

    fun getItemByURL(url: String): ResultItem? {
        return resultDao.getResultByURL(url)
    }

    fun getAllByURL(url: String) : List<ResultItem> {
        return resultDao.getAllByURL(url)
    }

    fun getAllByIDs(ids: List<Long>) : List<ResultItem> {
        return resultDao.getAllByIDs(ids)
    }

    fun updateID(id: Long, newID: Long) {
        resultDao.updateID(id, newID)
    }

    suspend fun getResultsFromSource(inputQuery: String, resetResults: Boolean, addToResults: Boolean = true, singleItem: Boolean = false) : List<ResultItem> {
        if (WebUrlInput.routeInput(inputQuery) is WebUrlInput.InputRoute.UnsupportedExplicitScheme) {
            if (resetResults) deleteAll()
            return emptyList()
        }
        return when(getQueryType(inputQuery)){
            SourceType.YOUTUBE_VIDEO -> {
                getYoutubeVideo(inputQuery, resetResults, addToResults)
            }
            SourceType.YOUTUBE_WATCHVIDEOS -> {
                getYoutubeWatchVideos(inputQuery, resetResults, addToResults)
            }
            SourceType.YOUTUBE_PLAYLIST -> {
                if (singleItem){
                    getFromYTDLP(inputQuery, resetResults, addToResults, true)
                }else{
                    getYoutubePlaylist(inputQuery, resetResults, addToResults)
                }
            }
            SourceType.YOUTUBE_CHANNEL -> {
                if (singleItem) {
                    getFromYTDLP(inputQuery, resetResults, addToResults, true)
                }else{
                    getYoutubeChannel(inputQuery, resetResults, addToResults)
                }
            }
            SourceType.SEARCH_QUERY -> {
                search(inputQuery, resetResults, addToResults)
            }
            SourceType.YT_DLP -> {
                getFromYTDLP(inputQuery, resetResults, addToResults, singleItem)
            }
        }

    }

    /**
     * Source extraction with completeness authority preserved for consumers
     * that make membership/absence decisions.  The existing List-returning
     * API remains for positive-item callers; Observe must use this typed
     * boundary so a partial extractor response cannot authorize deletion.
     */
    suspend fun getSourceSnapshotFromSource(
        inputQuery: String,
        resetResults: Boolean,
        addToResults: Boolean = true,
        singleItem: Boolean = false,
    ): SourceSnapshot {
        if (resetResults) deleteAll()
        val snapshot = try {
            if (WebUrlInput.routeInput(inputQuery) is WebUrlInput.InputRoute.UnsupportedExplicitScheme) {
                SourceSnapshot.failed(
                    diagnostic = "Unsupported source scheme",
                )
            } else {
                when (getQueryType(inputQuery)) {
                    SourceType.YOUTUBE_VIDEO -> getYoutubeVideoSnapshot(inputQuery)
                    SourceType.YOUTUBE_WATCHVIDEOS -> getYoutubeWatchVideosSnapshot(inputQuery)
                    SourceType.YOUTUBE_PLAYLIST -> {
                        if (singleItem) {
                            getFromYTDLPSnapshot(inputQuery, singleItem = true)
                        } else {
                            getYoutubePlaylistSnapshot(inputQuery)
                        }
                    }
                    SourceType.YOUTUBE_CHANNEL -> {
                        if (singleItem) {
                            getFromYTDLPSnapshot(inputQuery, singleItem = true)
                        } else {
                            getYoutubeChannelSnapshot(inputQuery)
                        }
                    }
                    SourceType.SEARCH_QUERY -> {
                        SourceSnapshot.partial(
                            search(inputQuery, resetResults = false, addToResults = false),
                            diagnostic = "Search extraction has no source-membership completeness proof",
                        )
                    }
                    SourceType.YT_DLP -> getFromYTDLPSnapshot(inputQuery, singleItem)
                }
            }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SourceSnapshot.failed(error, "Source extraction failed")
        }

        itemCount.value = snapshot.items.size
        if (addToResults && snapshot.items.isNotEmpty()) {
            insertAndSetIds(snapshot.items)
        }
        return snapshot
    }

    private fun getFromYTDLPSnapshot(
        inputQuery: String,
        singleItem: Boolean,
    ): SourceSnapshot {
        var itemCountFromCallback = 0
        val snapshot = ytdlpUtil.getFromYTDLSnapshot(inputQuery, singleItem) { results ->
            itemCountFromCallback += results.size
            itemCount.value = itemCountFromCallback
        }
        snapshot.items
            .filter { it.playlistTitle.isBlank() }
            .forEach { it.playlistTitle = YTDLNIS_SEARCH }
        return snapshot
    }

    private fun getYoutubeVideoSnapshot(inputQuery: String): SourceSnapshot {
        val theURL = inputQuery.replace("\\?list.*".toRegex(), "")
        val newpipeExtractorResult = if (isUsingNewPipeExtractorDataFetching()) {
            newPipeUtil.getVideoData(theURL)
        } else {
            Result.failure(Throwable("NewPipe disabled"))
        }
        return if (newpipeExtractorResult.isSuccess) {
            SourceSnapshot.authoritative(newpipeExtractorResult.getOrNull().orEmpty())
        } else {
            val youtubeID = inputQuery.getIDFromYoutubeURL()
            val url = if (youtubeID == null) inputQuery else "https://youtu.be/${youtubeID}"
            getFromYTDLPSnapshot(url, singleItem = true)
        }
    }

    private fun getYoutubeWatchVideosSnapshot(inputQuery: String): SourceSnapshot {
        var fetchedItems = 0
        val newpipeExtractorResult = if (isUsingNewPipeExtractorDataFetching()) {
            newPipeUtil.getPlaylistSnapshotData(inputQuery) { page ->
                fetchedItems += page.size
                itemCount.value = fetchedItems
            }
        } else {
            SourceSnapshot.failed(diagnostic = "NewPipe disabled")
        }
        return SourceSnapshotAuthority.preserveOrFallback(newpipeExtractorResult) {
            getFromYTDLPSnapshot(inputQuery, singleItem = false)
        }
    }

    private fun getYoutubePlaylistSnapshot(inputQuery: String): SourceSnapshot {
        val playlistId = inputQuery.substringAfter("list=", "").substringBefore("&")
        if (playlistId.isBlank()) {
            return SourceSnapshot.failed(diagnostic = "YouTube playlist has no list identity")
        }
        val playlistURL = "https://youtube.com/playlist?list=${playlistId}"
        var fetchedItems = 0
        val newpipeExtractorResult = if (isUsingNewPipeExtractorDataFetching()) {
            newPipeUtil.getPlaylistSnapshotData(playlistURL) { page ->
                fetchedItems += page.size
                itemCount.value = fetchedItems
            }
        } else {
            SourceSnapshot.failed(diagnostic = "NewPipe disabled")
        }
        return SourceSnapshotAuthority.preserveOrFallback(newpipeExtractorResult) {
            getFromYTDLPSnapshot(inputQuery, singleItem = false)
        }
    }

    private fun getYoutubeChannelSnapshot(inputQuery: String): SourceSnapshot {
        var fetchedItems = 0
        val newpipeExtractorResult = if (isUsingNewPipeExtractorDataFetching()) {
            newPipeUtil.getChannelSnapshotData(inputQuery) { page ->
                fetchedItems += page.size
                itemCount.value = fetchedItems
            }
        } else {
            SourceSnapshot.failed(diagnostic = "NewPipe disabled")
        }
        return SourceSnapshotAuthority.preserveOrFallback(newpipeExtractorResult) {
            getFromYTDLPSnapshot(inputQuery, singleItem = false)
        }
    }

    suspend fun getSingleMetadataFromSource(inputQuery: String): ResultItem? {
        if (inputQuery.isBlank()) return null
        if (WebUrlInput.routeInput(inputQuery) is WebUrlInput.InputRoute.UnsupportedExplicitScheme) {
            return null
        }
        return MetadataEnrichmentResolver.resolveFreshFirst(
            loadFresh = { fetchSingleMetadataFromSource(inputQuery) },
            loadCached = { loadCachedMetadata(inputQuery) },
            isUsable = ResultMetadataMergePolicy::isUsable,
            needsFallback = ResultMetadataMergePolicy::needsFallback,
            merge = ResultMetadataMergePolicy::merge,
        )
    }

    private suspend fun fetchSingleMetadataFromSource(inputQuery: String): ResultItem? {
        val testLoader = ResultRepositoryMetadataTestHooks.freshMetadataForTesting
        val fetched = if (testLoader != null) {
            testLoader(inputQuery)
        } else {
            getResultsFromSource(
                inputQuery,
                resetResults = false,
                addToResults = false,
                singleItem = true
            ).firstOrNull()
        }
        if (fetched != null && !matchesRequestedMetadataSource(inputQuery, fetched)) {
            android.util.Log.w(
                "ResultRepository",
                "Ignoring fresh metadata whose source does not match the requested URL"
            )
            return null
        }
        return fetched
    }

    private suspend fun loadCachedMetadata(inputQuery: String): ResultItem? {
        val testLoader = ResultRepositoryMetadataTestHooks.cachedMetadataForTesting
        return if (testLoader != null) {
            testLoader(inputQuery)
        } else {
            ytdlpUtil.getCachedInfoJsonResultOrThrow(inputQuery)
        }
    }

    private fun matchesRequestedMetadataSource(
        requestedSource: String,
        candidate: ResultItem,
    ): Boolean {
        val sourceIdentity = candidate.sourceIdentity ?: ExtractorSourceIdentity(
            canonicalUrl = candidate.url.trim(),
        )
        return ExtractorSourceIdentityPolicy.matchesRequestedSource(
            requestedSource = requestedSource.trim(),
            identity = sourceIdentity,
        )
    }

    suspend fun getSingleMetadataFromUrl(inputUrl: String): ResultItem? {
        val normalizedUrl = inputUrl.trim()
        if (!LinkUtil.isExtractorInput(normalizedUrl)) return null

        val fetched = getSingleMetadataFromSource(normalizedUrl) ?: return null
        if (!matchesRequestedMetadataSource(normalizedUrl, fetched)) {
            android.util.Log.w(
                "ResultRepository",
                "Ignoring metadata whose source does not match the requested URL"
            )
            return null
        }
        return fetched
    }

    private fun getQueryType(inputQuery: String) : SourceType {
        val extractorInput = when (val route = WebUrlInput.routeInput(inputQuery)) {
            is WebUrlInput.InputRoute.Extractor -> route.input.dispatchValue
            WebUrlInput.InputRoute.SearchQuery -> return SourceType.SEARCH_QUERY
            is WebUrlInput.InputRoute.UnsupportedExplicitScheme -> return SourceType.SEARCH_QUERY
        }
        var type = SourceType.SEARCH_QUERY
        if (extractorInput.isYoutubeURL()) {
            type = SourceType.YOUTUBE_VIDEO
            if (extractorInput.contains("playlist?list=")) {
                type = SourceType.YOUTUBE_PLAYLIST
            }else if (extractorInput.isYoutubeChannelURL()) {
                type = SourceType.YOUTUBE_CHANNEL
            }else if (extractorInput.isYoutubeWatchVideosURL()) {
                type = SourceType.YOUTUBE_WATCHVIDEOS
            }
        } else {
            type = SourceType.YT_DLP
        }
        return type
    }

    suspend fun updateDownloadItem(
        downloadItem: DownloadItem,
        lookupOrder: DownloadMetadataLookupOrder = DownloadMetadataLookupOrder.FRESH_FIRST,
    ) : DownloadItem? {
        if (!DownloadMetadataEnrichmentPolicy.shouldEnrich(downloadItem)) return null

        val changed = when (lookupOrder) {
            DownloadMetadataLookupOrder.FRESH_FIRST -> {
                val info = getSingleMetadataFromSource(downloadItem.url) ?: return null
                applyMetadata(downloadItem, info)
            }
            DownloadMetadataLookupOrder.CACHE_FIRST -> {
                MetadataEnrichmentResolver.enrichCacheFirst(
                    loadCached = {
                        loadCachedMetadata(downloadItem.url)
                    },
                    loadFresh = {
                        fetchSingleMetadataFromSource(downloadItem.url)
                    },
                    applyMetadata = { info -> applyMetadata(downloadItem, info) },
                    isComplete = { !DownloadMetadataEnrichmentPolicy.shouldEnrich(downloadItem) },
                )
            }
        }
        return downloadItem.takeIf { changed }
    }

    private fun applyMetadata(downloadItem: DownloadItem, info: ResultItem): Boolean {
        var changed = false
        if (downloadItem.title.isBlank() && info.title.isNotBlank()) {
            downloadItem.title = info.title
            changed = true
        }
        if (downloadItem.author.isBlank() && info.author.isNotBlank()) {
            downloadItem.author = info.author
            changed = true
        }
        if (
            downloadItem.playlistTitle.isNotBlank() &&
            downloadItem.playlistTitle != YTDLNIS_SEARCH &&
            info.playlistTitle.isNotBlank() &&
            downloadItem.playlistTitle != info.playlistTitle
        ) {
            downloadItem.playlistTitle = info.playlistTitle
            changed = true
        }
        if (info.duration.isNotBlank() && downloadItem.duration != info.duration) {
            downloadItem.duration = info.duration
            changed = true
        }
        if (info.website.isNotBlank() && downloadItem.website != info.website) {
            downloadItem.website = info.website
            changed = true
        }
        if (downloadItem.thumb.isBlank() && info.thumb.isNotBlank()) {
            downloadItem.thumb = info.thumb
            changed = true
        }
        if (
            MediaPublishedDate.isPresent(info.mediaPublishedAt) &&
            downloadItem.mediaPublishedAt != info.mediaPublishedAt
        ) {
            downloadItem.mediaPublishedAt = info.mediaPublishedAt
            changed = true
        }
        return changed
    }

}

/** Null-default seam for deterministic metadata-enrichment production tests. */
internal object ResultRepositoryMetadataTestHooks {
    var freshMetadataForTesting: (suspend (String) -> ResultItem?)? = null
    var cachedMetadataForTesting: (suspend (String) -> ResultItem?)? = null

    fun clearForTesting() {
        freshMetadataForTesting = null
        cachedMetadataForTesting = null
    }
}
