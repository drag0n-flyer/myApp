package org.example.app.core.chats

/** One answer of a poll. [id] is 1..8 characters and unique within the poll. */
data class PollOption(val id: String, val text: String)

/**
 * The votes on a poll as they stand now. [counts] and [voters] hold every option (0 and empty when nobody chose it);
 * [voters] lists account IDs in the order the votes were given. [mine] is what this account chose. [voterCount] is the
 * number of people who voted (a person who picked two answers counts once).
 */
data class PollTally(
    val counts: Map<String, Int>,
    val voters: Map<String, List<String>>,
    val mine: Set<String>,
    val voterCount: Int,
) {
    companion object {
        val Empty = PollTally(emptyMap(), emptyMap(), emptySet(), 0)
    }
}

object PollRules {
    const val QUESTION_MAX = 300
    const val OPTION_TEXT_MAX = 100
    const val OPTION_ID_MAX = 8
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 12

    private fun len(s: String) = s.codePointCount(0, s.length)

    fun questionValid(question: String) = len(question) in 1..QUESTION_MAX && question.isNotBlank()

    fun optionValid(option: PollOption) =
        len(option.id) in 1..OPTION_ID_MAX && len(option.text) in 1..OPTION_TEXT_MAX && option.text.isNotBlank()

    /** True for a poll the wire format allows: valid question, 2..12 valid options with unique IDs. */
    fun valid(question: String, options: List<PollOption>): Boolean =
        questionValid(question) && options.size in MIN_OPTIONS..MAX_OPTIONS && options.all(::optionValid) &&
            options.map { it.id }.toSet().size == options.size

    /** The options a person typed, trimmed, blanks dropped, given IDs `o1`, `o2`...; null when the poll would not be valid. */
    fun build(question: String, texts: List<String>): Pair<String, List<PollOption>>? {
        val q = question.trim()
        val options = texts.map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, t -> PollOption("o${i + 1}", t) }
        return if (valid(q, options)) q to options else null
    }

    /** Ignores IDs the poll does not have and repeats; a single-choice poll keeps only the first of what is left. */
    fun normalize(options: List<PollOption>, multi: Boolean, ids: List<String>): List<String> {
        val known = options.map { it.id }.toSet()
        val kept = ids.filter { it in known }.distinct()
        return if (multi) kept else kept.take(1)
    }

    /**
     * [votes] holds each voter's latest choice (account ID to option IDs, in vote order). Invalid IDs are ignored,
     * a single-choice poll counts the first one, and a vote with nothing left counts as no vote.
     */
    fun tally(options: List<PollOption>, multi: Boolean, votes: Map<String, List<String>>, me: String): PollTally {
        val voters = options.associate { it.id to mutableListOf<String>() }
        var count = 0
        var mine: Set<String> = emptySet()
        for ((account, raw) in votes) {
            val ids = normalize(options, multi, raw)
            if (ids.isEmpty()) continue
            count++
            for (id in ids) voters.getValue(id) += account
            if (account == me) mine = ids.toSet()
        }
        return PollTally(voters.mapValues { it.value.size }, voters, mine, count)
    }

    /** What my vote becomes when [optionId] is tapped: a multiple-answer poll toggles it, a single-answer poll switches to it or takes it back. */
    fun toggle(mine: Set<String>, optionId: String, multi: Boolean): List<String> = when {
        optionId in mine -> (mine - optionId).toList()
        multi -> mine.toList() + optionId
        else -> listOf(optionId)
    }
}
