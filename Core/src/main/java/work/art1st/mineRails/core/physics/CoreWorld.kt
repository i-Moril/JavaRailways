package work.art1st.mineRails.core.physics

import org.koin.core.scope.Scope
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.persistence.Loads
import work.art1st.mineRails.core.api.util.persistence.PersistentBy
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.tick.TickManager
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.train.SavedTrainV1
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
    dependencyProvider: Scope,
    private val extensionDataContainer: ExtensionDataContainer<Any> = ExtensionDataContainer()
) : World, Extendable<Any> by extensionDataContainer, PersistentBy<CoreWorld.SavedWorldV1> {
    val dependencyProvider: Scope

    init {
        this.dependencyProvider = dependencyProvider.getScope("world_$this")
        this.dependencyProvider.declare(this)
    }

    private constructor(tickManager: TickManager, dependencyProvider: Scope)
            : this(hashMapOf(), hashMapOf(), hashMapOf(), tickManager, dependencyProvider)

    companion object {
        fun createEmpty(tickManager: TickManager, parentScope: Scope): CoreWorld =
            CoreWorld(tickManager, parentScope)
    }

    @MainThread
    internal fun addTrain(train: CoreTrain) {
        trains[train.id] = train
    }

    override fun getTrain(name: String): CoreTrain? = trains[name]

    @MainThread
    override fun removeTrain(train: Train) {
        trains.remove(train.id)
    }

    @MainThread
    internal fun addRail(rail: CoreRail) {
        rails[rail.id] = rail
    }

    override fun getRail(id: UUID): CoreRail? = rails[id]

    @MainThread
    override fun removeRail(rail: CoreRail) {
        rails.remove(rail.id)
    }

    override fun save(): SavedWorldV1 {
        return SavedWorldV1(
            rails.values.map { it.save() },
            trains.values.map { it.save() }
        )
    }

    data class SavedWorldV1 (
        val rails: List<CoreRail.SavedRailV1>,
        val trains: List<SavedTrainV1>
    ) : Loads<CoreWorld, SavedWorldV1.Dependency> {
        data class Dependency(
            val tickManager: TickManager,
            val parentScope: Scope
        )

        override fun load(dependency: Dependency): CoreWorld {
            val world = CoreWorld(dependency.tickManager, dependency.parentScope)
            val railDependency = CoreRail.SavedRailV1.Dependency(world)
            rails.forEach { world.addRail(it.load(railDependency)) }
            trains.forEach { world.addTrain(it.load(world)) }
            return world
        }
    }

}