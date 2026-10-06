package work.art1st.javarailways.simplesignals.signal

/** Typed internal declaration identities; authored/resource identities remain unchanged. */
internal fun fixedBlockSignalDeclarationId(
    blockId: String,
    direction: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD
): String = "simplesignals:fixed-block/${direction.name.lowercase()}/${blockId.replace(':', '/')}"

internal fun protectionSignalDeclarationId(signalId: String): String =
    "simplesignals:protection-signal/${signalId.replace(':', '/')}"

internal fun blockEntryFromSignalDeclaration(declarationId: String): FixedBlockSignalEntry {
    val prefix = "simplesignals:fixed-block/"
    require(declarationId.startsWith(prefix)) { "unknown fixed-block signal declaration $declarationId" }
    val entry = declarationId.removePrefix(prefix)
    val directionSeparator = entry.indexOf('/')
    require(directionSeparator > 0) { "invalid fixed-block declaration $declarationId" }
    val direction = when (entry.substring(0, directionSeparator)) {
        "forward" -> FixedBlockEntryDirection.FORWARD
        "reverse" -> FixedBlockEntryDirection.REVERSE
        else -> throw IllegalArgumentException("unknown fixed-block entry direction $declarationId")
    }
    val encoded = entry.substring(directionSeparator + 1)
    val separator = encoded.indexOf('/')
    require(separator > 0) { "invalid fixed-block declaration $declarationId" }
    return FixedBlockSignalEntry(encoded.substring(0, separator) + ":" + encoded.substring(separator + 1), direction)
}
