package work.art1st.mineRails.core.physics

import kotlinx.serialization.Serializable
import org.joml.Vector3dc
import work.art1st.mineRails.core.api.util.annotation.Heavy
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainFrameSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.configLib.persistence.Loads
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.tick.TickManager
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.di.WorldParentContext
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailSpatialIndex
import work.art1st.mineRails.core.physics.rail.SweptOccupancyIndex
import work.art1st.mineRails.core.physics.rail.SweptOccupancyQueryResult
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.physics.train.SavedTrainV1
import work.art1st.mineRails.core.physics.tick.LatestTrainFrameMailbox
import work.art1st.mineRails.core.physics.tick.PhysicsCoordinator
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 物理世界：由 [createEmpty] 或 [SavedWorldV1.load] 创建；Bukkit 等平台在创建后通过
 * [Extendable.registerExtensionData] 注册各插件的扩展数据。
 */
class CoreWorld(
    private val rails: MutableMap<UUID, CoreRail>,
    private val trains: MutableMap<String, CoreTrain>,
    private val signals: MutableMap<String, Signal>,
    override val tickManager: TickManager,
    val physicsCoordinator: PhysicsCoordinator,
    worldParent: WorldParentContext,
    private val extensionDataContainer: ExtensionDataContainer<Any> = ExtensionDataContainer()
) : World, Extendable<Any> by extensionDataContainer, PersistentBy<CoreWorld.SavedWorldV1> {
    private val railSpatialIndex = RailSpatialIndex()
    val runtimeId: String = UUID.randomUUID().toString()

    @Volatile
    private var sweptOccupancyIndex: SweptOccupancyIndex = SweptOccupancyIndex.EMPTY
    private val publishedTrainFrames = LatestTrainFrameMailbox(DEFAULT_TRAIN_FRAME_MAILBOX_CAPACITY)

    init {
        worldParent.attachWorld(this)
        physicsCoordinator.attachWorld(this)
    }

    private constructor(
        tickManager: TickManager,
        physicsCoordinator: PhysicsCoordinator,
        worldParent: WorldParentContext
    ) : this(LinkedHashMap(), ConcurrentHashMap(), hashMapOf(), tickManager, physicsCoordinator, worldParent)

    companion object {
        private const val DEFAULT_TRAIN_FRAME_MAILBOX_CAPACITY = 65_536

        fun createEmpty(
            tickManager: TickManager,
            worldParent: WorldParentContext,
            physicsCoordinator: PhysicsCoordinator = PhysicsCoordinator(1)
        ): CoreWorld = CoreWorld(tickManager, physicsCoordinator, worldParent)
    }

    @MainThread
    internal fun addTrain(train: CoreTrain) {
        trains[train.id] = train
    }

    override fun getTrain(name: String): CoreTrain? = trains[name]

    override val allTrains: Collection<CoreTrain>
        get() = trains.values

    @MainThread
    override fun removeTrain(train: Train) {
        publishedTrainFrames.remove(train.id)
        trains.remove(train.id)
    }

    @MainThread
    internal fun addRail(rail: CoreRail) {
        rails[rail.id] = rail
        railSpatialIndex.addRail(rail)
    }

    val allRails: Collection<CoreRail>
        get() = rails.values

    override fun getRail(id: UUID): CoreRail? = rails[id]

    fun getRailsNear(position: Vector3dc, radius: Double): Collection<CoreRail> =
        railSpatialIndex.query(position, radius)

    fun getRailsAlongRay(origin: Vector3dc, direction: Vector3dc, maxDistance: Double, radius: Double): Collection<CoreRail> =
        railSpatialIndex.queryAlongRay(origin, direction, maxDistance, radius)

    @OptIn(Heavy::class)
    fun getNearestRail(position: Vector3dc): CoreRail? {
        ThreadGuard.ensureOffMainThread("CoreWorld.getNearestRail")
        return rails.values.minByOrNull { rail ->
            rail.curve.getNearestPoint(position).position.distance(position)
        }
    }

    @MainThread
    override fun removeRail(rail: Rail) {
        (rail as? CoreRail)?.let { railSpatialIndex.removeRail(it) }
        rails.remove(rail.id)
    }

    /** Rebuilt by the physics Coordinator after all train states are published. */
    internal fun rebuildSweptOccupancyIndex() {
        sweptOccupancyIndex = SweptOccupancyIndex(
            trains.values.flatMap { it.publishedSweptSpans }
        )
    }

    internal fun sweptOccupancyStats(): Pair<Int, Int> =
        sweptOccupancyIndex.spanCount to sweptOccupancyIndex.bucketReferenceCount

    internal fun querySweptCollision(train: CoreTrain): SweptOccupancyQueryResult =
        sweptOccupancyIndex.queryFirstCollision(
            trainId = train.id,
            ownSpans = train.publishedSweptSpans,
            maxCandidates = SweptOccupancyIndex.DEFAULT_MAX_CANDIDATES
        )

    /** Called by the Coordinator after atomically publishing an active train frame. */
    internal fun publishTrainFrame(frame: TrainFrameSnapshot) {
        publishedTrainFrames.offer(frame)
    }

    /**
     * Drains at most one latest frame per train without executing render or platform side effects.
     * A slow consumer therefore cannot build an unbounded per-tick backlog.
     */
    fun drainPublishedTrainFrames(limit: Int = Int.MAX_VALUE): List<TrainFrameSnapshot> =
        publishedTrainFrames.drain(limit)

    fun trainFrameMailboxMetrics(): LatestTrainFrameMailbox.Metrics = publishedTrainFrames.metrics()

    override fun save(): SavedWorldV1 {
        return SavedWorldV1(
            rails.values.map { it.save() },
            trains.values.map { it.save() }
        )
    }

    @Serializable
    data class SavedWorldV1(
        val rails: List<CoreRail.SavedRailV1>,
        val trains: List<SavedTrainV1>
    ) : Loads<CoreWorld, SavedWorldV1.Dependency> {
        data class Dependency(
            val tickManager: TickManager,
            val worldParent: WorldParentContext,
            val physicsCoordinator: PhysicsCoordinator = PhysicsCoordinator(1)
        )

        override fun load(dependency: Dependency): CoreWorld {
            val world = CoreWorld(dependency.tickManager, dependency.physicsCoordinator, dependency.worldParent)
            val railDependency = CoreRail.SavedRailV1.Dependency(world)
            val loadedRails = rails.map { it.loadDisconnected(railDependency) }
            rails.zip(loadedRails).forEach { (savedRail, rail) ->
                savedRail.restoreConnections(rail, railDependency)
            }
            trains.forEach { savedTrain ->
                try {
                    world.addTrain(savedTrain.load(world))
                } catch (t: Throwable) {
                    System.err.println(
                        "[MineRails] Skipping invalid persisted train '${savedTrain.id}' while loading world: " +
                            "${t::class.simpleName}: ${t.message ?: ""}"
                    )
                }
            }
            return world
        }
    }
}
