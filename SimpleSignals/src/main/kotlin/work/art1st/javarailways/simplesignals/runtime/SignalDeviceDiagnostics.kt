package work.art1st.javarailways.simplesignals.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import java.time.Instant

/** Read-only facts from one published result. Missing facts mean unknown, never clear. */
internal class SignalDeviceDiagnostics(
    val state: SectionSignalState?,
    val stamp: RailwayStateStamp? = null,
    val evaluatedAt: Instant? = null,
    /** The host does not currently supply an observation wall-clock timestamp. */
    val observedAt: Instant? = null,
    val selfOccupied: Boolean? = null,
    val selfLengthMeters: Double? = null,
    val scanDistanceMeters: Double? = null,
    forward: Collection<SignalForwardDiagnostic> = emptyList(),
    terminations: Collection<SignalScanTerminationDiagnostic> = emptyList()
) {
    val forward: List<SignalForwardDiagnostic> = java.util.List.copyOf(forward)
    val terminations: List<SignalScanTerminationDiagnostic> = java.util.List.copyOf(terminations)
}

internal data class SignalForwardDiagnostic(
    val distanceMeters: Double,
    val hasDownstreamSignal: Boolean,
    /** Null is unlimited only when [hasDownstreamSignal] is true. */
    val speedLimitMetersPerSecond: Double?,
    val branchPath: String,
    val selection: String,
    val routeReady: Boolean?
)

internal data class SignalScanTerminationDiagnostic(val reason: String, val distanceMeters: Double, val branchPath: String)

/** Only parses the queried device's already constructed input; does not scan topology or run JS. */
internal fun signalDeviceDiagnostics(
    state: SectionSignalState?,
    stamp: RailwayStateStamp?,
    evaluation: SectionSignalEvaluation? = null
): SignalDeviceDiagnostics {
    val input = evaluation?.input as? JsonObject
    val self = input?.get("self") as? JsonObject
    val forward = mutableListOf<SignalForwardDiagnostic>()
    val ends = mutableListOf<SignalScanTerminationDiagnostic>()
    fun end(node: JsonObject, path: String) {
        val termination = node["end"] as? JsonObject ?: return
        ends += SignalScanTerminationDiagnostic(
            (termination.getValue("reason") as JsonPrimitive).content,
            (termination.getValue("distanceMeters") as JsonPrimitive).doubleOrNull!!,
            path
        )
    }
    if (input != null) {
        end(input, "0")
        val pending = ArrayDeque<Pair<String, JsonObject>>()
        (input["forward"] as? JsonArray)?.forEachIndexed { index, node ->
            pending.addLast((index + 1).toString() to node as JsonObject)
        }
        while (pending.isNotEmpty()) {
            val (path, node) = pending.removeFirst()
            if (node["section"] is JsonObject) {
                val downstream = node["downstreamSignal"] as? JsonObject
                forward += SignalForwardDiagnostic(
                    (node.getValue("distanceMeters") as JsonPrimitive).doubleOrNull!!,
                    downstream != null,
                    (downstream?.get("speedLimitMetersPerSecond") as? JsonPrimitive)?.doubleOrNull,
                    path,
                    (node.getValue("selection") as JsonPrimitive).content,
                    (node["routeReady"] as? JsonPrimitive)?.booleanOrNull
                )
            }
            end(node, path)
            (node["next"] as? JsonArray)?.forEachIndexed { index, child ->
                pending.addLast("$path.${index + 1}" to child as JsonObject)
            }
        }
    }
    return SignalDeviceDiagnostics(state, evaluation?.stamp ?: stamp, evaluation?.evaluatedAt,
        selfOccupied = (self?.get("occupied") as? JsonPrimitive)?.booleanOrNull,
        selfLengthMeters = (self?.get("lengthMeters") as? JsonPrimitive)?.doubleOrNull,
        scanDistanceMeters = (input?.get("scanDistanceMeters") as? JsonPrimitive)?.doubleOrNull,
        forward = forward, terminations = ends)
}
