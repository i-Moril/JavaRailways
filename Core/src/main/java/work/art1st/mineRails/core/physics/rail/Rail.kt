package work.art1st.mineRails.core.physics.rail

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.joml.Vector3dc
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.train.TrainEnd
import work.art1st.mineRails.core.util.Destroyable
import work.art1st.mineRails.core.api.physics.curve.Curve.Companion.TOLERANCE
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.exception.CannotConnectRailException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.Throws

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
        activeTrackAtA: RailView? = null,
        activeTrackAtB: RailView? = null,
        signals: List<Signal>? = null,
        trainEnds: List<TrainEnd>? = null
    ) {
        this.id = id
        this.world = world
        this.viewAB = RailView(
            this,
            RailView.ViewDirection.AB,
            curveAB,
            nextRailsB.toMutableList(),
            activeTrackAtB,
        )
        this.viewBA = RailView(
            this,
            RailView.ViewDirection.BA,
            curveAB.reversed,
            nextRailsA.toMutableList(),
            activeTrackAtA
        )
        this.signals = signals?.toMutableSet() ?: ArrayList()
        this.trains = if (trainEnds != null) CopyOnWriteArrayList(trainEnds) else CopyOnWriteArrayList()
        this.assets = assets
        world.addRail(this)
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
        val curveAB: Curve,
    )

    companion object {
        fun isSamePoint(pointA: Vector3dc, pointB: Vector3dc): Boolean {
            return pointA.distance(pointB) < TOLERANCE
        }

        @Throws(CannotConnectRailException::class)
        fun connect(rail1: Rail, rail2: Rail) {
            val a1 = rail1.viewBA.endPoint.position
            val b1 = rail1.viewAB.endPoint.position
            val a2 = rail2.viewBA.endPoint.position
            val b2 = rail2.viewAB.endPoint.position
            val dist = listOf(
                a1.distance(a2),
                a1.distance(b2),
                b1.distance(a2),
                b1.distance(b2)
            )
            val i = dist.withIndex().minByOrNull { it.value }?.index
            when (i) {
                0 -> RailView.connect(rail1.viewBA, rail2.viewBA)
                1 -> RailView.connect(rail1.viewBA, rail2.viewAB)
                2 -> RailView.connect(rail1.viewAB, rail2.viewBA)
                3 -> RailView.connect(rail1.viewAB, rail2.viewAB)
            }
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
