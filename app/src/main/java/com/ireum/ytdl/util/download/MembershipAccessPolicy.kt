package com.ireum.ytdl.util.download

data class MembershipAccessDecision(
    val waitForAutomaticRetry: Boolean,
    val showFirstWaitingNotification: Boolean
)

object MembershipAccessPolicy {
    fun decide(observeSourceId: Long, previousIssueCode: String): MembershipAccessDecision {
        val automatic = observeSourceId > 0L
        return MembershipAccessDecision(
            waitForAutomaticRetry = automatic,
            showFirstWaitingNotification =
                automatic && previousIssueCode != DownloadIssueCode.MEMBERSHIP_REQUIRED.name
        )
    }
}
