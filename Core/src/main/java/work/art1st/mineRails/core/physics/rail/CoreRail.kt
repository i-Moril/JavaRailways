package work.art1st.mineRails.core.physics.rail

import work.art1st.mineRails.core.api.physics.rail.ViewDirection
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
import work.art1st.mineRails.core.api.physics.rail.RailMetadata
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.annotation.Concurrent
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.configLib.persistence.Loads
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.jvm.Throws

/**
 * 正方向：endPointA -> endpointB
 */
class CoreRail @MainThread private constructor(
    override val id: UUID,
    override val world: CoreWorld,
    curveAB: PrimeCurve,
    @Volatile override var metadata: RailMetadata,
    nextRailsA: Collection<RailView>,
    nextRailsB: Collection<RailView>,
    activeTrackAtA: RailView? = null,
    activeTrackAtB: RailView? = null,
    val signals: MutableCollection<Signal> = CopyOnWriteArraySet(),
    // trains is maintained only on runtime
    val trains: ConcurrentLinkedQueue<TrainEnd> = ConcurrentLinkedQueue(),
    val extensionDataContainer: ExtensionDataContainer<RenderableExtension<Rail>> = ExtensionDataContainer()
) : Rail, Extendable<RenderableExtension<Rail>> by extensionDataContainer, PersistentBy<CoreRail.SavedRailV1> {
    override val curve: PrimeCurve = curveAB
    val viewAB: RailView = RailView(
        this,
        ViewDirection.AB,
        curveAB,
        nextRailsB.toMutableList(),
        activeTrackAtB,
    )
    val viewBA: RailView = RailView(
        this,
        ViewDirection.BA,
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
        nextRailsB: MutableList<RailView>,
        metadata: RailMetadata = RailMetadata()
    ) : this(UUID.randomUUID(), world, curveAB, metadata, nextRailsA, nextRailsB)

    override fun getView(direction: ViewDirection): RailView {
        return when (direction) {
            ViewDirection.AB -> viewAB
            ViewDirection.BA -> viewBA
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
            metadata,
            viewBA.nextRails.map { it.rail.id.toString() },
            viewAB.nextRails.map { it.rail.id.toString() },
            viewBA.nextActiveRail?.rail?.id?.toString(),
            viewAB.nextActiveRail?.rail?.id?.toString()
        )
    }

    @Serializable
    data class SavedRailV1(
        val id: String,
        val curveAB: PrimeCurveImpl.SavedPrimeCurveV1,
        val metadata: RailMetadata,
        val nextRailAtA: List<String>,
        val nextRailAtB: List<String>,
        val activeTrackAtA: String?,
        val activeTrackAtB: String?
    ) : Loads<CoreRail, SavedRailV1.Dependency> {
        override fun load(dependency: Dependency): CoreRail {
            val rail = loadDisconnected(dependency)
            restoreConnections(rail, dependency)
            return rail
        }

        fun loadDisconnected(dependency: Dependency): CoreRail {
            val curve = curveAB.load(Unit)
            return CoreRail(
                UUID.fromString(id),
                dependency.world,
                curve,
                metadata,
                emptyList(),
                emptyList(),
                null,
                null
            )
        }

        fun restoreConnections(rail: CoreRail, dependency: Dependency) {
            val pointA = rail.curve.getPointAt(0.0)
            val pointB = rail.curve.getPointAt(rail.curve.totalArcLength)
            val nextAtA = nextRailAtA.mapNotNull { resolveRailView(pointA, it, dependency) }
            val nextAtB = nextRailAtB.mapNotNull { resolveRailView(pointB, it, dependency) }
            rail.viewBA.activeRail = null
            rail.viewBA.mutableNextRails.clear()
            rail.viewBA.mutableNextRails.addAll(nextAtA)
            rail.viewBA.activeRail = activeTrackAtA
                ?.takeIf { nextRailAtA.contains(it) }
                ?.let { resolveRailView(pointA, it, dependency) }
                ?.takeIf { nextAtA.contains(it) }
            rail.viewAB.activeRail = null
            rail.viewAB.mutableNextRails.clear()
            rail.viewAB.mutableNextRails.addAll(nextAtB)
            rail.viewAB.activeRail = activeTrackAtB
                ?.takeIf { nextRailAtB.contains(it) }
                ?.let { resolveRailView(pointB, it, dependency) }
                ?.takeIf { nextAtB.contains(it) }
        }

        private fun resolveRailView(point: Curve.Point, railId: String, dependency: Dependency): RailView? {
            val uuid = runCatching { UUID.fromString(railId) }.getOrNull() ?: return null
            val rail = dependency.world.getRail(uuid) ?: return null
            return getDirection(point, rail)
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
