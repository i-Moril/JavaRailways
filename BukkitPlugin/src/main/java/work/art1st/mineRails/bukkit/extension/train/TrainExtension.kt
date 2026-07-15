package work.art1st.mineRails.bukkit.extension.train

import org.bukkit.Location
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.behavior.TrainBehaviorActionDefinition
import work.art1st.mineRails.bukkit.behavior.TrainBehaviorBindings
import work.art1st.mineRails.bukkit.behavior.TrainBehaviorCallResult
import work.art1st.mineRails.bukkit.behavior.TrainBehaviorRuntime
import work.art1st.mineRails.bukkit.config.model.TrainActionAnimationConfig
import work.art1st.mineRails.bukkit.config.model.TrainBehaviorScript
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainExtension as CoreTrainExtension
import work.art1st.mineRails.core.api.util.Destroyable
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickingObject
import work.art1st.mineRails.core.util.ThreadGuard

@OptIn(ExtensionData::class)
val Train.rendererOrNull: TrainExtension?
    get() {
        val plugin = BukkitPlugin.instanceOrNull() ?: return null
        return getExtensionDataOrNull(plugin)
    }

val Train.renderer: TrainExtension
    get() = rendererOrNull
        ?: throw IllegalStateException("Train renderer 未注册到列车 ${this.id} 上")

class TrainExtension : CoreTrainExtension {
    lateinit var self: Train
    private var behaviorRuntime: TrainBehaviorRuntime? = null
    private var behaviorScript: TrainBehaviorScript? = null
    override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
    }

    fun applyAnimation(name: String): Boolean {
        return self.cars.fold(false) { applied, car -> car.renderer.applyAnimation(name) || applied }
    }

    fun applyAnimation(name: String, sceneName: String): Boolean {
        return self.cars.fold(false) { applied, car -> car.renderer.applyAnimation(name, sceneName) || applied }
    }

    fun applyAction(name: String): Boolean {
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val action = modelManager.getTrainAction(resolvePackageName(), name) ?: return false
        var applied = false
        for (animation in action.animations) {
            if (animation.at > 0.0) {
                scheduleDelayed(animation.at) { applyActionAnimation(animation) }
                applied = true
            } else {
                applied = applyActionAnimation(animation) || applied
            }
        }
        for (sound in action.sounds) {
            if (sound.at > 0.0) {
                scheduleDelayed(sound.at) { playSound(sound.key, sound.volume, sound.pitch) }
            } else {
                playSound(sound.key, sound.volume, sound.pitch)
            }
            applied = true
        }
        return applied
    }

    private fun applyActionAnimation(animation: TrainActionAnimationConfig): Boolean {
        return if (animation.scene == null) {
            applyAnimation(animation.name)
        } else {
            applyAnimation(animation.name, animation.scene)
        }
    }

    private fun scheduleDelayed(delaySeconds: Double, action: () -> Unit) {
        val delayed = DelayedActionEffect(delaySeconds.coerceAtLeast(0.0), action)
        self.world.tickManager.register(delayed)
    }

    fun callBehavior(method: String, args: List<Any?> = emptyList()): TrainBehaviorCallResult {
        val runtime = ensureBehaviorRuntime()
            ?: return TrainBehaviorCallResult.Failure("当前列车模型包没有 behavior.js 或行为脚本加载失败")
        return runtime.call(method, args)
    }

    fun behaviorActions(): List<TrainBehaviorActionDefinition> {
        return ensureBehaviorRuntime()?.listActions() ?: emptyList()
    }

    fun applyBehaviorAction(name: String): Boolean {
        val runtime = ensureBehaviorRuntime() ?: return false
        val result = runtime.runDefinedAction(name)
        return result is TrainBehaviorCallResult.Success && result.returnValue != false
    }

    private fun ensureBehaviorRuntime(): TrainBehaviorRuntime? {
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val script = modelManager.getTrainBehavior(resolvePackageName()) ?: return null
        return ensureBehaviorRuntime(script)
    }

    private fun ensureBehaviorRuntime(script: TrainBehaviorScript): TrainBehaviorRuntime? {
        if (behaviorRuntime != null && behaviorScript == script) return behaviorRuntime
        behaviorRuntime?.close()
        behaviorRuntime = null
        behaviorScript = script
        return try {
            TrainBehaviorRuntime(script, createBehaviorBindings()).also { behaviorRuntime = it }
        } catch (e: Exception) {
            BukkitPlugin.INSTANCE.logger.warning(TrainBehaviorRuntime.formatLoadException(script, e))
            null
        }
    }

    private fun createBehaviorBindings(): TrainBehaviorBindings {
        return object : TrainBehaviorBindings {
            override fun action(name: String): Boolean = applyAction(name)

            override fun sound(key: String, volume: Double, pitch: Double): Boolean {
                playSound(key, volume, pitch)
                return true
            }

            override fun log(message: String) {
                BukkitPlugin.INSTANCE.logger.info("[TrainBehavior:${self.id}] $message")
            }

            override fun operatingEnd(): String {
                return BukkitPlugin.INSTANCE.trainControlManager.operatingControlEndFor(self).name.lowercase()
            }
        }
    }

    fun modelPackageName(): String? = resolvePackageName()

    private fun resolvePackageName(): String? {
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val firstModelName = self.cars.firstOrNull()?.carRendererOrNull?.carModelName ?: return null
        return modelManager.getCar(firstModelName)?.packageName
    }

    private fun playSound(key: String, volume: Double = 1.0, pitch: Double = 1.0) {
        ThreadGuard.ensureMainThread("TrainExtension.playSound")
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val position = self.headLocation.position
        val location = Location(bukkitWorld, position.x(), position.y(), position.z())
        bukkitWorld.playSound(location, key, volume.toFloat(), pitch.toFloat())
    }

    private inner class DelayedActionEffect(
        private val delaySeconds: Double,
        private val action: () -> Unit
    ) : TickingObject, Synchronizable, Destroyable {
        private var elapsedSeconds: Double = 0.0
        private var ready: Boolean = delaySeconds <= 0.0
        private var destroyed: Boolean = false

        override fun tick(deltaT: Double) {
            if (destroyed || ready) return
            elapsedSeconds += deltaT.coerceAtLeast(0.0) / 1000.0
            if (elapsedSeconds >= delaySeconds) ready = true
        }

        override fun commit() {
            if (destroyed || !ready) return
            action.invoke()
            destroy()
        }

        override fun destroy() {
            if (destroyed) return
            destroyed = true
            self.world.tickManager.unregister(this)
        }
    }

    override fun splitFrom(carNum: Int, newId: String): CoreTrainExtension = TrainExtension()

    override fun linkWith(otherTrain: Train) = Unit

    override fun save(): Pair<String, SerializableExtensionData>? {
        return BukkitPlugin.EXTENSION_ID to SavedTrainRenderState()
    }
}