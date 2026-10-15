package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.editor.EditorTextHandle
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import work.art1st.javarailways.bukkit.api.editor.EditorTextService
import work.art1st.javarailways.bukkit.util.LatestAffinityTaskDispatcher
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** All entity work is serialized per handle, with at most one latest pending complete list. */
internal class DefaultEditorTextService(
    private val host: Plugin,
    private val dispatcher: PlatformDispatcher,
    private val logger: Logger
) : EditorTextService, AutoCloseable {
    private val registryLock = Any()
    private val active = mutableMapOf<Identity, Handle>()
    // Failed cleanup remains observable, including handles individually closed before closeOwner.
    private val outstanding = mutableSetOf<Handle>()
    @Volatile private var closed = false
    private val tasks = LatestAffinityTaskDispatcher<Handle, Handle> { handle, complete ->
        try { dispatcher.runAsync { handle.reconcile(complete) } }
        catch (failure: Throwable) { handle.rejected(failure, complete) }
    }

    override fun create(owner: Plugin, viewer: Player, objectId: String): EditorTextHandle {
        require(objectId.isNotBlank()) { "Editor text object ID must not be blank" }
        check(dispatcher.owns(viewer)) { "Editor text creation must run on the viewer owner" }
        check(viewer.isOnline) { "Editor text viewer is offline" }
        val world = viewer.world
        check(host.server.getWorld(world.uid) === world) { "Editor text world is not loaded" }
        return synchronized(registryLock) {
            check(!closed && host.isEnabled) { "Editor text host is unavailable" }
            check(owner.isEnabled) { "Editor text owner ${owner.name} is disabled" }
            val identity = Identity(owner, viewer, world, objectId)
            check(identity !in active) { "Active editor text ${owner.name}/$objectId already exists" }
            Handle(identity).also { active[identity] = it; outstanding += it }
        }
    }

    override fun closeOwner(owner: Plugin): CompletionStage<Void> = closeMatching { it.owner === owner }

    fun closeViewer(viewer: Player) { closeMatching { it.viewer === viewer } }
    fun removeWorld(world: World) { closeMatching { it.world === world } }

    private fun closeMatching(matches: (Identity) -> Boolean): CompletionStage<Void> {
        val handles = synchronized(registryLock) { outstanding.filter { matches(it.identity) } }
        handles.forEach(Handle::close)
        return CompletableFuture.allOf(*handles.map { it.cleanup }.toTypedArray()).minimalCompletionStage()
    }

    override fun close() {
        val handles = synchronized(registryLock) {
            if (closed) return
            closed = true
            outstanding.toList()
        }
        handles.forEach {
            if (host.isEnabled) it.close() else it.finish(IllegalStateException(
                "JavaRailways was disabled before editor text cleanup completed; observe closeOwner before disabling the host"
            ))
        }
    }

    private class Identity(val owner: Plugin, val viewer: Player, val world: World, val objectId: String) {
        override fun equals(other: Any?) = other is Identity && owner === other.owner &&
            viewer === other.viewer && world === other.world && objectId == other.objectId
        override fun hashCode() = 31 * (31 * (31 * System.identityHashCode(owner) +
            System.identityHashCode(viewer)) + System.identityHashCode(world)) + objectId.hashCode()
    }

    private class Shown(val label: EditorTextLabel, val entity: TextDisplay)
    private class CleanupFailure(cause: Throwable) : RuntimeException(cause)

    private inner class Handle(val identity: Identity) : EditorTextHandle {
        private val stateLock = Any()
        @Volatile private var stopped = false
        private var revision = -1L
        private var pending: List<EditorTextLabel>? = null
        // Accessed only in the serial reconciliation chain, never under the state lock.
        private val shown = linkedMapOf<String, Shown>()
        val cleanup = CompletableFuture<Void>()

        override fun submit(revision: Long, labels: List<EditorTextLabel>) {
            if (stopped) return
            require(revision >= 0) { "Editor text revision must be nonnegative" }
            if (revision <= synchronized(stateLock) { this.revision }) return
            val copy = java.util.List.copyOf(labels)
            require(copy.map { it.id() }.toSet().size == copy.size) { "Duplicate editor text label ID" }
            synchronized(stateLock) {
                if (stopped || revision <= this.revision) return
                if (!identity.owner.isEnabled || closed) return
                this.revision = revision
                pending = copy
            }
            offer()
        }

        override fun close() {
            synchronized(stateLock) {
                if (stopped) return
                stopped = true
                pending = null
            }
            synchronized(registryLock) { active.remove(identity, this) }
            offer()
        }

        private fun offer() {
            try { tasks.offer(this, this) } catch (failure: Throwable) { finish(failure) }
        }

        private fun live() = !stopped && !closed && host.isEnabled && identity.owner.isEnabled

        fun rejected(failure: Throwable, complete: () -> Unit) {
            report(failure)
            synchronized(stateLock) { stopped = true; pending = null }
            // This is still the serial dispatcher slot: rejection must not abandon existing entities.
            clearAll().whenComplete { _, cleanupFailure ->
                if (cleanupFailure != null) failure.addSuppressed(cleanupFailure)
                finish(failure)
                complete()
            }
        }

        fun reconcile(complete: () -> Unit) {
            if (cleanup.isDone) { complete(); return }
            val next = synchronized(stateLock) { pending.also { pending = null } }
            val work = if (!live()) clearAll() else onViewer {
                if (!identity.viewer.isOnline || identity.viewer.world !== identity.world) close()
            }.thenCompose {
                if (!live()) clearAll() else apply(next)
            }
            work.whenComplete { _, failure ->
                if (failure == null) {
                    if (!live()) finish(null)
                    complete()
                } else {
                    report(failure)
                    synchronized(stateLock) { stopped = true; pending = null }
                    clearAll().whenComplete { _, cleanupFailure ->
                        val removalFailed = generateSequence(failure) { it.cause }.any { it is CleanupFailure }
                        finish(cleanupFailure ?: failure.takeIf { removalFailed })
                        complete()
                    }
                }
            }
        }

        private fun apply(labels: List<EditorTextLabel>?): CompletableFuture<Void> {
            if (labels == null) return CompletableFuture.completedFuture(null)
            val desired = labels.associateBy { it.id() }
            var chain = CompletableFuture.completedFuture<Void>(null)
            for ((id, old) in shown.toMap()) {
                val label = desired[id]
                if (label == null || old.label.x() != label.x() || old.label.y() != label.y() || old.label.z() != label.z()) {
                    chain = chain.thenCompose { remove(id, old) }
                }
            }
            for (label in labels) chain = chain.thenCompose {
                if (!live()) CompletableFuture.completedFuture(null) else {
                    val old = shown[label.id()]
                    when {
                        old == null -> spawn(label)
                        old.label == label -> CompletableFuture.completedFuture(null)
                        else -> onEntity(old.entity, at(old.label)) {
                            if (live()) { old.entity.text(label.text()); shown[label.id()] = Shown(label, old.entity) }
                        }
                    }
                }
            }
            return chain.thenCompose { if (!live()) clearAll() else CompletableFuture.completedFuture(null) }
        }

        private fun spawn(label: EditorTextLabel): CompletableFuture<Void> {
            val target = at(label)
            return scheduled({ callback, _ -> dispatcher.runAt(target, callback) }) {
                if (live()) {
                    val entity = identity.world.spawn(target, TextDisplay::class.java) {
                        // Keep the allocated entity reachable even if an initializer setter fails.
                        shown[label.id()] = Shown(label, it)
                        it.isVisibleByDefault = false
                        it.isPersistent = false
                        it.billboard = Display.Billboard.CENTER
                        it.isShadowed = true
                        it.text(label.text())
                    }
                    shown[label.id()] = Shown(label, entity)
                }
            }.thenCompose {
                val created = shown[label.id()] ?: return@thenCompose CompletableFuture.completedFuture(null)
                if (!live()) remove(label.id(), created) else onViewer {
                    if (live() && identity.viewer.isOnline && identity.viewer.world === identity.world) {
                        identity.viewer.showEntity(host, created.entity)
                    } else close()
                }.thenCompose { if (!live()) remove(label.id(), created) else CompletableFuture.completedFuture(null) }
            }
        }

        private fun at(label: EditorTextLabel) = Location(identity.world, label.x(), label.y(), label.z())

        private fun remove(id: String, old: Shown): CompletableFuture<Void> = onEntity(old.entity, at(old.label)) {
            old.entity.remove()
            shown.remove(id, old)
        }.handle { _, failure ->
            if (failure != null) throw CleanupFailure(failure)
            null
        }

        private fun clearAll(): CompletableFuture<Void> {
            var chain = CompletableFuture.completedFuture<Void>(null)
            var failure: Throwable? = null
            for ((id, old) in shown.toMap()) chain = chain.thenCompose {
                remove(id, old).handle { _, error ->
                    if (error != null) {
                        if (failure == null) failure = error else failure.addSuppressed(error)
                    }
                    null
                }
            }
            return chain.thenApply { failure?.let { throw it }; null }
        }

        private fun onViewer(action: () -> Unit) = scheduled({ callback, failed ->
            val retired = { failed(IllegalStateException("Editor text viewer retired")) }
            if (!dispatcher.runForEntity(identity.viewer, callback, retired)) retired()
        }, action)

        private fun onEntity(entity: Entity, target: Location, action: () -> Unit): CompletableFuture<Void> =
            scheduled({ callback, failed ->
                val fallback = {
                    try { dispatcher.runAt(target, callback) } catch (failure: Throwable) { failed(failure) }
                }
                if (!dispatcher.runForEntity(entity, callback, fallback)) fallback()
            }, action)

        private fun scheduled(schedule: (() -> Unit, (Throwable) -> Unit) -> Unit, action: () -> Unit): CompletableFuture<Void> {
            val result = CompletableFuture<Void>()
            val claimed = AtomicBoolean()
            val failed = { failure: Throwable ->
                if (claimed.compareAndSet(false, true)) result.completeExceptionally(failure)
                Unit
            }
            try {
                check(host.isEnabled) { "Editor text scheduler host is disabled" }
                schedule({
                    if (claimed.compareAndSet(false, true)) {
                        try {
                            check(host.isEnabled) { "Editor text scheduler host is disabled" }
                            action()
                            result.complete(null)
                        } catch (failure: Throwable) { result.completeExceptionally(failure) }
                    }
                }, failed)
            } catch (failure: Throwable) { failed(failure) }
            return result
        }

        fun finish(failure: Throwable?) {
            synchronized(stateLock) { stopped = true; pending = null }
            synchronized(registryLock) {
                active.remove(identity, this)
                if (failure == null) outstanding.remove(this)
            }
            if (failure == null) cleanup.complete(null) else if (cleanup.completeExceptionally(failure)) report(failure)
        }

        private fun report(failure: Throwable) = logger.log(Level.SEVERE,
            "Editor text failed for plugin ${identity.owner.name}, object ${identity.objectId}", failure)
    }
}
