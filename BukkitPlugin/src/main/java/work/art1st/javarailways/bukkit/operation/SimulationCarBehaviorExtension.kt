package work.art1st.javarailways.bukkit.operation

import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.bukkit.behavior.CarBehavior
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorActionDefinition
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorScriptException
import work.art1st.javarailways.bukkit.behavior.CapturedCarBehavior
import work.art1st.javarailways.bukkit.behavior.supportsAnimation
import work.art1st.javarailways.bukkit.behavior.carBehaviorActions
import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
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

/** Exact-car state survives structural transfer; real animation timing runs without display or audio outlets. */
internal class SimulationCarBehaviorExtension(
    val definition: CapturedCarBehavior,
    val carProfile: TrainDynamics.CarProfile,
    private val effectObserver: ((Car, SimulationBehaviorEffect) -> Unit)? = null
) : RenderableExtension<Car>, AutoCloseable {
    private lateinit var self: Car
    private var closed = false
    private val clock by lazy { work.art1st.javarailways.bukkit.behavior.CarBehaviorClock(self.world.tickManager, this) }
    private val playback = work.art1st.javarailways.bukkit.behavior.CarAnimationPlayback(
        { definition.animationParts },
        { active -> clock.register(active) },
        { emptyMap() }, { _, _ -> })
    private val behavior = CarBehavior(
        script = { definition.script },
        actions = { definition.actions },
        animate = { name, scene ->
            if (!definition.animations.supportsAnimation(name, scene)) CompletableFuture.failedFuture(IllegalStateException("animation unavailable: $name"))
            else {
                effectObserver?.invoke(self, SimulationBehaviorEffect.AnimationStarted(name, Animation.normalizeSceneName(scene)))
                playback.play(name, scene)
            }
        },
        sound = { key, volume, pitch ->
            effectObserver?.invoke(self, SimulationBehaviorEffect.SoundStarted(key, volume, pitch))
            Unit
        },
        schedule = { delay, action -> clock.schedule(delay, action) },
        log = { message -> LOGGER.log(System.Logger.Level.INFO, "[simulation ${(self.world as CoreWorld).runtimeId} car ${self.id}] $message") },
        scriptFailed = ::stopTrainForScriptFailure,
        hasAnimation = { name, scene -> definition.animations.supportsAnimation(name, scene) },
        closeDuration = playback::closeDurationTicks,
        planningEnvironment = { definition }
    )

    override fun initialize(host: Car, savedExtensionData: Map<String, SerializableExtensionData>) { self = host; clock.start() }
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
    fun estimateDoorCloseDurationTicks(side: DoorSide, tail: Boolean): Long {
        check(!closed) { "car behavior is closed" }
        return behavior.estimateDoorCloseDurationTicks(side, tail)
    }

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

    private val destruction = Destruction()

    override fun destroy(): CompletionStage<Void> = destruction.run {
        synchronized(this) {
            closed = true
            try { behavior.close() } finally { try { playback.close() } finally { clock.close() } }
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
