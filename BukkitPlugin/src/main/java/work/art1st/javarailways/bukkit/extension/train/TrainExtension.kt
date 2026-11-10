package work.art1st.javarailways.bukkit.extension.train

import org.bukkit.Location
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorActionDefinition
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorBindings
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorCallResult
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorRuntime
import work.art1st.javarailways.bukkit.config.TrainModelUpdatePolicy
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.DriverControlEnd
import work.art1st.javarailways.bukkit.config.model.SavedTrainPackageSnapshot
import work.art1st.javarailways.bukkit.config.model.TrainActionAnimationConfig
import work.art1st.javarailways.bukkit.config.model.TrainActionConfig
import work.art1st.javarailways.bukkit.config.model.TrainBehaviorScript
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainExtension as CoreTrainExtension
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import work.art1st.javarailways.bukkit.util.PlatformCommitAffinity
import work.art1st.javarailways.bukkit.persistence.TrainPackageSnapshotStore
import kotlin.math.max

@OptIn(ExtensionData::class)
val Train.rendererOrNull: TrainExtension?
    get() {
        val plugin = BukkitPlugin.instanceOrNull() ?: return null
        return getExtensionDataOrNull(plugin)
    }

val Train.renderer: TrainExtension
    get() = rendererOrNull
        ?: throw IllegalStateException("Train renderer 未注册到列车 ${this.id} 上")

sealed interface TrainModelOperationResult {
    data class Accepted(val completionDelaySeconds: Double) : TrainModelOperationResult
    data class Rejected(val reason: String) : TrainModelOperationResult
}

class TrainExtension : CoreTrainExtension {
    lateinit var self: Train
    private var behaviorRuntime: TrainBehaviorRuntime? = null
    private var behaviorScript: TrainBehaviorScript? = null
    private var actionExecutionTracker: ActionExecutionTracker? = null
    private var savedPackageName: String? = null
    private var savedPackageSnapshotHash: String? = null
    private var savedPackageSnapshot: SavedTrainPackageSnapshot? = null

    fun close() {
        behaviorRuntime?.close()
        behaviorRuntime = null
        behaviorScript = null
        actionExecutionTracker = null
    }

