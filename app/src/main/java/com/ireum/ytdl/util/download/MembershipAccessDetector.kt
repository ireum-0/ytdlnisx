package com.ireum.ytdl.util.download

object MembershipAccessDetector {
    private val explicitAccessPhrases = listOf(
        "available to this channel's members",
        "available to members of this channel",
        "join this channel to get access",
        "join this channel to access",
        "only available to channel members",
        "only available to this channel's members",
        "requires channel membership access"
    )
    private val subscriberOnlyAvailability = Regex(
        """(?i)["']?availability["']?\s*[:=]\s*["']?subscriber_only"""
    )

    fun isMembershipRequired(text: String): Boolean {
        return subscriberOnlyAvailability.containsMatchIn(text) ||
            explicitAccessPhrases.any { phrase -> text.contains(phrase, ignoreCase = true) }
    }
}
