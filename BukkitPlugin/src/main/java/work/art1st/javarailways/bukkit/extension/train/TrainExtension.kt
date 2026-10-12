package work.art1st.javarailways.bukkit.extension.train

import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorActionDefinition
import work.art1st.javarailways.bukkit.behavior.CarOperationBatch
import work.art1st.javarailways.bukkit.config.TrainModelUpdatePolicy
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.DriverControlEnd
import work.art1st.javarailways.bukkit.config.model.SavedTrainPackageSnapshot
import work.art1st.javarailways.bukkit.config.model.TrainActionConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.operation.RegionTrainOperationDispatcher
import work.art1st.javarailways.bukkit.persistence.TrainPackageSnapshotStore
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.core.api.physics.train.TrainExtension as CoreTrainExtension

@OptIn(ExtensionData::class)
val Train.rendererOrNull: TrainExtension?
    get() = BukkitPlugin.instanceOrNull()?.let { getExtensionDataOrNull(it) }

val Train.renderer: TrainExtension
    get() = rendererOrNull ?: error("Train renderer 未注册到列车 $id 上")

sealed interface TrainModelOperationResult {
    data object Accepted : TrainModelOperationResult
    data class Rejected(val reason: String) : TrainModelOperationResult
}

/** Simulation-owner facade. JS and accepted effects belong to the captured car instances. */
class TrainExtension : CoreTrainExtension {
    lateinit var self: Train
    private var savedPackageName: String? = null
    private var savedPackageSnapshotHash: String? = null
    private var savedPackageSnapshot: SavedTrainPackageSnapshot? = null

