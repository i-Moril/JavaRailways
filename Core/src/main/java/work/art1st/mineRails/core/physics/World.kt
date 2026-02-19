package work.art1st.mineRails.core.physics

import work.art1st.mineRails.core.api.util.TickManager
import work.art1st.mineRails.core.control.signal.Signal
import work.art1st.mineRails.core.physics.train.Train
import work.art1st.mineRails.core.physics.rail.Rail
import java.util.UUID
import java.util.concurrent.ConcurrentMap

class World(
    private val rails: ConcurrentMap<UUID, Rail>,
    private val trains: ConcurrentMap<String, Train>,
    private val signals: ConcurrentMap<String, Signal>,
    val tickManager: TickManager,
) {

    internal fun addTrain(train: Train) {
        trains[train.id] = train
    }

    fun getTrain(name: String): Train? = trains[name]

    fun removeTrain(train: Train) = trains.remove(train.id)

    internal fun addRail(rail: Rail) {
        rails[rail.id] = rail
    }

    fun getRail(id: UUID): Rail? = rails[id]

    fun removeRail(rail: Rail) = rails.remove(rail.id)

//    fun getNearestRailLocation(position: Vector3dc): RailLocation {
//
//    }

}