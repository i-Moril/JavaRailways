package work.art1st.javarailways.basic.safety

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PublishedTrainFrameProcessor
import work.art1st.javarailways.core.physics.train.CoreTrain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** A physics-owned derail request derived from one published physics epoch. */
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

/** Evaluates immutable published train frames on physics workers; callers only apply requests. */
class SpeedSafetyMonitor(
    private val world: CoreWorld,
    private val config: Config
) : PublishedTrainFrameProcessor {
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

    private data class TrainState(val lastEpoch: Long, val consecutiveOverspeedEpochs: Int)

    private val trainStates = ConcurrentHashMap<String, TrainState>()
    private val requests = ConcurrentLinkedQueue<DerailRequest>()

    override fun process(train: CoreTrain, physicsEpoch: Long) {
        require(physicsEpoch >= 0L)
        trainStates.compute(train.id) { _, previous ->
            if (previous != null && physicsEpoch <= previous.lastEpoch) return@compute previous
            evaluate(train, physicsEpoch, previous?.consecutiveOverspeedEpochs ?: 0)
        }
    }

    override fun completeEpoch(liveTrainIds: Set<String>, physicsEpoch: Long) {
        trainStates.keys.retainAll(liveTrainIds)
    }

    fun drainRequests(throughPhysicsEpoch: Long): List<DerailRequest> {
        val ready = ArrayList<DerailRequest>()
        val deferred = ArrayList<DerailRequest>()
        while (true) {
            val request = requests.poll() ?: break
            if (request.observedPhysicsEpoch <= throughPhysicsEpoch) ready += request else deferred += request
        }
        requests.addAll(deferred)
        return ready
    }

    private fun evaluate(train: CoreTrain, epoch: Long, previousCount: Int): TrainState {
        val frame = train.publishedFrame
        if (!config.derailEnabled || frame.derailed) return TrainState(epoch, 0)
        val derailSpeed = frame.sweptSpans.asSequence()
            .mapNotNull { span -> effectiveCurveDerailSpeed(train.id, span.railId) }
            .minOrNull()
            ?: return TrainState(epoch, 0)
        if (frame.speedMetersPerSecond <= derailSpeed + config.toleranceMetersPerSecond) {
            return TrainState(epoch, 0)
        }
        val count = previousCount + 1
        if (count < config.graceTicks) return TrainState(epoch, count)
        requests += DerailRequest(
            trainId = train.id,
            reason = "Derail speed exceeded: %.2f m/s > %.2f m/s".format(
                frame.speedMetersPerSecond,
                derailSpeed
            ),
            observedPhysicsEpoch = epoch
        )
        return TrainState(epoch, 0)
    }

    private fun effectiveCurveDerailSpeed(trainId: String, railId: java.util.UUID): Double? {
        if (world.getRail(railId) == null) return null
        val metadata = world.basicWorldData.railMetadata.getOrDefault(railId)
        val base = metadata.curveSafety.baseCurveDerailSpeedMetersPerSecond ?: return null
        val offset = world.basicWorldData.trainSpeedProfiles.getOrDefault(trainId)
            .curveSpeedLimitOffsetMetersPerSecond
        val adjusted = (base + offset).coerceAtLeast(0.0)
        return metadata.speedLimits.value(SpeedLimitType.SUBGRADE)?.let { minOf(adjusted, it) } ?: adjusted
    }
}
