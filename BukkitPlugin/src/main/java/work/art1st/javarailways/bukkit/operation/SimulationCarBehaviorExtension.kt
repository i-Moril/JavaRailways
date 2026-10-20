package work.art1st.javarailways.bukkit.operation

import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.bukkit.behavior.CarBehavior
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorActionDefinition
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorScriptException
import work.art1st.javarailways.bukkit.behavior.CapturedCarBehavior
import work.art1st.javarailways.bukkit.behavior.carBehaviorActions
import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.CoreTrain
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal val Car.simulationBehaviorOrNull: SimulationCarBehaviorExtension?
    get() {
        var behavior: SimulationCarBehaviorExtension? = null
        forEachExtensionData { extension ->
            if (extension is SimulationCarBehaviorExtension) {
                check(behavior == null) { "multiple simulation behaviors for car $id" }
                behavior = extension
            }
        }
        return behavior
    }

internal sealed interface SimulationBehaviorEffect {
    data class AnimationStarted(val name: String, val scene: String?) : SimulationBehaviorEffect
    data class SoundStarted(val key: String, val volume: Double, val pitch: Double) : SimulationBehaviorEffect
}

/** Exact-car state survives structural transfer; display/audio outlets validate starts without playback. */
internal class SimulationCarBehaviorExtension(
    val definition: CapturedCarBehavior,
    val carProfile: TrainDynamics.CarProfile,
    private val effectObserver: ((Car, SimulationBehaviorEffect) -> Unit)? = null
) : RenderableExtension<Car>, AutoCloseable {
    private lateinit var self: Car
    private var closed = false
    private val behavior = CarBehavior(
        script = { definition.script },
        actions = { definition.actions },
        animate = { name, scene ->
            val scenes = definition.animations[name]
            if (scenes == null || (scene != null && Animation.normalizeSceneName(scene) !in scenes)) false
            else {
                effectObserver?.invoke(self, SimulationBehaviorEffect.AnimationStarted(name, Animation.normalizeSceneName(scene)))
                true
            }
        },
        sound = { key, volume, pitch ->
            effectObserver?.invoke(self, SimulationBehaviorEffect.SoundStarted(key, volume, pitch))
            Unit
        },
        schedule = ::schedule,
        log = { message -> LOGGER.log(System.Logger.Level.INFO, "[simulation ${(self.world as CoreWorld).runtimeId} car ${self.id}] $message") },
        scriptFailed = ::stopTrainForScriptFailure
    )

    override fun initialize(host: Car, savedExtensionData: Map<String, SerializableExtensionData>) { self = host }
    override fun save(): Pair<String, SerializableExtensionData>? = null
    override fun commit() = Unit

    @Synchronized
    fun metadata(tail: Boolean): List<TrainBehaviorActionDefinition> {
        check(!closed) { "car behavior is closed" }
        return carBehaviorActions(definition.actions, behavior.metadata(tail))
    }

    @Synchronized
    fun door(side: DoorSide, opening: Boolean, tail: Boolean, done: (Result<Unit>) -> Unit): TrainModelOperationResult =
        if (closed) TrainModelOperationResult.Rejected("car behavior is closed")
        else behavior.door(side, opening, tail, done)

    @Synchronized
    fun action(name: String, tail: Boolean, done: (Result<Unit>) -> Unit): TrainModelOperationResult =
        if (closed) TrainModelOperationResult.Rejected("car behavior is closed")
        else behavior.action(name, tail, done)

    @Synchronized
    fun call(method: String, args: List<Any?>, tail: Boolean, done: (Result<Unit>) -> Unit): TrainModelOperationResult =
        if (closed) TrainModelOperationResult.Rejected("car behavior is closed")
        else behavior.call(method, args, tail, done, optional = true)

    private fun stopTrainForScriptFailure(failure: TrainBehaviorScriptException) {
        val train = self.train as? CoreTrain
        LOGGER.log(System.Logger.Level.ERROR,
            "[simulation ${(self.world as CoreWorld).runtimeId} car ${self.id} train ${train?.id}] Car behavior failed", failure)
        if (train != null) train.world.physicsCoordinator.submitBetweenFrames {
            if (self.train === train && train.world.getTrain(train.id) === train) {
                train.derail("Car behavior failed: car=${self.id}: ${failure.message}")
            }
        }
    }

    /** Generic workers advance elapsed time only; the existing owner commit runs the effect. */
    private fun schedule(delay: Double, action: () -> Unit): AutoCloseable {
        val effect = object : TickingObject, Synchronizable, Destroyable {
            private var elapsed = 0.0
            private var destroyed = false
            override fun tick(deltaT: Double) = synchronized(this@SimulationCarBehaviorExtension) {
                if (!destroyed) elapsed += deltaT.coerceAtLeast(0.0) / 1000.0
            }
            override fun commit() = synchronized(this@SimulationCarBehaviorExtension) {
                if (!destroyed && elapsed >= delay) {
                    try { action() } finally { destroy().toCompletableFuture().getNow(null) }
                }
            }
            private val destruction = Destruction()
            override fun destroy(): CompletionStage<Void> = destruction.run {
                synchronized(this@SimulationCarBehaviorExtension) {
                    destroyed = true
                    self.world.tickManager.unregister(this)
                }
                CompletableFuture.completedFuture(null)
            }
        }
        self.world.tickManager.register(effect)
        return AutoCloseable { effect.destroy().toCompletableFuture().getNow(null) }
    }

    private val destruction = Destruction()

    override fun destroy(): CompletionStage<Void> = destruction.run {
        synchronized(this) {
            closed = true
            behavior.close()
        }
        CompletableFuture.completedFuture(null)
    }

    override fun close() {
        destroy().toCompletableFuture().getNow(null)
    }

    companion object {
        private val LOGGER = System.getLogger(SimulationCarBehaviorExtension::class.java.name)
    }
}
