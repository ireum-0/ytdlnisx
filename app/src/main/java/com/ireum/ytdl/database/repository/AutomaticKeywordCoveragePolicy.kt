package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.models.observeSources.ObservationPurposes

object AutomaticKeywordCoveragePolicy {
    fun needsManagedSource(conditionKey: String, activePublicConditionKeys: Set<String>): Boolean =
        conditionKey !in activePublicConditionKeys

    fun isDiscoveryOnly(source: ObserveSourcesItem): Boolean =
        source.observationPurpose == ObservationPurposes.KEYWORD_DISCOVERY

    fun mayQueueDownloads(observationPurpose: String): Boolean =
        observationPurpose != ObservationPurposes.KEYWORD_DISCOVERY
}
