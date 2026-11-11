package work.art1st.javarailways.offline

import kotlinx.serialization.json.*
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.rail.RailEndpointGeometry
import work.art1st.javarailways.core.physics.rail.SavedRailEndpointRef
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import java.util.UUID

/** Old endpoint associations are accepted only by the independent offline migration source set. */
object RailJunctionMigration {
    private val json = Json { ignoreUnknownKeys = false }
    private val connectionFields = setOf("connectionsAtA", "connectionsAtB", "activeConnectionAtA", "activeConnectionAtB")
    private val membershipFields = setOf("joinedEndpointsAtA", "joinedEndpointsAtB")
    private val order = compareBy<RailEndpointRef>({ it.railId.toString() }, { it.endpoint.name })

    fun convert(core: JsonObject, report: MutableList<String>): JsonObject {
        require(core.keys == setOf("formatVersion", "infrastructureRevision", "rails")) { "Unexpected Core infrastructure fields" }
        val version = core.getValue("formatVersion").jsonPrimitive.int
        require(version == 1 || version == 2) { "Unsupported Core infrastructure version: $version" }
        val rails = core.getValue("rails").jsonArray.map { it.jsonObject }
        if (rails.isEmpty()) return core
        val hasMembership = rails.map { rail ->
            val fields = rail.keys.intersect(membershipFields)
            require(fields.isEmpty() || fields == membershipFields) { "Incomplete rail junction membership fields" }
            val expected = setOf("id", "curveAB") + connectionFields + fields +
                if (version == 1) setOf("metadata") else emptySet()
            require(rail.keys == expected) { "Unexpected rail fields: ${rail.keys}" }
            fields.isNotEmpty()
        }
        require(hasMembership.distinct().size == 1) { "Mixed rail junction membership shapes" }
        if (hasMembership.first()) return core // The caller still strictly decodes and restores current input.

        val byId = rails.associateBy { UUID.fromString(it.getValue("id").jsonPrimitive.content) }
        require(byId.size == rails.size) { "Duplicate rail identity" }
        val curves = byId.mapValues { (_, rail) ->
            json.decodeFromJsonElement<PrimeCurveImpl.SavedPrimeCurveV1>(rail.getValue("curveAB")).load(Unit)
        }
        val connections = linkedMapOf<RailEndpointRef, List<RailEndpointRef>>()
        val active = linkedMapOf<RailEndpointRef, RailEndpointRef?>()
        fun decode(value: JsonElement): RailEndpointRef {
            val saved = json.decodeFromJsonElement<SavedRailEndpointRef>(value)
            val id = UUID.fromString(saved.railId)
            require(id in byId) { "Junction reference contains missing rail $id" }
            return RailEndpointRef(id, saved.endpoint)
        }
        byId.forEach { (id, rail) ->
            RailEndpoint.entries.forEach { endpoint ->
                val source = RailEndpointRef(id, endpoint)
                val targets = rail.getValue("connectionsAt$endpoint").jsonArray.map(::decode)
                require(targets.size <= 2 && targets.distinct().size == targets.size && source !in targets) {
                    "Invalid legacy connections at $source"
                }
                connections[source] = targets
                active[source] = rail.getValue("activeConnectionAt$endpoint").let { value ->
                    if (value == JsonNull) null else decode(value).also { selected ->
                        require(selected in targets) { "Legacy active endpoint is not connected at $source: $selected" }
                    }
                }
            }
        }
        connections.forEach { (source, targets) -> targets.forEach { target ->
            require(source in connections.getValue(target)) { "Legacy connection is not symmetric: $source <-> $target" }
        } }

        val memberships = linkedMapOf<RailEndpointRef, List<RailEndpointRef>>()
        val legal = linkedMapOf<RailEndpointRef, Set<RailEndpointRef>>()
        val visited = hashSetOf<RailEndpointRef>()
        var groups = 0
        var incomplete = 0
        connections.keys.sortedWith(order).forEach { start ->
            if (!visited.add(start)) return@forEach
            val pending = ArrayDeque<RailEndpointRef>().apply { add(start) }
            val members = mutableListOf<RailEndpointRef>()
            while (pending.isNotEmpty()) {
                val member = pending.removeFirst()
                members += member
                connections.getValue(member).forEach { if (visited.add(it)) pending.addLast(it) }
            }
            members.sortWith(order)
            val derived = RailEndpointGeometry.legalContinuations(members, curves::getValue)
            legal.putAll(derived)
            members.forEach { source -> memberships[source] = members.filter { it != source } }
            if (members.size > 1) {
                groups++
                if (derived.values.all { it.isEmpty() }) incomplete++
                report += "junction members=${members.joinToString()} legalDirectedPaths=${derived.values.sumOf { it.size }}"
            }
        }

        var removed = 0
        var added = 0
        var repairedActive = 0
        val converted = byId.map { (id, rail) ->
            val fields = linkedMapOf<String, JsonElement>()
            RailEndpoint.entries.forEach { endpoint ->
                val source = RailEndpointRef(id, endpoint)
                val before = connections.getValue(source)
                val allowed = legal.getValue(source)
                // Preserve the original order of surviving paths, then append newly derived paths deterministically.
                val after = before.filter { it in allowed } + allowed.filter { it !in before }
                before.filter { it !in allowed }.forEach { target ->
                    removed++
                    report += "removed illegal path $source -> $target"
                }
                after.filter { it !in before }.forEach { target ->
                    added++
                    report += "added legal path $source -> $target"
                }
                val previousActive = active.getValue(source)
                val selected = when {
                    previousActive == null || previousActive in allowed -> previousActive
                    after.isEmpty() -> null
                    after.size == 1 -> after.single()
                    else -> error("Ambiguous active repair at $source: previous=$previousActive, legal=$after")
                }
                if (selected != previousActive) {
                    repairedActive++
                    report += "repaired active $source: $previousActive -> $selected"
                }
                fields["joinedEndpointsAt$endpoint"] = JsonArray(memberships.getValue(source).map(::encode))
                fields["connectionsAt$endpoint"] = JsonArray(after.map(::encode))
                fields["activeConnectionAt$endpoint"] = selected?.let(::encode) ?: JsonNull
            }
            JsonObject(rail + fields)
        }
        report += "junction migration groups=$groups incompleteGroups=$incomplete removedDirectedPaths=$removed addedDirectedPaths=$added repairedActives=$repairedActive"
        return JsonObject(core + ("rails" to JsonArray(converted)))
    }

    private fun encode(endpoint: RailEndpointRef): JsonElement = json.encodeToJsonElement(
        SavedRailEndpointRef(endpoint.railId.toString(), endpoint.endpoint)
    )
}
