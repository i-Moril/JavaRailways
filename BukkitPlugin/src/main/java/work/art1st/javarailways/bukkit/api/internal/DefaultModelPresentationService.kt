package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.entity.Player
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import work.art1st.javarailways.bukkit.render.backend.EditorModelGlowPackets
import org.bukkit.Location
import org.bukkit.World as BukkitWorld
import org.bukkit.plugin.Plugin
import org.joml.Quaterniond
import org.joml.Vector3d
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
import work.art1st.javarailways.core.api.util.DestructionSteps
import work.art1st.javarailways.core.api.physics.World
import java.util.UUID
import java.util.Optional
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
    private val highlights = mutableSetOf<Highlight>()
    private var glowPackets: EditorModelGlowPackets? = null
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
            highlights.filter { it.world === binding }.forEach { it.close() }
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

    override fun createEditorHighlight(owner: Plugin, viewer: Player, sessionId: String): EditorModelHighlightHandle {
        require(sessionId.isNotBlank()) { "Editor highlight session ID must not be blank" }
        check(dispatcher.owns(viewer)) { "Editor highlight creation must run on viewer owner" }
        check(viewer.isOnline) { "Editor highlight viewer is offline" }
        val world = viewer.world
        val viewerId = viewer.uniqueId
        return synchronized(registryLock) {
            check(!closed && host.isEnabled && owner.isEnabled) { "Editor highlight owner/host unavailable" }
            val binding = worlds[world.uid]?.takeIf { it.bukkitWorld === world }
                ?: error("Editor highlight world is not registered")
            check(highlights.none { it.owner === owner && it.viewer === viewer && it.world === binding && it.sessionId == sessionId }) {
                "Active editor highlight session already exists"
            }
            Highlight(owner, viewer, viewerId, binding, sessionId).also { highlights += it }
        }
    }

    fun closeViewer(viewer: Player) {
        synchronized(registryLock) {
            highlights.filter { it.viewer === viewer }.forEach { it.close() }
            glowPackets?.forget(viewer.uniqueId)
        }
    }

    private inner class Highlight(val owner: Plugin, val viewer: Player, val viewerId: UUID,
                                 val world: WorldBinding, val sessionId: String) : EditorModelHighlightHandle {
        var stopped = false
        var revision = -1L
        var objects: Set<String> = emptySet()
        var rgb = 0
        override fun submit(revision: Long, objectIds: Set<String>, rgb: Int) {
            require(revision >= 0 && rgb in 0..0xffffff && objectIds.all { it.isNotBlank() }) { "Invalid highlight submission" }
            val copy = objectIds.toSet()
            synchronized(registryLock) {
                if (stopped || revision <= this.revision || !world.available || !owner.isEnabled) return
                this.revision = revision
                this.objects = copy
                this.rgb = rgb
                refreshHighlights()
            }
        }
        override fun close() {
            synchronized(registryLock) {
                if (stopped) return
                stopped = true
                objects = emptySet()
                // Keep viewer discoverable while reset packets are scheduled.
                refreshHighlights()
                highlights.remove(this)
            }
        }
    }

    /** Created before publishing the first actual display, so every outgoing baseline is observed. */
    private fun ensureGlowPackets(): EditorModelGlowPackets {
        return glowPackets ?: EditorModelGlowPackets { id, entityId, current ->
            val target = synchronized(registryLock) { highlights.firstOrNull { it.viewerId == id }?.viewer }
            if (target != null) dispatcher.runForEntity(target, action = {
                if (target.isOnline) current()?.let { entries ->
                    PacketEvents.getAPI().playerManager.sendPacketSilently(target, WrapperPlayServerEntityMetadata(entityId, entries))
                }
            })
        }.also { glowPackets = it }
    }

    /** Called under registryLock; exact existing identities only, without reading Bukkit entities. */
    private fun refreshHighlights(only: Handle? = null) {
        val packets = glowPackets ?: return
        for (handle in if (only == null) outstanding else listOf(only)) {
            val identity = handle.identity
            val colors = highlights.filter { !it.stopped && it.owner === identity.owner && it.world === identity.world &&
                identity.objectId in it.objects }.associate { it.viewerId to it.rgb }
            handle.model?.packetEntityIds?.forEach { packets.select(it, colors) }
        }
    }

    override fun closeOwner(owner: Plugin): CompletionStage<Void> {
        val handles = synchronized(registryLock) {
            highlights.filter { it.owner === owner }.forEach { it.close() }
            outstanding.filter { it.identity.owner === owner }
        }
        handles.forEach(Handle::close)
        return CompletableFuture.allOf(*handles.map { it.cleanup }.toTypedArray()).minimalCompletionStage()
    }

    override fun pick(owner: Plugin, worldId: UUID, ray: ModelRay): Optional<ModelPick> = synchronized(registryLock) {
        check(!closed && host.isEnabled) { "Model presentation host is unavailable" }
        check(owner.isEnabled) { "Model owner ${owner.name} is disabled" }
        val binding = worlds[worldId] ?: error("Model presentation world $worldId is not loaded")
        var nearest: ModelPick? = null
        for ((identity, handle) in active) {
            if (identity.owner !== owner || identity.world !== binding) continue
            val hit = handle.pick(ray) ?: continue
            val previous = nearest
            if (previous == null || hit.distance() < previous.distance() ||
                (hit.distance() == previous.distance() && hit.objectId() < previous.objectId())) nearest = hit
        }
        Optional.ofNullable(nearest)
    }

    override fun close() {
        val handles = synchronized(registryLock) {
            if (closed) return
            highlights.toList().forEach { it.close() }
            glowPackets?.close()
            glowPackets = null
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
        // Selection follows accepted submissions even while rendering is queued/coalesced.
        private var accepted: ModelPresentation? = null
        val cleanup = CompletableFuture<Void>()
        // Only the per-handle serial target task changes these; audience reads the adapter's volatile model.
        @Volatile var model: OrdinaryModel? = null
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
                accepted = presentation
            }
            offer()
        }

        override fun close() {
            synchronized(stateLock) {
                if (stopped) return
                stopped = true
                pending = null
                accepted = null
            }
            audience?.withdraw()
            synchronized(registryLock) { active.remove(identity, this) }
            offer()
        }

        fun pick(ray: ModelRay): ModelPick? = synchronized(stateLock) {
            if (stopped || !identity.world.available || !identity.owner.isEnabled || definition.selectionBoxes().isEmpty()) return null
            val presentation = accepted ?: return null
            val pose = presentation.transform()
            val inverse = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW()).invert()
            val origin = inverse.transform(Vector3d(ray.originX() - pose.x(), ray.originY() - pose.y(), ray.originZ() - pose.z()))
            val direction = inverse.transform(Vector3d(ray.directionX(), ray.directionY(), ray.directionZ()))
            var nearest: Double? = null
            for (box in definition.selectionBoxes()) {
                val distance = intersect(box, origin, direction, ray.maxDistance()) ?: continue
                if (nearest == null || distance < nearest) nearest = distance
            }
            nearest?.let { ModelPick(identity.objectId, it, presentation.revision()) }
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
            var completingAsynchronously = false
            try {
                if (stopped || !identity.world.available || !identity.owner.isEnabled) {
                    completingAsynchronously = true
                    destroyModel().whenComplete { _, failure ->
                        try { finish(failure) } finally { complete() }
                    }
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
                    completingAsynchronously = true
                    destroyModel().whenComplete { _, failure ->
                        try {
                            if (failure != null) {
                                finish(failure)
                            } else {
                                // Rebuild only after the old model's resources have been removed.
                                synchronized(stateLock) { if (!stopped && pending == null) pending = next }
                                location = null
                                offer()
                            }
                        } finally { complete() }
                    }
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
                    synchronized(registryLock) {
                        val baselines = model!!.packetGlowBaselines
                        if (baselines.isNotEmpty()) {
                            val packets = ensureGlowPackets()
                            baselines.forEach { (id, baseline) -> packets.register(id, baseline) }
                        }
                        refreshHighlights(this)
                    }
                }
                model!!.submit(state.transform(), next.selection)
                applied = state
                if (stopped || !identity.world.available || !identity.owner.isEnabled) {
                    completingAsynchronously = true
                    destroyModel().whenComplete { _, failure ->
                        try { finish(failure) } finally { complete() }
                    }
                    return
                }
                if (audience == null) {
                    val published = OrdinaryModelRenderable(identity.world.railWorld) {
                        close()
                        cleanup.minimalCompletionStage()
                    }
                    audience = published
                    published.publish(state.transform(), model!!)
                    identity.world.audiences.update(published, null, published.position)
                }
            } catch (failure: Throwable) {
                completingAsynchronously = true
                val steps = DestructionSteps()
                if (failure is DisplayCleanupException) steps.await { failure.cleanup }
                steps.await { destroyModel() }
                steps.finish().whenComplete { _, cleanupFailure ->
                    try {
                        if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                        report(failure)
                        finish(cleanupFailure)
                    } finally { complete() }
                }
            } finally { if (!completingAsynchronously) complete() }
        }

        private fun at(pose: ModelPose) = Location(identity.world.bukkitWorld, pose.x(), pose.y(), pose.z())

        private fun destroyModel(): CompletionStage<Void> {
            val oldAudience = audience
            audience = null
            val oldModel = model
            synchronized(registryLock) { oldModel?.packetEntityIds?.forEach { glowPackets?.remove(it) } }
            model = null
            applied = null
            val steps = DestructionSteps()
            steps.action { oldAudience?.withdraw() }
            if (oldAudience != null) steps.await { identity.world.audiences.remove(oldAudience) }
            if (oldModel != null) steps.await { oldModel.destroy() }
            return steps.finish()
        }

        fun unavailable(failure: Throwable) {
            audience?.withdraw()
            finish(failure)
        }

        private fun finish(failure: Throwable?) {
            synchronized(stateLock) { stopped = true; pending = null; accepted = null }
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

    /** Slab intersection in root-local metres; inverse rotation preserves ray distance. */
    private fun intersect(box: ModelSelectionBox, origin: Vector3d, direction: Vector3d, maxDistance: Double): Double? {
        var near = 0.0
        var far = maxDistance
        for (axis in 0..2) {
            val min = when (axis) { 0 -> box.minX(); 1 -> box.minY(); else -> box.minZ() }
            val max = when (axis) { 0 -> box.maxX(); 1 -> box.maxY(); else -> box.maxZ() }
            val position = origin[axis]
            val delta = direction[axis]
            if (delta == 0.0) {
                if (position < min || position > max) return null
            } else {
                val first = (min - position) / delta
                val second = (max - position) / delta
                near = maxOf(near, minOf(first, second))
                far = minOf(far, maxOf(first, second))
                if (near > far) return null
            }
        }
        return near
    }
}
