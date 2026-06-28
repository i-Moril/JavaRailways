package work.art1st.mineRails.core.api.physics.train

import work.art1st.mineRails.core.api.physics.WorldElement
import work.art1st.mineRails.core.api.util.Destroyable
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.Extension
import work.art1st.mineRails.core.api.util.tick.Synchronizable

interface Train : WorldElement, Destroyable, Synchronizable, Extendable<TrainExtension> {
    val id: String
    val cars: List<Car>
    val speed: Double
    val isDerailed: Boolean
    val length: Int
    fun moveTrainForward(deltaS: Double)
    fun reverse()
}
