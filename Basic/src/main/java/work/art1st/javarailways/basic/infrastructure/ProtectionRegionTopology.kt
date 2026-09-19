package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.route.ProtectionBoundaryDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionDiagnostic
import work.art1st.javarailways.basic.api.route.ProtectionRegionValidationCode
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.ArrayDeque
import java.util.UUID

internal data class ProtectionTopologyConnection(
    val railId: UUID,
    val enteringFacing: ViewDirection
)

internal data class ProtectionTopologyRail(
    val railId: UUID,
    val lengthMeters: Double,
    val connectionsFromA: List<ProtectionTopologyConnection>,
    val connectionsFromB: List<ProtectionTopologyConnection>
) {
    init {
        require(lengthMeters.isFinite() && lengthMeters > 0.0)
    }
}

internal data class ProtectionRegionTopologySnapshot(
    val topologyRevision: Long,
    val rails: Map<UUID, ProtectionTopologyRail>
)

internal sealed interface ProtectionRegionTopologyCapture {
    data class Captured(val topology: ProtectionRegionTopologySnapshot) : ProtectionRegionTopologyCapture
    data class Rejected(val diagnostic: ProtectionRegionDiagnostic) : ProtectionRegionTopologyCapture
}

internal fun interface ProtectionRegionTopologySource {
    fun capture(
        boundaries: Collection<ProtectionBoundaryDefinition>,
        expectedTopologyRevision: Long
    ): ProtectionRegionTopologyCapture
}

/** Captures one immutable connected topology snapshot from live public Rail API values. */
internal class LiveRailProtectionRegionTopologySource(
    private val currentTopologyRevision: () -> Long,
    private val railLookup: (UUID) -> Rail?
) : ProtectionRegionTopologySource {
    override fun capture(
        boundaries: Collection<ProtectionBoundaryDefinition>,
        expectedTopologyRevision: Long
    ): ProtectionRegionTopologyCapture {
        val before = currentTopologyRevision()
        if (before != expectedTopologyRevision) {
            return stale(expectedTopologyRevision, before)
        }
        val queue = ArrayDeque<UUID>()
        boundaries.mapTo(queue) { it.position.railId }
        val rails = linkedMapOf<UUID, ProtectionTopologyRail>()
        while (queue.isNotEmpty()) {
            val railId = queue.removeFirst()
            if (railId in rails) continue
            val rail = railLookup(railId) ?: return ProtectionRegionTopologyCapture.Rejected(
                ProtectionRegionDiagnostic(
                    code = ProtectionRegionValidationCode.MISSING_RAIL,
                    detail = "protection boundary references missing rail $railId",
                    railId = railId
                )
            )
            val node = rail.toTopologyRail()
            rails[railId] = node
            node.connectionsFromA.forEach { queue.addLast(it.railId) }
            node.connectionsFromB.forEach { queue.addLast(it.railId) }
        }
        val after = currentTopologyRevision()
        if (after != expectedTopologyRevision) {
            return stale(expectedTopologyRevision, after)
        }
        return ProtectionRegionTopologyCapture.Captured(
            ProtectionRegionTopologySnapshot(expectedTopologyRevision, rails.toMap())
        )
    }

    private fun Rail.toTopologyRail(): ProtectionTopologyRail = ProtectionTopologyRail(
        railId = id,
        lengthMeters = curve.totalArcLength,
        connectionsFromA = getView(ViewDirection.BA).nextRails.map { next ->
            ProtectionTopologyConnection(next.rail.id, next.viewDirection)
        },
        connectionsFromB = getView(ViewDirection.AB).nextRails.map { next ->
            ProtectionTopologyConnection(next.rail.id, next.viewDirection)
        }
    )

    private fun stale(expected: Long, actual: Long) = ProtectionRegionTopologyCapture.Rejected(
        ProtectionRegionDiagnostic(
            code = ProtectionRegionValidationCode.STALE_TOPOLOGY,
            detail = "requested topology revision $expected does not match current $actual"
        )
    )

    companion object {
        internal val BOUNDARY_TOLERANCE_METERS: Double = Curve.TOLERANCE
    }
}