    override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
        val savedState = savedExtensionData[BukkitPlugin.EXTENSION_ID] as? SavedTrainRenderState
        savedPackageName = savedState?.packageName
        savedPackageSnapshotHash = savedState?.packageSnapshotHash
    }

    fun applyAnimation(name: String): Boolean {
        return self.cars.fold(false) { applied, car -> car.renderer.applyAnimation(name) || applied }
    }

    fun applyAnimation(name: String, sceneName: String): Boolean {
        return self.cars.fold(false) { applied, car -> car.renderer.applyAnimation(name, sceneName) || applied }
    }

    fun applyAction(name: String): Boolean {
        val action = resolveAction(name)
        if (action == null) {
            actionExecutionTracker?.record(applied = false, completionDelaySeconds = 0.0)
            return false
        }
        var applied = false
        var completionDelaySeconds = 0.0
        for (animation in action.animations) {
            completionDelaySeconds = max(completionDelaySeconds, animation.at)
            if (animation.at > 0.0) {
                scheduleDelayed(animation.at) { applyActionAnimation(animation) }
                applied = true
            } else {
                applied = applyActionAnimation(animation) || applied
            }
        }
        for (sound in action.sounds) {
            completionDelaySeconds = max(completionDelaySeconds, sound.at)
            if (sound.at > 0.0) {
                scheduleDelayed(sound.at) { playSound(sound.key, sound.volume, sound.pitch) }
            } else {
                playSound(sound.key, sound.volume, sound.pitch)
            }
            applied = true
        }
        actionExecutionTracker?.record(applied, completionDelaySeconds)
        return applied
    }

    /**
     * Executes the model's semantic door behavior and confirms only after all declared delayed
     * action effects have reached their latest configured start time.
     */
    fun executeDoorOperation(
        side: DoorSide,
        opening: Boolean,
        onComplete: () -> Unit
    ): TrainModelOperationResult {
        val position = self.headLocation.position
        BukkitThreadOwnership.ensureLocation(
            Location(self.world.bukkitWorldData.bukkitWorld, position.x(), position.y(), position.z()),
            "TrainExtension.executeDoorOperation"
        )
        if (side == DoorSide.NONE) return TrainModelOperationResult.Rejected("door side is NONE")
        if (actionExecutionTracker != null) {
            return TrainModelOperationResult.Rejected("another tracked train action is executing")
        }
        val tracker = ActionExecutionTracker()
        actionExecutionTracker = tracker
        val behaviorResult: TrainBehaviorCallResult?
        try {
            val packageName = resolvePackageName()
                ?: return TrainModelOperationResult.Rejected("train model package is unavailable")
            val behavior = resolveBehavior()
            behaviorResult = if (behavior != null) {
                val runtime = ensureBehaviorRuntime(behavior)
                    ?: return TrainModelOperationResult.Rejected("train behavior script failed to load")
                runtime.call(
                    "door",
                    listOf(
                        side.name.lowercase(),
                        if (opening) "open" else "closed"
                    )
                )
            } else {
                val suffix = if (opening) "open" else "close"
                val semanticSides = if (side == DoorSide.BOTH) {
                    listOf("left", "right")
                } else {
                    listOf(side.name.lowercase())
                }
                val operatingFromTail = BukkitPlugin.INSTANCE.trainControlManager
                    .operatingControlEndFor(self) == DriverControlEnd.TAIL
                val physicalSides = semanticSides.map { semanticSide ->
                    if (!operatingFromTail) semanticSide
                    else if (semanticSide == "left") "right" else "left"
                }
                val applied = physicalSides.all { applyAction("door-$it-$suffix") }
                if (applied) TrainBehaviorCallResult.Success("model-actions", true)
                else TrainBehaviorCallResult.Failure("door model action is unavailable")
            }
        } finally {
            actionExecutionTracker = null
        }
        if (behaviorResult is TrainBehaviorCallResult.Failure) {
            return TrainModelOperationResult.Rejected(behaviorResult.message)
        }
        if (behaviorResult !is TrainBehaviorCallResult.Success || behaviorResult.returnValue == false) {
            return TrainModelOperationResult.Rejected("door behavior rejected the operation")
        }
        if (tracker.callCount == 0 || tracker.failedCount > 0) {
            return TrainModelOperationResult.Rejected(
                "door behavior did not apply every requested model action"
            )
        }
        if (tracker.completionDelaySeconds > 0.0) {
            scheduleDelayed(tracker.completionDelaySeconds, onComplete)
        } else {
            onComplete()
        }
        return TrainModelOperationResult.Accepted(tracker.completionDelaySeconds)
    }

    /** Execute a named behavior.js or actions.yml action and confirm its delayed effects. */
    fun executeModelAction(name: String, onComplete: () -> Unit): TrainModelOperationResult {
        val position = self.headLocation.position
        BukkitThreadOwnership.ensureLocation(
            Location(self.world.bukkitWorldData.bukkitWorld, position.x(), position.y(), position.z()),
            "TrainExtension.executeModelAction"
        )
        if (name.isBlank() || name != name.trim()) {
            return TrainModelOperationResult.Rejected("model action name is invalid")
        }
        if (actionExecutionTracker != null) {
            return TrainModelOperationResult.Rejected("another tracked train action is executing")
        }
        val tracker = ActionExecutionTracker()
        actionExecutionTracker = tracker
        val applied = try {
            val behaviorDefinesAction = behaviorActions().any { it.name == name }
            if (behaviorDefinesAction) applyBehaviorAction(name) else applyAction(name)
        } finally {
            actionExecutionTracker = null
        }
        if (!applied || tracker.callCount == 0 || tracker.failedCount > 0) {
            return TrainModelOperationResult.Rejected("train model action is unavailable or failed: $name")
        }
        if (tracker.completionDelaySeconds > 0.0) {
            scheduleDelayed(tracker.completionDelaySeconds, onComplete)
        } else {
            onComplete()
        }
        return TrainModelOperationResult.Accepted(tracker.completionDelaySeconds)
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
        val script = resolveBehavior() ?: return null
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

    fun resolveActions(): Map<String, TrainActionConfig> {
        if (hasPinnedOperationalSnapshot()) return operationalSnapshot()?.actions.orEmpty()
        return BukkitPlugin.INSTANCE.modelManager.getTrainActions(resolvePackageName())
    }

    fun resolveAction(name: String): TrainActionConfig? {
        if (hasPinnedOperationalSnapshot()) return operationalSnapshot()?.actions?.get(name)
        return BukkitPlugin.INSTANCE.modelManager.getTrainAction(resolvePackageName(), name)
    }

    fun resolveBehavior(): TrainBehaviorScript? {
        if (hasPinnedOperationalSnapshot()) return operationalSnapshot()?.behavior
        return BukkitPlugin.INSTANCE.modelManager.getTrainBehavior(resolvePackageName())
    }

    fun resolveControl(): TrainControlConfig {
        if (hasPinnedOperationalSnapshot()) {
            return operationalSnapshot()?.control
                ?: throw IllegalStateException("Pinned train package snapshot '$savedPackageSnapshotHash' is unavailable")
        }
        return BukkitPlugin.INSTANCE.modelManager.getTrainControl(resolvePackageName())
    }

    fun resolveCarControl(carModelName: String): CarControlConfig? {
        if (hasPinnedOperationalSnapshot()) {
            return operationalSnapshot()?.carControls?.get(carModelName.substringAfterLast('/'))
        }
        return BukkitPlugin.INSTANCE.modelManager.getCar(carModelName)?.control
    }

    private fun hasPinnedOperationalSnapshot(): Boolean =
        BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy == TrainModelUpdatePolicy.SNAPSHOT &&
            savedPackageSnapshotHash != null

    private fun operationalSnapshot(): SavedTrainPackageSnapshot? {
        if (BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy != TrainModelUpdatePolicy.SNAPSHOT) return null
        savedPackageSnapshot?.let { return it }
        val hash = savedPackageSnapshotHash ?: return null
        return TrainPackageSnapshotStore.load(hash)?.also { savedPackageSnapshot = it }
    }

    private fun resolvePackageName(): String? {
        savedPackageName?.let { return it }
        val modelManager = BukkitPlugin.INSTANCE.modelManager
        val firstModelName = self.cars.firstOrNull()?.carRendererOrNull?.carModelName ?: return null
        return modelManager.getCar(firstModelName)?.packageName?.also { savedPackageName = it }
    }

    private fun playSound(key: String, volume: Double = 1.0, pitch: Double = 1.0) {
        val bukkitWorld = self.world.bukkitWorldData.bukkitWorld
        val position = self.headLocation.position
        BukkitThreadOwnership.ensureLocation(
            Location(bukkitWorld, position.x(), position.y(), position.z()),
            "TrainExtension.playSound"
        )
        val location = Location(bukkitWorld, position.x(), position.y(), position.z())
        bukkitWorld.playSound(location, key, volume.toFloat(), pitch.toFloat())
    }

    private class ActionExecutionTracker {
        var callCount: Int = 0
            private set
        var failedCount: Int = 0
            private set
        var completionDelaySeconds: Double = 0.0
            private set

        fun record(applied: Boolean, completionDelaySeconds: Double) {
            callCount++
            if (!applied) failedCount++
            this.completionDelaySeconds = max(
                this.completionDelaySeconds,
                completionDelaySeconds.coerceAtLeast(0.0)
            )
        }
    }

    private inner class DelayedActionEffect(
        private val delaySeconds: Double,
        private val action: () -> Unit
    ) : TickingObject, Synchronizable, Destroyable, PlatformCommitAffinity {
        private var elapsedSeconds: Double = 0.0
        private var ready: Boolean = delaySeconds <= 0.0
        private var destroyed: Boolean = false

        @Synchronized
        override fun tick(deltaT: Double) {
            if (destroyed || ready) return
            elapsedSeconds += deltaT.coerceAtLeast(0.0) / 1000.0
            if (elapsedSeconds >= delaySeconds) ready = true
        }

        override fun commitLocation(): Location {
            val position = self.headLocation.position
            return Location(self.world.bukkitWorldData.bukkitWorld, position.x(), position.y(), position.z())
        }

        @Synchronized
        override fun commit() {
            if (destroyed || !ready) return
            action.invoke()
            destroy()
        }

        @Synchronized
        override fun destroy() {
            if (destroyed) return
            destroyed = true
            self.world.tickManager.unregister(this)
        }
    }

    override fun splitFrom(carNum: Int, newId: String): CoreTrainExtension = TrainExtension()

    override fun linkWith(otherTrain: Train) = Unit

    fun pinCurrentPackageSnapshot(): String? {
        savedPackageSnapshotHash?.let { return it }
        return resolvePackageName()
            ?.let { BukkitPlugin.INSTANCE.modelManager.createTrainPackageSnapshot(it) }
            ?.let { TrainPackageSnapshotStore.save(it) }
            ?.also { savedPackageSnapshotHash = it }
    }

    override fun save(): Pair<String, SerializableExtensionData>? {
        val packageName = resolvePackageName()
        val snapshotHash = if (BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy == TrainModelUpdatePolicy.SNAPSHOT) {
            pinCurrentPackageSnapshot()
        } else {
            null
        }
        return BukkitPlugin.EXTENSION_ID to SavedTrainRenderState(
            packageName = packageName,
            packageSnapshotHash = snapshotHash
        )
    }
}
