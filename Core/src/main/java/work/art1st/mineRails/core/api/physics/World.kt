package work.art1st.mineRails.core.api.physics

import work.art1st.mineRails.core.api.physics.rail.Rail
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.tick.TickManager
import java.util.UUID

interface World : Extendable<Any> {
    val tickManager: TickManager
    val allTrains: Collection<Train>
    fun getTrain(name: String): Train?
    fun removeTrain(train: Train)
    fun getRail(id: UUID): Rail?
    fun removeRail(rail: Rail)
}