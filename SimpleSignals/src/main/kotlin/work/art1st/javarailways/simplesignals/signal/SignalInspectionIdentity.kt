package work.art1st.javarailways.simplesignals.signal

/** Typed internal declaration identities; authored/resource identities remain unchanged. */
internal fun fixedBlockSignalDeclarationId(blockId: String): String =
    "simplesignals:fixed-block/${blockId.replace(':', '/')}"

internal fun protectionSignalDeclarationId(signalId: String): String =
    "simplesignals:protection-signal/${signalId.replace(':', '/')}"

internal fun blockIdFromSignalDeclaration(declarationId: String): String {
    val prefix = "simplesignals:fixed-block/"
    require(declarationId.startsWith(prefix)) { "unknown fixed-block signal declaration $declarationId" }
    val encoded = declarationId.removePrefix(prefix)
    val separator = encoded.indexOf('/')
    require(separator > 0) { "invalid fixed-block declaration $declarationId" }
    return encoded.substring(0, separator) + ":" + encoded.substring(separator + 1)
}
