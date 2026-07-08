package work.art1st.mineRails.core.physics

import kotlinx.serialization.Serializable
import org.joml.Vector3dc
import work.art1st.mineRails.core.api.util.annotation.Heavy
import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Train
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
import work.art1st.mineRails.core.physics.train.SavedTrainV1
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 物理世界：由 [createEmpty] 或 [SavedWorldV1.load] 创建；Bukkit 等平台在创建后通过
 * [Extendable.registerExtensionData] 注册各插件的扩展数据。
 */
class CoreWorld(
    private val rails: MutableMap<UUID, CoreRail>,
    private val trains: MutableMap<String, CoreTrain>,
    private val signals: MutableMap<String, Signal>,
    override val tickManager: TickManager,
    worldParent: WorldParentContext,
    private val extensionDataContainer: ExtensionDataContainer<Any> = ExtensionDataContainer()
) : World, Extendable<Any> by extensionDataContainer, PersistentBy<CoreWorld.SavedWorldV1> {
    private val railSpatialIndex = RailSpatialIndex()

    init {
        worldParent.attachWorld(this)
    }

    private constructor(tickManager: TickManager, worldParent: WorldParentContext)
            : this(LinkedHashMap(), hashMapOf(), hashMapOf(), tickManager, worldParent)

    companion object {
        fun createEmpty(tickManager: TickManager, worldParent: WorldParentContext): CoreWorld =
            CoreWorld(tickManager, worldParent)
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
        var searchRadius = railSpatialIndex.cellSize
        var candidates: Collection<CoreRail> = emptyList()
        while (searchRadius <= railSpatialIndex.cellSize * 8 && candidates.isEmpty()) {
            candidates = railSpatialIndex.query(position, searchRadius)
            searchRadius *= 2.0
        }
        val searchSet = if (candidates.isEmpty()) rails.values else candidates
        return searchSet.minByOrNull { rail ->
            rail.curve.getNearestPoint(position).position.distance(position)
        }
    }

    @MainThread
    override fun removeRail(rail: Rail) {
        (rail as? CoreRail)?.let { railSpatialIndex.removeRail(it) }
        rails.remove(rail.id)
    }

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
            val worldParent: WorldParentContext
        )

        override fun load(dependency: Dependency): CoreWorld {
            val world = CoreWorld(dependency.tickManager, dependency.worldParent)
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
