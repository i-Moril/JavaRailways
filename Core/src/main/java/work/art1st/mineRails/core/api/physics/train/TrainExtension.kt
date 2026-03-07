package work.art1st.mineRails.core.api.physics.train

import work.art1st.mineRails.core.api.util.plugin.Extension

interface TrainExtension : Extension<Train> {
    fun splitFrom(carNum: Int, newId: String) : TrainExtension
    fun linkWith(otherTrain: Train)
}