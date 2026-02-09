package work.art1st.mineRails.core.physics.rail

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurve
import work.art1st.mineRails.core.physics.train.TrainEnd
import work.art1st.mineRails.core.util.Destroyable
import work.art1st.mineRails.core.api.math.Vector3D
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 正方向：endPointA -> endpointB
 */
@Serializable(Rail.Serializer::class)
open class Rail : Destroyable {
    var id: UUID
    val world: World
    val viewAB: RailView
    val viewBA: RailView
    val assets: Collection<RailAsset>
    val signals: MutableCollection<Signal>
    val trains: CopyOnWriteArrayList<TrainEnd>

    constructor(
        id: UUID,
        world: World,
        curveAB: PrimeCurve,
        nextRailsA: List<RailView>,
        nextRailsB: List<RailView>,
        assets: Collection<RailAsset>,
        activeTrackAtA: Int = 0,
        activeTrackAtB: Int = 0,
        signals: List<Signal>? = null,
        trainEnds: List<TrainEnd>? = null
    ) {
        this.id = id
        this.world = world
        this.viewAB = RailView(
            this,
            RailView.ViewDirection.AB,
            curveAB,
            nextRailsA.toMutableList(),
            activeTrackAtB
        )
        this.viewBA = RailView(
            this,
            RailView.ViewDirection.BA,
            curveAB,
            nextRailsB.toMutableList(),
            activeTrackAtA
        )
        this.signals = signals?.toMutableSet() ?: ArrayList()
        this.trains = if (trainEnds != null) CopyOnWriteArrayList(trainEnds) else CopyOnWriteArrayList()
        this.assets = assets
    }

    constructor(
        world: World,
        curveAB: PrimeCurve,
        nextRailsA: MutableList<RailView>,
        nextRailsB: MutableList<RailView>,
        assets: Collection<RailAsset>
    ) : this(UUID.randomUUID(), world, curveAB, nextRailsA, nextRailsB, assets)

    fun getView(direction: RailView.ViewDirection): RailView {
        return when (direction) {
            RailView.ViewDirection.AB -> viewAB
            RailView.ViewDirection.BA -> viewBA
        }
    }

    fun moveTrainEndTo(x: TrainEnd, nextRail: Rail) {
        nextRail.trains.add(x)
        trains.remove(x)
    }

    /**
     * internal: 防止并发bug
     */
    internal fun removeTrainEnd(x: TrainEnd) {
        trains.remove(x)
    }

    /**
     * internal: 防止并发bug
     */
    internal fun addTrainEnd(x: TrainEnd) {
        trains.add(x)
    }

    override fun canDestroy(): Boolean = trains.isEmpty()

    fun save(): JsonElement {
        return Json.encodeToJsonElement(SavedRail(id.toString(), signals, viewAB.curve))
    }

    override fun destroy() {
        signals.forEach { s -> s.destroy() }
    }

    @Serializable
    protected data class SavedRail(
        val id: String,
        val signals: MutableCollection<Signal>,
        val curveAB: PrimeCurve,
    )

    companion object {
        fun isSamePoint(pointA: Vector3D, pointB: Vector3D): Boolean {
            return pointA.distance(pointB) < Curve.TOLERANCE
        }

        fun connect(rail1: Rail, rail2: Rail) {
            // TODO
        }
    }

    object Serializer : KSerializer<Rail> {
        override val descriptor: SerialDescriptor
            get() = TODO("Not yet implemented")

        override fun serialize(encoder: Encoder, value: Rail) {
            encoder.encodeSerializableValue(SavedRail.serializer(),
                SavedRail(value.id.toString(), value.signals, value.viewAB.curve))
        }

        override fun deserialize(decoder: Decoder): Rail {
            TODO("Not yet implemented")
        }
    }
}
