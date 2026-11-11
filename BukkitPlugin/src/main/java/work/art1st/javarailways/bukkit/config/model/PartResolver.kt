package work.art1st.javarailways.bukkit.config.model

interface PartResolver {
    fun resolve(refName: String, defaultNamespace: String): ResolvedPart
}

class SnapshotPartRegistry(
    private val parts: Map<String, PartDefinition>
) : PartResolver {
    override fun resolve(refName: String, defaultNamespace: String): ResolvedPart {
        val qualifiedName = if (refName.contains("/")) refName else "$defaultNamespace/$refName"
        parts[qualifiedName]?.let { return ResolvedPart(it.name, it, qualifiedName) }

        throw IllegalArgumentException(
            "Snapshot part not found: $qualifiedName (ref: $refName, ns: $defaultNamespace)"
        )
    }
}
