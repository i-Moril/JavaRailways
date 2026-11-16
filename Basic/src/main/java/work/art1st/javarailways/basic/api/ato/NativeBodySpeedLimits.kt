package work.art1st.javarailways.basic.api.ato

import java.util.UUID
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/**
 * Queries native limits on every distinct rail covered by the supplied published conservative body;
 * a limit remains present until that body's tail clears its rail. First-party query, without an
 * independent third-party compatibility promise. No registration, mutation or persistence change.
 *
 * Uses [frame] as supplied, then reads current rail metadata through [train]'s world. It does not
 * capture a new frame, freeze metadata or validate world/epoch currentness. Call within the world's
 * allowed simulation/physics read context; the caller owns frame and world lifecycle consistency.
 * Work is proportional to body segments plus distinct covered-rail metadata lookups, with no scan
 * of unrelated rails. The returned map follows first body-occurrence order and omits unlimited rails.
 *
 * @param train train whose world supplies live rail metadata.
 * @param frame published body traversal for that train, including physical operating facing.
 * @return freshly calculated rail UUID to effective native speed limit in m/s.
 * @throws IllegalStateException if train ID differs, the frame is derailed or has no body traversal,
 * or any covered rail is absent. Existing metadata-access failures propagate unchanged.
 */
fun nativeBodySpeedLimits(train: Train, frame: TrainFrameSnapshot): Map<UUID, Double> {
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
