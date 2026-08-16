package work.art1st.javarailways.bukkit.tool

/** Extract a concise root-cause message suitable for showing to a player. */
internal fun railWandFailureReason(failure: Throwable): String {
    val chain = generateSequence(failure) { current ->
        current.cause?.takeUnless { it === current }
    }.toList()
    val source = chain.asReversed().firstOrNull { !it.message.isNullOrBlank() } ?: chain.last()
    val reason = source.message
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: source::class.simpleName
        ?: "未知错误"
    return reason.take(MAX_PLAYER_REASON_LENGTH)
}

private const val MAX_PLAYER_REASON_LENGTH = 240
