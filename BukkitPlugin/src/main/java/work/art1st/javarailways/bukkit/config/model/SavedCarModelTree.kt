package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.TreeMap

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
            collectEntries(car.bodyParts, car.namespace, partResolver, collected)
            car.bogies.forEach { bogie ->
                collectEntries(bogie.parts, car.namespace, partResolver, collected)
                collectEntries(bogie.wheelParts, car.namespace, partResolver, collected)
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
            target: MutableMap<String, PartDefinition>
        ) {
            for (entry in entries) {
                val part = entry.inline ?: partResolver.resolve(entry.name, namespace).resolved
                val existing = target.putIfAbsent(part.name, part)
                if (existing == null) {
                    collectEntries(part.parts, part.namespace, partResolver, target)
                }
            }
        }
    }
}
