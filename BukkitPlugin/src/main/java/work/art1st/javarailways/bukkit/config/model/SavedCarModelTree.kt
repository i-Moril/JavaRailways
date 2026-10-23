package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.TreeMap
import java.util.Collections
import java.util.IdentityHashMap

@Serializable
data class SavedCarModelTree(
    val hash: String,
    val car: CarDefinition,
    val parts: Map<String, PartDefinition>
) {
    fun partResolver(): PartResolver = SnapshotPartRegistry(parts)

    companion object {
        private val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        fun build(car: CarDefinition, partResolver: PartResolver): SavedCarModelTree {
            val collected = TreeMap<String, PartDefinition>()
            val active = mutableListOf<PartDefinition>()
            val visited = Collections.newSetFromMap(IdentityHashMap<PartDefinition, Boolean>())
            collectEntries(car.bodyParts, car.namespace, partResolver, collected, active, visited)
            car.bogies.forEach { bogie ->
                collectEntries(bogie.parts, car.namespace, partResolver, collected, active, visited)
                collectEntries(bogie.wheelParts, car.namespace, partResolver, collected, active, visited)
            }
            val parts = collected.toMap()
            val hash = computeHash(car, parts)
            return SavedCarModelTree(hash = hash, car = car, parts = parts)
        }

        fun computeHash(car: CarDefinition, parts: Map<String, PartDefinition>): String {
            val canonical = SavedCarModelTree(
                hash = "",
                car = car,
                parts = TreeMap(parts).toMap()
            )
            val bytes = json.encodeToString(canonical).toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun collectEntries(
            entries: List<PartRefEntry>,
            namespace: String,
            partResolver: PartResolver,
            target: MutableMap<String, PartDefinition>,
            active: MutableList<PartDefinition>,
            visited: MutableSet<PartDefinition>
        ) {
            for (entry in entries) {
                val part = entry.inline ?: partResolver.resolve(entry.name, namespace).resolved
                require(active.none { it === part }) {
                    "Cyclic model part reference: ${(active.dropWhile { it !== part }.map { it.name } + part.name).joinToString(" -> ")}"
                }
                target.putIfAbsent(part.name, part)
                // An inline definition may reuse a registered name without being the same
                // definition. Snapshot keys must not suppress validation of that subtree.
                if (visited.add(part)) {
                    active.add(part)
                    try {
                        collectEntries(part.parts, part.namespace, partResolver, target, active, visited)
                    } finally {
                        active.removeAt(active.lastIndex)
                    }
                }
            }
        }
    }
}
