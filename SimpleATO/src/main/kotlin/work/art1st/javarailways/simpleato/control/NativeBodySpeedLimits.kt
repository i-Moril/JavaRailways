package work.art1st.javarailways.simpleato.control

import java.util.UUID
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/** Native rail limits remain current until the published conservative tail clears the rail. */
internal fun nativeBodySpeedLimits(train: Train, frame: TrainFrameSnapshot): Map<UUID, Double> {
    check(frame.trainId == train.id && !frame.derailed && frame.bodyTraversal.isNotEmpty()) {
        "native speed limits require a valid published body for ${train.id}"
    }
    return buildMap {
        frame.bodyTraversal.map { it.railId }.distinct().forEach { railId ->
            val rail = checkNotNull(train.world.getRail(railId)) { "occupied rail $railId is missing" }
            rail.infrastructureMetadata.speedLimits.effectiveLimitMetersPerSecond()?.let { put(railId, it) }
        }
    }
}
