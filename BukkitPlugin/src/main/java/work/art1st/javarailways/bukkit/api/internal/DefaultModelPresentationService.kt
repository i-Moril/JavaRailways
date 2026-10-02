package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.Location
import org.bukkit.World as BukkitWorld
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.model.*
import work.art1st.javarailways.bukkit.config.model.ModelTreeBuilder
import work.art1st.javarailways.bukkit.render.backend.DisplayRenderBackend
import work.art1st.javarailways.bukkit.render.chunk.OrdinaryModelRenderable
import work.art1st.javarailways.bukkit.render.chunk.RenderChunkManager
import work.art1st.javarailways.bukkit.render.model.OrdinaryModel
import work.art1st.javarailways.bukkit.render.model.OrdinarySceneIndex
import work.art1st.javarailways.bukkit.render.backend.DisplayCleanupException
import work.art1st.javarailways.bukkit.util.LatestAffinityTaskDispatcher
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.api.physics.World
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** Value submissions never wait for model construction or target-owner work. */
internal class DefaultModelPresentationService(
    private val host: Plugin,
    private val dispatcher: PlatformDispatcher,
    private val backend: DisplayRenderBackend,
    private val viewRange: Float,
    private val logger: Logger
) : ModelPresentationService, AutoCloseable {
    private val registryLock = Any()
    private val worlds = mutableMapOf<UUID, WorldBinding>()
    private val active = mutableMapOf<Identity, Handle>()
    // Closed handles remain here until cleanup finishes, so closeOwner also observes earlier closes.
    private val outstanding = mutableSetOf<Handle>()
    @Volatile private var closed = false
    private val tasks = LatestAffinityTaskDispatcher<Handle, Handle> { handle, complete ->
        val completed = AtomicBoolean()
        dispatcher.runAsync { handle.dispatch { if (completed.compareAndSet(false, true)) complete() } }
    }

    fun registerWorld(worldId: UUID, bukkitWorld: BukkitWorld, railWorld: World, audiences: RenderChunkManager) {
        synchronized(registryLock) {
            check(!closed) { "Model presentation service is closed" }
            check(worldId !in worlds) { "Model presentation world $worldId is already registered" }
            worlds[worldId] = WorldBinding(bukkitWorld, railWorld, audiences)
        }
    }

    fun removeWorld(worldId: UUID, bukkitWorld: BukkitWorld) {
        val handles = synchronized(registryLock) {
            val binding = worlds[worldId]?.takeIf { it.bukkitWorld === bukkitWorld } ?: return
            worlds.remove(worldId)
            binding.available = false
            outstanding.filter { it.identity.world === binding }
        }
        handles.forEach(Handle::close)
    }

    override fun create(owner: Plugin, worldId: UUID, objectId: String, definition: ModelDefinition): ModelHandle {
        require(objectId.isNotBlank()) { "Ordinary model object ID must not be blank" }
        val sceneIndex = OrdinarySceneIndex(definition)
        return synchronized(registryLock) {
            check(!closed && host.isEnabled) { "Model presentation host is unavailable" }
            check(owner.isEnabled) { "Model owner ${owner.name} is disabled" }
            val binding = worlds[worldId] ?: error("Model presentation world $worldId is not loaded")
            val identity = Identity(owner, binding, objectId)
            check(identity !in active) { "Active model ${owner.name}/$objectId already exists in world $worldId" }
            Handle(identity, definition, sceneIndex).also {
                active[identity] = it
                outstanding += it
            }
        }
    }

    override fun closeOwner(owner: Plugin): CompletionStage<Void> {
        val handles = synchronized(registryLock) { outstanding.filter { it.identity.owner === owner } }
        handles.forEach(Handle::close)
        return CompletableFuture.allOf(*handles.map { it.cleanup }.toTypedArray()).minimalCompletionStage()
    }

    override fun close() {
        val handles = synchronized(registryLock) {
            if (closed) return
            closed = true
            worlds.values.forEach { it.available = false }
            worlds.clear()
            outstanding.toList()
        }
        for (handle in handles) {
            if (host.isEnabled) handle.close() else handle.unavailable(
                IllegalStateException("JavaRailways was disabled before model cleanup completed; " +
                    "stop add-ons and observe closeOwner completion before disabling the host")
            )
        }
        if (!host.isEnabled) tasks.clear()
    }

    private class WorldBinding(val bukkitWorld: BukkitWorld, val railWorld: World, val audiences: RenderChunkManager) {
        @Volatile var available = true
    }

    private class Identity(val owner: Plugin, val world: WorldBinding, val objectId: String) {
        override fun equals(other: Any?) = other is Identity && owner === other.owner && world === other.world && objectId == other.objectId
        override fun hashCode() = 31 * (31 * System.identityHashCode(owner) + System.identityHashCode(world)) + objectId.hashCode()
    }

    private class Pending(val presentation: ModelPresentation, val selection: OrdinarySceneIndex.Selection)

    private inner class Handle(
        val identity: Identity,
        private val definition: ModelDefinition,
        private val scenes: OrdinarySceneIndex
    ) : ModelHandle {
        private val stateLock = Any()
        @Volatile private var stopped = false
        private var revision = -1L
        private var pending: Pending? = null
        val cleanup = CompletableFuture<Void>()
        // Only the per-handle serial target task changes these; audience reads the adapter's volatile model.
        @Volatile private var model: OrdinaryModel? = null
        @Volatile private var audience: OrdinaryModelRenderable? = null
        private var applied: ModelPresentation? = null
        private var location: Location? = null

        override fun submit(presentation: ModelPresentation) {
            if (stopped || presentation.revision() <= synchronized(stateLock) { revision }) return
            val selection = scenes.resolve(presentation.animationScenes())
            synchronized(stateLock) {
                if (stopped || presentation.revision() <= revision) return
                if (!identity.world.available || !identity.owner.isEnabled) return
                revision = presentation.revision()
                pending = Pending(presentation, selection)
            }
            offer()
        }

        override fun close() {
            synchronized(stateLock) {
                if (stopped) return
                stopped = true
                pending = null
            }
            audience?.withdraw()
            synchronized(registryLock) { active.remove(identity, this) }
            offer()
        }

        private fun offer() {
            try { tasks.offer(this, this) } catch (failure: Throwable) { unavailable(failure) }
        }

        fun dispatch(complete: () -> Unit) {
            if (cleanup.isDone) { complete(); return }
            if (!host.isEnabled) { unavailable(IllegalStateException("Model scheduler host is disabled")); complete(); return }
            val current = model
            val action = { execute(complete) }
            try {
                val entity = current?.affinityEntity
                if (entity != null) {
                    // EntityScheduler false means neither callback was accepted; true guarantees one callback.
                    val retired = { dispatchAt(location!!, complete) }
                    if (!dispatcher.runForEntity(entity, action, retired)) retired()
                } else {
                    val target = location ?: synchronized(stateLock) { pending }?.presentation?.transform()?.let(::at)
                    if (target == null) { finish(null); complete() } else dispatchAt(target, complete)
                }
            } catch (failure: Throwable) { unavailable(failure); complete() }
        }

        private fun dispatchAt(target: Location, complete: () -> Unit) {
            try { dispatcher.runAt(target) { execute(complete) } }
            catch (failure: Throwable) { unavailable(failure); complete() }
        }

        private fun execute(complete: () -> Unit) {
            try {
                if (stopped || !identity.world.available || !identity.owner.isEnabled) {
                    destroyModel()
                    finish(null)
                    return
                }
                val next = synchronized(stateLock) { pending.also { pending = null } } ?: return
                val state = next.presentation
                if (applied?.transform() == state.transform() && applied?.animationScenes() == state.animationScenes()) {
                    applied = state
                    return
                }
                val previous = applied?.transform()
                if (model != null && previous != null && (previous.x() != state.transform().x() ||
                    previous.y() != state.transform().y() || previous.z() != state.transform().z())) {
                    destroyModel()
                    // Preserve a newer pending state if one arrived during the old-owner destruction.
                    synchronized(stateLock) { if (!stopped && pending == null) pending = next }
                    location = null
                    offer()
                    return
                }
                if (model == null) {
                    location = at(state.transform())
                    if (!dispatcher.owns(location!!)) {
                        synchronized(stateLock) { if (!stopped && pending == null) pending = next }
                        location = null
                        offer()
                        return
                    }
                    model = ModelTreeBuilder(identity.world.railWorld, backend, viewRange)
                        .buildModel(definition, location!!, scenes)
                }
                model!!.submit(state.transform(), next.selection)
                applied = state
                if (stopped || !identity.world.available || !identity.owner.isEnabled) {
                    destroyModel()
                    finish(null)
                    return
                }
                if (audience == null) {
                    val published = OrdinaryModelRenderable(identity.world.railWorld, ::close)
                    audience = published
                    published.publish(state.transform(), model!!)
                    identity.world.audiences.update(published, null, published.position)
                }
            } catch (failure: Throwable) {
                var cleanupFailure: Throwable? = failure.takeIf { it is DisplayCleanupException }
                try { destroyModel() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup); cleanupFailure = cleanup }
                report(failure)
                finish(cleanupFailure)
            } finally { complete() }
        }

        private fun at(pose: ModelPose) = Location(identity.world.bukkitWorld, pose.x(), pose.y(), pose.z())

        private fun destroyModel() {
            val oldAudience = audience
            audience = null
            oldAudience?.withdraw()
            var failure: Throwable? = null
            try { oldAudience?.let { identity.world.audiences.remove(it) } } catch (error: Throwable) { failure = error }
            val oldModel = model
            model = null
            applied = null
            try { oldModel?.destroy() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            failure?.let { throw DisplayCleanupException(it) }
        }

        fun unavailable(failure: Throwable) {
            audience?.withdraw()
            finish(failure)
        }

        private fun finish(failure: Throwable?) {
            synchronized(stateLock) { stopped = true; pending = null }
            synchronized(registryLock) {
                active.remove(identity, this)
                // A failed cleanup remains observable to closeOwner; never report its absence as success.
                if (failure == null) outstanding.remove(this)
            }
            if (failure == null) cleanup.complete(null) else if (cleanup.completeExceptionally(failure)) {
                report(failure)
            }
        }

        private fun report(failure: Throwable) = logger.log(
            Level.SEVERE, "Model presentation failed for plugin ${identity.owner.name}, object ${identity.objectId}", failure
        )
    }
}
