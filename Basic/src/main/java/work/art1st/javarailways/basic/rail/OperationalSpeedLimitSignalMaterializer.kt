package work.art1st.javarailways.basic.rail

import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithoutFacing

/** Rebuilds ephemeral default speed transitions from current Basic infrastructure metadata. */
object OperationalSpeedLimitSignalMaterializer {
    private const val NAMESPACE = "javarailways.speed-limit"

    fun materialize(world: CoreWorld) {
        world.allRails.forEach(::materializeRail)
    }

    fun materializeRail(rail: CoreRail) {
        removeFromRail(rail)
        val limits = rail.infrastructureMetadata.speedLimits.takeUnless { it.entries.isEmpty() }
        rail.signals += signal(rail, ViewDirection.AB, 0.0, limits)
        rail.signals += signal(rail, ViewDirection.BA, rail.curve.totalArcLength, limits)
    }

    fun removeFromRail(rail: CoreRail) {
        rail.signals.filterIsInstance<OperationalSpeedLimitSignal>().forEach { signal ->
            // This concrete signal owns only synchronous state; getNow never waits on an owner.
            signal.destroy().toCompletableFuture().getNow(null)
            rail.signals.remove(signal)
        }
    }

    private fun signal(
        rail: CoreRail,
        direction: ViewDirection,
        arcLengthInAbMeters: Double,
        limits: SpeedLimitMetadata?
    ): OperationalSpeedLimitSignal = OperationalSpeedLimitSignal(
        ref = RailObjectRef(NAMESPACE, "${rail.id}/$direction/entry"),
        location = RailLocationWithoutFacing(rail, arcLengthInAbMeters),
        appliesTo = direction,
        resultingLimits = limits
    )
}