    override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
        self = host
        val state = savedExtensionData[BukkitPlugin.EXTENSION_ID] as? SavedTrainRenderState
        savedPackageName = state?.packageName
        savedPackageSnapshotHash = state?.packageSnapshotHash
    }

    private data class Target(val renderer: CarExtension, val tail: Boolean)

    private fun capture(): List<Target> {
        ThreadGuard.ensureMainThread("TrainExtension.captureBehaviorTargets")
        val controlTail = BukkitPlugin.INSTANCE.trainControlManager.operatingControlEndFor(self) == DriverControlEnd.TAIL
        return self.cars.map { car -> Target(car.carRenderer, controlTail xor ((car as CoreCar).isReversed xor self.isReversed)) }
    }

    fun executeDoorOperation(side: DoorSide, opening: Boolean, isPending: () -> Boolean = { true }): CompletableFuture<Unit> =
        broadcast(isPending = isPending) { target, done -> target.renderer.executeDoor(side, opening, target.tail, done) }

    fun executeModelAction(name: String, isPending: () -> Boolean = { true }, hasControl: Boolean = true): CompletableFuture<Unit> =
        broadcast(isPending = isPending, customName = name, hasControl = hasControl) { target, done -> target.renderer.executeAction(name, target.tail, done) }

    fun callBehavior(method: String, args: List<Any?> = emptyList(), optional: Boolean = false): CompletableFuture<Unit> =
        broadcast { target, done -> target.renderer.executeBehavior(method, args, target.tail, done, optional) }

    fun applyAnimation(name: String, scene: String? = null): CompletableFuture<Unit> = broadcast { target, done ->
        val applied = target.renderer.executeAnimation(name, scene)
        if (applied) { done(Result.success(Unit)); TrainModelOperationResult.Accepted }
        else TrainModelOperationResult.Rejected("car animation is unavailable: $name")
    }

    private fun broadcast(
        isPending: () -> Boolean = { true }, customName: String? = null, hasControl: Boolean = true,
        invoke: (Target, (Result<Unit>) -> Unit) -> TrainModelOperationResult
    ): CompletableFuture<Unit> {
        val captured = capture()
        val future = self.world.bukkitWorldData.trackModelOperation(CompletableFuture<Unit>())
        val dispatcher = RegionTrainOperationDispatcher(BukkitPlugin.INSTANCE)
        val targets = captured.map { target ->
            CarOperationBatch.Target(
                metadata = { dispatcher.dispatch(target.renderer) { target.renderer.behaviorActions(target.tail) } },
                execute = { done ->
                    dispatcher.dispatch(target.renderer) {
                        if (future.isDone || !isPending()) TrainModelOperationResult.Rejected("operation cancelled before car dispatch")
                        else invoke(target, done)
                    }
                }
            )
        }
        return CarOperationBatch.execute(targets, future, isPending, customName, hasControl)
    }

    /** Metadata executes on car owners, not the player's region. */
    fun loadBehaviorActions(): CompletableFuture<List<TrainBehaviorActionDefinition>> {
        val targets = capture()
        val future = self.world.bukkitWorldData.trackModelOperation(CompletableFuture<List<TrainBehaviorActionDefinition>>())
        if (future.isDone) return future
        val dispatcher = RegionTrainOperationDispatcher(BukkitPlugin.INSTANCE)
        val calls = targets.map { target -> dispatcher.dispatch(target.renderer) { target.renderer.behaviorActions(target.tail) } }
        CompletableFuture.allOf(*calls.toTypedArray()).whenComplete { _, failure ->
            if (failure != null) future.completeExceptionally(failure)
            else future.complete(calls.flatMap { it.join() }.groupBy { it.name }.values.map { entries ->
                entries.first().copy(executable = entries.all { it.executable }, requiresControl = entries.any { it.requiresControl })
            })
        }
        return future
    }

    /** Immutable YAML values; no Context initialization. Durations include every source car. */
    fun resolveActions(): Map<String, TrainActionConfig> {
        ThreadGuard.ensureMainThread("TrainExtension.resolveActions")
        return self.cars.flatMap { it.carRenderer.resolveActions().values }.groupBy { it.name }.mapValues { (_, entries) ->
            entries.first().copy(executable = entries.all { it.executable }, requiresControl = entries.any { it.requiresControl },
                animations = entries.flatMap { it.animations }, sounds = entries.flatMap { it.sounds })
        }
    }

    fun modelPackageName(): String? = resolvePackageName()

    fun resolveControl(): TrainControlConfig {
        if (hasPinnedOperationalSnapshot()) return operationalSnapshot()?.control
            ?: error("Pinned train package snapshot '$savedPackageSnapshotHash' is unavailable")
        return BukkitPlugin.INSTANCE.modelManager.getTrainControl(resolvePackageName())
    }

    fun resolveCarControl(carModelName: String): CarControlConfig? {
        if (hasPinnedOperationalSnapshot()) return operationalSnapshot()?.carControls?.get(carModelName.substringAfterLast('/'))
        return BukkitPlugin.INSTANCE.modelManager.getCar(carModelName)?.control
    }

    private fun hasPinnedOperationalSnapshot() = BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy == TrainModelUpdatePolicy.SNAPSHOT && savedPackageSnapshotHash != null

    private fun operationalSnapshot(): SavedTrainPackageSnapshot? {
        if (BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy != TrainModelUpdatePolicy.SNAPSHOT) return null
        savedPackageSnapshot?.let { return it }
        val hash = savedPackageSnapshotHash ?: return null
        return TrainPackageSnapshotStore.load(hash)?.also { savedPackageSnapshot = it }
    }

    private fun resolvePackageName(): String? {
        savedPackageName?.let { return it }
        val model = self.cars.firstOrNull()?.carRendererOrNull?.carModelName ?: return null
        return BukkitPlugin.INSTANCE.modelManager.getCar(model)?.packageName?.also { savedPackageName = it }
    }

    override fun splitBefore(carId: String, newId: String): CoreTrainExtension = TrainExtension()
    override fun linkWith(otherTrain: Train) = Unit

    fun pinCurrentPackageSnapshot(): String? {
        savedPackageSnapshotHash?.let { return it }
        return resolvePackageName()?.let { BukkitPlugin.INSTANCE.modelManager.createTrainPackageSnapshot(it) }
            ?.let { TrainPackageSnapshotStore.save(it) }?.also { savedPackageSnapshotHash = it }
    }

    override fun save(): Pair<String, SerializableExtensionData> = BukkitPlugin.EXTENSION_ID to SavedTrainRenderState(
        packageName = resolvePackageName(),
        packageSnapshotHash = if (BukkitPlugin.INSTANCE.javaRailwaysConfig.trainModelUpdatePolicy == TrainModelUpdatePolicy.SNAPSHOT) pinCurrentPackageSnapshot() else null)
}
