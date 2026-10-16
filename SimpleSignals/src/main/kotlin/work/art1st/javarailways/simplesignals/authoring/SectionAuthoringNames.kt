package work.art1st.javarailways.simplesignals.authoring

private val uuidInAuthoringId = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** Keep authored names, but never use generated storage identities as player-facing names. */
internal fun sectionAuthoringName(id: String, fallback: String): String =
    if (uuidInAuthoringId.containsMatchIn(id)) fallback else id
