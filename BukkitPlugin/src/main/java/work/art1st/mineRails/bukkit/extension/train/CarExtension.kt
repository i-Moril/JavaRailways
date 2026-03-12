package work.art1st.mineRails.bukkit.extension.train

import work.art1st.mineRails.bukkit.extension.renderChunkManager
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData

class CarExtension : TrainComponentExtension<Car>() {
    override fun save(): Pair<String, SerializableExtensionData>? {
        TODO("Not yet implemented")
    }

    /**
     * 由car负责更新bogie
     */
    override fun commit() {
        val renderManager = self.world.renderChunkManager
        renderManager.update(self, self.lastState.position, self.position)
        self.bogies.forEach {
            renderManager.update(it, it.lastState.position, it.position)
        }
        super.commit()
    }

    override fun destroy() {
        val renderManager = self.world.renderChunkManager
        renderManager.remove(self)
        self.bogies.forEach {
            renderManager.remove(it)
        }
        super.destroy()
    }

//    override fun render(player: Player) {
//        sendBundleDelimiter(player)
//        model.render(player)
//        self.bogies.forEach { it.renderer.render(player) }
//        sendBundleDelimiter(player)
//    }
}