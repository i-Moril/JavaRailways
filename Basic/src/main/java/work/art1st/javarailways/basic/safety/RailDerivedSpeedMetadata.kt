package work.art1st.javarailways.basic.safety

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignalMaterializer
import work.art1st.javarailways.basic.rail.CurveSafetyMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.CoreWorld

/** Rebuilds derived snapshots from authoring inputs, never from a previous automatic result. */
object RailDerivedSpeedMetadata {
    @Heavy
    fun refresh(
        metadata: RailInfrastructureMetadata,
        curve: PrimeCurve,
        physics: RailSpeedPhysics,
        derailLateralAccelerationMetersPerSecondSquared: Double
    ): RailInfrastructureMetadata {
        val authoring = metadata.authoring ?: return metadata
        val geometry = RailSpeedCalculator.calculate(
            curve, Double.MAX_VALUE, authoring.geometry.allowableCantDeficiencyMeters,
            derailLateralAccelerationMetersPerSecondSquared, physics
        )
        val limits = if (authoring.curveSpeedLimitAutomatic) {
            SpeedLimitMetadata(buildList {
                addAll(metadata.speedLimits.entries.filterNot { it.type == SpeedLimitType.CURVE })
                if (geometry.derailSpeedMetersPerSecond != null) {
                    add(SpeedLimitEntry(geometry.operatingLimitMetersPerSecond, SpeedLimitType.CURVE))
                }
            })
        } else metadata.speedLimits
        return metadata.copy(
            speedLimits = limits,
            curveSafety = CurveSafetyMetadata(geometry.derailSpeedMetersPerSecond)
        )
    }

    /** Startup only: refresh all metadata before publishing the corresponding speed signals. */
    @Heavy
    fun refreshWorld(
        world: CoreWorld,
        physics: RailSpeedPhysics,
        derailLateralAccelerationMetersPerSecondSquared: Double
    ) {
        val store = world.basicWorldData.railMetadata
        world.allRails.forEach { rail ->
            val metadata = store[rail.id] ?: return@forEach
            store.put(rail.id, refresh(metadata, rail.curve, physics, derailLateralAccelerationMetersPerSecondSquared))
        }
        OperationalSpeedLimitSignalMaterializer.materialize(world)
    }
}
