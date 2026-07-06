package work.art1st.mineRails.bukkit.config.model

interface PartResolver {
    fun resolve(refName: String, defaultNamespace: String): ResolvedPart
}

class SnapshotPartRegistry(
    private val parts: Map<String, PartDefinition>
) : PartResolver {
    override fun resolve(refName: String, defaultNamespace: String): ResolvedPart {
        val qualifiedName = if (refName.contains("/")) refName else "$defaultNamespace/$refName"
        parts[qualifiedName]?.let { return ResolvedPart(it.name, it, qualifiedName) }

        val slashIdx = refName.indexOf('/')
        if (slashIdx > 0) {
            val partName = refName.substring(slashIdx + 1)
            val packageName = refName.substring(0, slashIdx)
            val fallbackName = "$packageName/$partName"
            parts[fallbackName]?.let { return ResolvedPart(it.name, it, fallbackName) }
        }

        throw IllegalArgumentException(
            "Snapshot part not found: $qualifiedName (ref: $refName, ns: $defaultNamespace)"
        )
    }
}
