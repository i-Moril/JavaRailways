package work.art1st.mineRails.bukkit.extension.train

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
import org.bukkit.entity.Player
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.util.plugin.Extension
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData

class TrainExtension : Extension<Train> {
    lateinit var self: Train
    override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
    }

    fun applyAnimation(name: String) {
        self.cars.forEach {
            it.renderer.applyAnimation(name)
        }
    }

    override fun save(): Pair<String, SerializableExtensionData>? = null
}