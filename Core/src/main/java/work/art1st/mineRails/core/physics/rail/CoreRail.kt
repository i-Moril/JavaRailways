package work.art1st.mineRails.core.physics.rail

import kotlinx.serialization.Serializable
import org.joml.Vector3dc
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.physics.train.TrainEnd
import work.art1st.mineRails.core.api.physics.curve.Curve.Companion.TOLERANCE
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.exception.CannotConnectRailException
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveImpl
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.annotation.Concurrent
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.configLib.persistence.Loads
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.jvm.Throws

/**
 * 正方向：endPointA -> endpointB
 */
class CoreRail @MainThread private constructor(
    override val id: UUID,
    override val world: CoreWorld,
    curveAB: PrimeCurve,
    nextRailsA: Collection<RailView>,
    nextRailsB: Collection<RailView>,
    activeTrackAtA: RailView? = null,
    activeTrackAtB: RailView? = null,
    val signals: MutableCollection<Signal> = mutableSetOf(),
    // trains is maintained only on runtime
    val trains: ConcurrentLinkedQueue<TrainEnd> = ConcurrentLinkedQueue(),
    val extensionDataContainer: ExtensionDataContainer<RenderableExtension<Rail>> = ExtensionDataContainer()
) : Rail, Extendable<RenderableExtension<Rail>> by extensionDataContainer, PersistentBy<CoreRail.SavedRailV1> {
    override val curve: PrimeCurve = curveAB
    val viewAB: RailView = RailView(
        this,
        RailView.ViewDirection.AB,
        curveAB,
        nextRailsB.toMutableList(),
        activeTrackAtB,
    )
    val viewBA: RailView = RailView(
        this,
        RailView.ViewDirection.BA,
        curveAB.reversed,
        nextRailsA.toMutableList(),
        activeTrackAtA
    )

    init {
        world.addRail(this)
    }

    constructor(
        world: CoreWorld,
        curveAB: PrimeCurve,
        nextRailsA: MutableList<RailView>,
        nextRailsB: MutableList<RailView>
    ) : this(UUID.randomUUID(), world, curveAB, nextRailsA, nextRailsB)

    fun getView(direction: RailView.ViewDirection): RailView {
        return when (direction) {
            RailView.ViewDirection.AB -> viewAB
            RailView.ViewDirection.BA -> viewBA
        }
    }

    internal fun moveTrainEndTo(x: TrainEnd, nextRail: CoreRail) {
        nextRail.trains.add(x)
        trains.remove(x)
    }

    /**
     * internal: 防止并发bug
     */
    @MainThread
    internal fun removeTrainEnd(x: TrainEnd) {
        trains.remove(x)
    }

    /**
     * internal: 防止并发bug
     */
    @MainThread
    internal fun addTrainEnd(x: TrainEnd) {
        trains.add(x)
    }

    override fun canDestroy(): Boolean = trains.isEmpty()

    override fun destroy() {
        signals.forEach { s -> s.destroy() }
    }

    override fun save(): SavedRailV1 {
        val curveSnapshot = viewAB.curve.save() as PrimeCurveImpl.SavedPrimeCurveV1
        return SavedRailV1(
            id.toString(),
            curveSnapshot,
            viewAB.nextRails.map { it.rail.id.toString() },
            viewBA.nextRails.map { it.rail.id.toString() },
            viewAB.nextActiveRail?.rail?.id?.toString(),
            viewBA.nextActiveRail?.rail?.id?.toString()
        )
    }

    @Serializable
    data class SavedRailV1(
        val id: String,
        val curveAB: PrimeCurveImpl.SavedPrimeCurveV1,
        val nextRailAtA: List<String>,
        val nextRailAtB: List<String>,
        val activeTrackAtA: String?,
        val activeTrackAtB: String?
    ) : Loads<CoreRail, SavedRailV1.Dependency> {
        override fun load(dependency: Dependency): CoreRail {
            val curve = curveAB.load(Unit)
            return CoreRail(
                UUID.fromString(id),
                dependency.world,
                curve,
                nextRailAtA.map {
                    getDirection(curve.getPointAt(0.0),
                        dependency.world.getRail(UUID.fromString(it))!!)
                },
                nextRailAtB.map {
                    getDirection(curve.getPointAt(curve.totalArcLength),
                        dependency.world.getRail(UUID.fromString(it))!!)
                },
                activeTrackAtA?.let {
                    if (!nextRailAtA.contains(it)) throw CannotConnectRailException()
                    getDirection(curve.getPointAt(0.0),
                        dependency.world.getRail(UUID.fromString(it))!!)
                },
                activeTrackAtB?.let {
                    if (!nextRailAtB.contains(it)) throw CannotConnectRailException()
                    getDirection(curve.getPointAt(curve.totalArcLength),
                        dependency.world.getRail(UUID.fromString(it))!!)
                }
            )
        }

        // 获取在端点处连接的RailView
        fun getDirection(point: Curve.Point, nextRail: CoreRail) : RailView {
            val distA = nextRail.viewAB.startPoint.position.distance(point.position)
            val distB = nextRail.viewAB.endPoint.position.distance(point.position)
            if (distA < distB) return nextRail.viewAB
            return nextRail.viewBA
        }

        data class Dependency(
            val world: CoreWorld
        )
    }

    companion object {
        fun isSamePoint(pointA: Vector3dc, pointB: Vector3dc): Boolean {
            return pointA.distance(pointB) < TOLERANCE
        }

        @Throws(CannotConnectRailException::class)
        fun connect(rail1: CoreRail, rail2: CoreRail) {
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
}
