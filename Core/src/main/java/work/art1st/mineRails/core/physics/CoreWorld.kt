package work.art1st.mineRails.core.physics

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.scope.Scope
import org.koin.dsl.module
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.tick.TickManager
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.rail.CoreRail
import java.util.UUID

class CoreWorld(
    private val rails: MutableMap<UUID, CoreRail>,
    private val trains: MutableMap<String, CoreTrain>,
    private val signals: MutableMap<String, Signal>,
    override val tickManager: TickManager,
    dependencyProvider: Scope,
    private val extensionDataContainer: ExtensionDataContainer<Any> = ExtensionDataContainer()
) : World, Extendable<Any> by extensionDataContainer {

    val dependencyProvider: Scope

    init {
        this.dependencyProvider = dependencyProvider.getScope("world_$this")
        this.dependencyProvider.declare(this)
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

//    fun getNearestRailLocation(position: Vector3dc): RailLocation {
//
//    }

}