package work.art1st.mineRails.core.api.physics.train

import work.art1st.mineRails.core.api.physics.WorldElement
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.Extension
import work.art1st.mineRails.core.physics.train.CoreCar

interface Train : WorldElement, Extendable<TrainExtension> {
    val id: String
    val cars: List<CoreCar>
}
