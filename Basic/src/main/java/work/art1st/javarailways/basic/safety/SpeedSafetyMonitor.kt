package work.art1st.javarailways.basic.safety

import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.util.annotation.MainThread

/** A simulation-owned derail request derived from one published physics epoch. */
data class DerailRequest(
    val trainId: String,
    val reason: String,
    val observedPhysicsEpoch: Long
) {
    init {
        require(trainId.isNotBlank())
        require(reason.isNotBlank())
        require(observedPhysicsEpoch >= 0L)
    }
}

/** Optional geometry-derived derail-speed enforcement over published physics state. */
class SpeedSafetyMonitor(
    private val world: CoreWorld,
    private val config: Config
) {
    data class Config(
        val derailEnabled: Boolean,
        val toleranceMetersPerSecond: Double,
        val graceTicks: Int
    ) {
        init {
            require(toleranceMetersPerSecond >= 0.0)
            require(graceTicks > 0)
        }
    }

    private val overspeedEpochs = HashMap<String, Int>()
    private var lastEvaluatedEpoch = -1L

    /**
     * Evaluates one already-published epoch. The caller owns applying returned requests.
     * Re-evaluating the same or an older epoch is a no-op; skipped epochs are not inferred.
     */
    @MainThread
    fun evaluatePublishedEpoch(physicsEpoch: Long): List<DerailRequest> {
        require(physicsEpoch >= 0L) { "physicsEpoch must be non-negative" }
        if (physicsEpoch <= lastEvaluatedEpoch) return emptyList()
        lastEvaluatedEpoch = physicsEpoch
        if (!config.derailEnabled) return emptyList()

        val liveTrainIds = HashSet<String>()
        val requests = ArrayList<DerailRequest>()
        world.allTrains.forEach { train ->
            liveTrainIds += train.id
            val frame = train.publishedFrame
            if (frame.derailed) {
                overspeedEpochs.remove(train.id)
                return@forEach
            }

            val derailSpeed = frame.sweptSpans.asSequence()
                .mapNotNull { span ->
                    world.getRail(span.railId)?.let { rail ->
                        world.basicWorldData.railMetadata.getOrDefault(rail.id)
                            .curveSafety.baseCurveDerailSpeedMetersPerSecond
                    }
                }
                .minOrNull()
                ?: run {
                    overspeedEpochs.remove(train.id)
                    return@forEach
                }
            val publishedSpeed = frame.speedMetersPerSecond
            if (publishedSpeed <= derailSpeed + config.toleranceMetersPerSecond) {
                overspeedEpochs.remove(train.id)
                return@forEach
            }

            val epochs = (overspeedEpochs[train.id] ?: 0) + 1
            if (epochs >= config.graceTicks) {
                overspeedEpochs.remove(train.id)
                requests += DerailRequest(
                    trainId = train.id,
                    reason = "Derail speed exceeded: %.2f m/s > %.2f m/s".format(publishedSpeed, derailSpeed),
                    observedPhysicsEpoch = physicsEpoch
                )
            } else {
                overspeedEpochs[train.id] = epochs
            }
        }
        overspeedEpochs.keys.retainAll(liveTrainIds)
        return requests
    }
}
