package com.v2ray.ang.core

/**
 * Keeps the user-defined profile order stable while a connection attempt falls back.
 *
 * The plan deliberately contains no Android state, which makes it safe to test and prevents
 * list refreshes during a connection attempt from silently changing the next profile.
 */
class ConnectionFallbackPlan private constructor(
    private val candidates: List<Candidate>,
) {
    data class Candidate(
        val guid: String,
        val delayMillis: Long,
        val automaticEligible: Boolean = true,
    )

    var position: Int = 0
        private set

    var attemptNumber: Int = 1
        private set

    val size: Int
        get() = candidates.size

    val currentGuid: String
        get() = candidates[position].guid


    fun advance(): String? {
        for (nextPosition in position + 1 until candidates.size) {
            position = nextPosition
            attemptNumber += 1
            return currentGuid
        }
        return null
    }

    companion object {
        fun create(orderedGuids: List<String>): ConnectionFallbackPlan? {
            val candidates = orderedGuids
                .filter(String::isNotBlank)
                .distinct()
                .map { guid -> Candidate(guid = guid, delayMillis = 0L) }
            return candidates.takeIf(List<Candidate>::isNotEmpty)?.let(::ConnectionFallbackPlan)
        }

        /**
         * Places successful latency measurements first, followed by untested profiles and then
         * known failures. Equal states retain the user's displayed order.
         */
        fun createFastest(orderedCandidates: List<Candidate>): ConnectionFallbackPlan? {
            val candidates = orderedCandidates
                .filter(Candidate::automaticEligible)
                .withIndex()
                .sortedWith(
                    compareBy<IndexedValue<Candidate>> { candidateRank(it.value.delayMillis) }
                        .thenBy {
                            it.value.delayMillis.takeIf { delay -> delay > 0L } ?: Long.MAX_VALUE
                        }
                        .thenBy { it.index },
                )
                .map(IndexedValue<Candidate>::value)
                .distinctBy(Candidate::guid)
            return candidates.takeIf(List<Candidate>::isNotEmpty)?.let(::ConnectionFallbackPlan)
        }

        private fun candidateRank(delayMillis: Long): Int = when {
            delayMillis > 0L -> 0
            delayMillis == 0L -> 1
            else -> 2
        }
    }
}
