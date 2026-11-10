package work.art1st.javarailways.bukkit.extension.train

import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** Owner-side samples survive geometry coalescing, but never cross train publication streams. */
internal class TrainPartRenderSampleCache {
    private data class Sample(val stream: UUID, val snapshot: TrainPartRenderSnapshot)
    private val samples = mutableMapOf<String, Sample>()

    fun clear() = samples.clear()

    fun capture(
        frame: TrainFrameSnapshot, index: Int, trainType: String?, tick: Long,
        operational: OperationalPartRenderProjection, streamId: UUID,
        lines: JsonObject = JsonObject(emptyMap()),
        operatingTail: Boolean? = null,
    ): TrainPartRenderSnapshot = captureTrainPartRenderSnapshot(frame, index, trainType, tick, operational, lines, operatingTail).also {
        samples[frame.cars[index].carId] = Sample(streamId, it)
    }

    fun latest(frame: TrainFrameSnapshot, index: Int, streamId: UUID): TrainPartRenderSnapshot? =
        samples[frame.cars[index].carId]?.takeIf { it.stream == streamId }?.snapshot
}
