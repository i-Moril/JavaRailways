package work.art1st.javarailways.offline

import kotlinx.serialization.json.*
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import java.util.UUID

/** The UUID-only development shape is accepted only by this independent offline source set. */
object RailEndpointMigration {
    private val json = Json { ignoreUnknownKeys = false }
    private val legacy = setOf("nextRailAtA", "nextRailAtB", "activeTrackAtA", "activeTrackAtB")
    private val current = setOf("connectionsAtA", "connectionsAtB", "activeConnectionAtA", "activeConnectionAtB")

    fun convert(core: JsonObject, report: MutableList<String>): JsonObject {
        require(core.keys == setOf("formatVersion", "infrastructureRevision", "rails")) { "Unexpected Core infrastructure fields" }
        val version = core.getValue("formatVersion").jsonPrimitive.int
        require(version == 1 || version == 2) { "Unsupported Core infrastructure version: $version" }
        val rails = core.getValue("rails").jsonArray.map { it.jsonObject }
        if (rails.isEmpty()) return core
        val oldShape = rails.map { rail ->
            val hasOld = rail.keys.any { it in legacy }
            val hasCurrent = rail.keys.any { it in current }
            require(hasOld != hasCurrent) { "Missing or mixed rail connection shapes" }
            val fields = if (hasOld) legacy else current
            val allowed = setOf("id", "curveAB") + fields + if (version == 1) setOf("metadata") else emptySet()
            require(rail.keys == allowed) { "Unexpected rail fields: ${rail.keys}" }
            hasOld
        }
        require(oldShape.distinct().size == 1) { "Mixed rail connection shapes in Core infrastructure" }
        if (!oldShape.first()) return core // Current strict decoder and business restore still run at the caller.
        val byId = rails.associateBy { UUID.fromString(it.getValue("id").jsonPrimitive.content) }
        require(byId.size == rails.size) { "Duplicate rail identity" }
        val curves = byId.mapValues { (_, rail) ->
            json.decodeFromJsonElement<PrimeCurveImpl.SavedPrimeCurveV1>(rail.getValue("curveAB")).load(Unit)
        }
        val converted = rails.map { rail ->
            val sourceId = UUID.fromString(rail.getValue("id").jsonPrimitive.content)
            val curve = curves.getValue(sourceId)
            val fields = linkedMapOf<String, JsonElement>()
            for (end in listOf("A", "B")) {
                val position = curve.getPointAt(if (end == "A") 0.0 else curve.totalArcLength).position
                val ids = rail.getValue("nextRailAt$end").jsonArray.map { it.jsonPrimitive.content }
                val targetIds = ids.map(UUID::fromString)
                require(ids.size <= 2 && targetIds.distinct().size == targetIds.size) { "$sourceId:$end: duplicate or excessive targets" }
                val refs = ids.mapIndexed { index, id ->
                    val targetId = targetIds[index]
                    val target = requireNotNull(curves[targetId]) { "$sourceId:$end: missing target $id" }
                    val candidates = listOf("A", "B").filter { targetEnd ->
                        val targetPosition = target.getPointAt(if (targetEnd == "A") 0.0 else target.totalArcLength).position
                        position.distance(targetPosition) <= Curve.TOLERANCE
                    }
                    require(candidates.size == 1) { "$sourceId:$end -> $id: expected one endpoint within ${Curve.TOLERANCE}, got $candidates" }
                    val targetEnd = candidates.single()
                    require(sourceId != targetId || end != targetEnd) { "$sourceId:$end: same-end self reference" }
                    report += "$sourceId:$end -> $targetId:$targetEnd"
                    buildJsonObject { put("railId", id); put("endpoint", targetEnd) }
                }
                val active = rail.getValue("activeTrackAt$end")
                val selected = if (active == JsonNull) JsonNull else {
                    val index = targetIds.indexOf(UUID.fromString(active.jsonPrimitive.content))
                    require(index >= 0) { "$sourceId:$end: active target is not connected" }
                    refs[index]
                }
                fields["connectionsAt$end"] = JsonArray(refs)
                fields["activeConnectionAt$end"] = selected
            }
            JsonObject(rail.filterKeys { it !in legacy } + fields)
        }
        return JsonObject(core + ("rails" to JsonArray(converted)))
    }
}
