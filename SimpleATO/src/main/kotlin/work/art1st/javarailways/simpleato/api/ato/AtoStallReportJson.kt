package work.art1st.javarailways.simpleato.api.ato

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RailObservationSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailObjectSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.constraint.AtoConstraint
import work.art1st.javarailways.simpleato.api.constraint.CurrentSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.DepartureHoldConstraint
import work.art1st.javarailways.simpleato.api.constraint.FollowConstraint
import work.art1st.javarailways.simpleato.api.constraint.MovementAuthorityConstraint
import work.art1st.javarailways.simpleato.api.constraint.RouteSpeedLimitConstraint
import work.art1st.javarailways.simpleato.api.constraint.StopConstraint

/** Stable, structured diagnostic representation written when a world-local ATO Stall occurs. */
object AtoStallReportJson {
    private val json = Json { prettyPrint = true }

    fun encode(report: AtoStallReport): String =
        json.encodeToString(JsonObject.serializer(), report.toJson())

    private fun AtoStallReport.toJson(): JsonObject = JsonObject(
        mapOf(
            "schemaVersion" to JsonPrimitive(3),
            "occurredAtEpochMillis" to JsonPrimitive(occurredAtEpochMillis),
            "worldId" to JsonPrimitive(worldId),
            "trainId" to JsonPrimitive(trainId),
            "atoTick" to JsonPrimitive(tick),
            "error" to JsonObject(
                mapOf(
                    "type" to JsonPrimitive(errorType),
                    "message" to nullableString(message),
                    "stackTrace" to JsonPrimitive(stackTrace)
                )
            ),
            "initialTrain" to (initialTrain?.toJson(0L) ?: JsonNull),
            "currentTrain" to (currentTrain?.toJson(tick) ?: JsonNull),
            "route" to (route?.toJson() ?: JsonNull),
            "perception" to (perception?.toJson() ?: JsonNull),
            "constraints" to JsonArray(
                constraints.entries
                    .sortedWith(compareBy({ it.key.type.name }, { it.key.id }))
                    .map { (owner, values) ->
                        JsonObject(
                            mapOf(
                                "ownerType" to JsonPrimitive(owner.type.name),
                                "ownerId" to JsonPrimitive(owner.id),
                                "values" to JsonArray(values.map { it.toJson() })
                            )
                        )
                    }
            )
        )
    )

    private fun TrainFrameSnapshot.toJson(contextRevision: Long): JsonObject = JsonObject(
        mapOf(
            "trainId" to JsonPrimitive(trainId),
            "revision" to JsonPrimitive(contextRevision),
            "speedMetersPerSecond" to JsonPrimitive(speedMetersPerSecond),
            "derailed" to JsonPrimitive(derailed),
            "carCount" to JsonPrimitive(cars.size),
            "head" to head.toJson(),
            "tail" to tail.toJson()
        )
    )

    private fun RailPositionSnapshot.toJson(): JsonObject = JsonObject(
        mapOf(
            "railId" to JsonPrimitive(railId.toString()),
            "facing" to JsonPrimitive(facing.name),
            "arcLengthInAbMeters" to JsonPrimitive(arcLengthInAbMeters)
        )
    )

    private fun RouteSnapshot.toJson(): JsonObject = JsonObject(
        mapOf(
            "routeId" to JsonPrimitive(routeId),
            "revision" to JsonPrimitive(revision),
            "topologyRevision" to JsonPrimitive(topologyRevision),
            "segments" to JsonArray(segments.map { segment ->
                JsonObject(
                    mapOf(
                        "railId" to JsonPrimitive(segment.railId.toString()),
                        "facing" to JsonPrimitive(segment.facing.name),
                        "startArcLengthMeters" to JsonPrimitive(segment.startArcLengthMeters),
                        "endArcLengthMeters" to JsonPrimitive(segment.endArcLengthMeters)
                    )
                )
            })
        )
    )

    private fun PerceptionSnapshot.toJson(): JsonObject = JsonObject(
        mapOf(
            "epoch" to JsonPrimitive(epoch),
            "trainRevision" to JsonPrimitive(epoch),
            "routeId" to JsonPrimitive(route.routeId),
            "routeRevision" to JsonPrimitive(route.revision),
            "observations" to JsonArray(observations.map { it.toJson() })
        )
    )

    private fun RailObservationSnapshot.toJson(): JsonObject = JsonObject(
        mapOf(
            "subject" to subject.toJson(),
            "transition" to JsonPrimitive(transition.name),
            "routeDistanceMeters" to JsonPrimitive(routeDistanceMeters),
            "relativeSpeedMetersPerSecond" to (relativeSpeedMetersPerSecond?.let(::JsonPrimitive) ?: JsonNull)
        )
    )

    private fun RailObjectSnapshot.toJson(): JsonObject = JsonObject(
        mapOf(
            "ref" to JsonPrimitive(ref.toString()),
            "lifecycleState" to JsonPrimitive(lifecycleState.name),
            "position" to position.toJson()
        )
    )

    private fun AtoConstraint.toJson(): JsonObject {
        val details = when (this) {
            is CurrentSpeedLimitConstraint -> mapOf(
                "maximumSpeedMetersPerSecond" to JsonPrimitive(maximumSpeedMetersPerSecond)
            )
            is RouteSpeedLimitConstraint -> mapOf(
                "distanceToStartMeters" to JsonPrimitive(distanceToStartMeters),
                "maximumSpeedMetersPerSecond" to JsonPrimitive(maximumSpeedMetersPerSecond)
            )
            is StopConstraint -> mapOf(
                "remainingDistanceMeters" to JsonPrimitive(remainingDistanceMeters)
            )
            is FollowConstraint -> mapOf(
                "gapMeters" to JsonPrimitive(gapMeters),
                "leadingSpeedMetersPerSecond" to JsonPrimitive(leadingSpeedMetersPerSecond)
            )
            is MovementAuthorityConstraint -> mapOf(
                "remainingDistanceMeters" to JsonPrimitive(remainingDistanceMeters),
                "stoppingMarginMeters" to (stoppingMarginMeters?.let(::JsonPrimitive) ?: JsonNull)
            )
            is DepartureHoldConstraint -> emptyMap()
        }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive(this::class.simpleName ?: "AtoConstraint"),
                "keyNamespace" to JsonPrimitive(key.namespace),
                "keyValue" to JsonPrimitive(key.value)
            ) + details
        )
    }

    private fun nullableString(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
}
