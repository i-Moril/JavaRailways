package work.art1st.javarailways.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.javarailways.bukkit.api.internal.DefaultPartExtensionService
import work.art1st.javarailways.bukkit.api.internal.PartExtensionAttachment
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.render.backend.*
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.DestructionSteps
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Original group/children stay intact; generated leaves are deliberately absent from animation-slot traversal. */
internal class ExtensionPartModel(
    world: World,
    state: ModelState,
    children: Collection<AbstractModel>,
    animations: Map<String, Animation>,
    typeId: String,
    context: PartExtensionRenderContext,
    private val service: DefaultPartExtensionService,
    private val backend: DisplayRenderBackend,
    private val dispatcher: PlatformDispatcher,
    private val bundles: TrainPacketBundleCoordinator,
    private val viewRange: Float
) : AbstractModel(world, state, children, animations) {
    private var root: DisplayVisual? = null
    private var texts: List<TextModel> = emptyList()
    private var lastRoot: CompactTransform? = null
    private var lastVelocity: Vector3dc = Vector3d()
    private var destroyed = false
    private var applying: CompletableFuture<Void>? = null
    private val viewers = linkedMapOf<UUID, Viewer>()
    private val attachment: PartExtensionAttachment? = service.createAttachment(typeId, context, ::retireGenerated)

    @Synchronized fun bindRoot(root: DisplayVisual) { check(this.root == null); this.root = root }

    @Synchronized
    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        lastRoot = CompactTransform(rootTransform)
        lastVelocity = Vector3d(rootVelocity)
        texts.forEach { it.updateState(state.rootRelativeTransform, rootTransform, rootVelocity, transformDirty) }
    }

    /** Called only from the existing model owner commit, including far/root-only commits. */
    @Synchronized fun commitPending(location: Location) {
        val mountedRoot = root ?: return
        val oldRoot = lastRoot ?: return // No attachment before the first complete group transform.
        val active = attachment ?: return
        if (destroyed || !active.isActive) return
        active.start()
        val frame = active.poll() ?: return
        if (frame.texts.isEmpty()) return
        val applyingCompletion = CompletableFuture<Void>()
        applying = applyingCompletion
        try {
            val prepared = ModelConstruction.build { construction ->
                val generated = frame.texts.map { text ->
                    if (!active.isActive || destroyed) throw CancellationException("Extension attachment was retired")
                    val visual = backend.create(requireNotNull(location.world), DisplayVisualDescriptor(
                        location.clone(), DisplayVisualContent.Text(text.text),
                        Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf()), viewRange,
                        TRAIN_DISPLAY_INTERPOLATION_TICKS, TRAIN_DISPLAY_TELEPORT_TICKS))
                    construction.own(visual, visual::destroy)
                    val model = TextModel(world, ModelState(text.pose.toCompactTransform(text.scale)), visual)
                    construction.own(model, model::destroy)
                    construction.transfer(visual)
                    model.mountTo(mountedRoot)
                    val latestRoot = oldRoot.copy(position = Vector3d(location.x, location.y, location.z))
                    model.updateState(state.rootRelativeTransform, latestRoot, lastVelocity, true)
                    model
                }
                if (!active.isActive || destroyed) throw CancellationException("Extension attachment was retired")
                generated.forEach(construction::transfer)
                generated
            }
            texts = prepared
            applyingCompletion.complete(null)
            viewers.values.toList().forEach { viewer -> replay(viewer, prepared, active, mountedRoot) }
        } catch (failure: Throwable) {
            val cleanup = if (failure is DisplayCleanupException) failure.cleanup else CompletableFuture.completedFuture(null)
            cleanup.whenComplete { _, cleanupFailure ->
                if (cleanupFailure == null) applyingCompletion.complete(null) else applyingCompletion.completeExceptionally(cleanupFailure)
            }
            val cause = if (failure is DisplayCleanupException) failure.cause ?: failure else failure
            if (cause !is CancellationException) service.reportRuntimeFailure("Could not apply extension text", cause)
        } finally { if (applyingCompletion.isDone && !applyingCompletion.isCompletedExceptionally) applying = null }
    }

    private fun replay(viewer: Viewer, prepared: List<TextModel>, active: PartExtensionAttachment, mountedRoot: DisplayVisual) {
        try {
            dispatcher.runForEntity(viewer.player, action = {
                synchronized(this) {
                    if (destroyed || !active.isActive || viewers[viewer.id] !== viewer || texts !== prepared) return@runForEntity
                    bundles.frame {
                        prepared.forEach { it.showVisualsTo(viewer.player) }
                        mountedRoot.refreshPassengersFor(viewer.player)
                    }
                }
            }, retired = { synchronized(this) { if (viewers[viewer.id] === viewer) viewers.remove(viewer.id) } })
        } catch (failure: RuntimeException) { service.reportRuntimeFailure("Could not expose extension text to its existing viewer", failure) }
    }

    @Synchronized override fun showVisualsTo(player: Player) {
        if (destroyed) return
        val id = player.uniqueId
        if (viewers[id]?.player !== player) viewers[id] = Viewer(id, player)
        if (attachment?.isActive == true) texts.forEach { it.showVisualsTo(player) }
        super.showVisualsTo(player)
    }
    @Synchronized override fun hideVisualsFrom(player: Player) {
        val id = player.uniqueId
        if (viewers[id]?.player !== player) return
        viewers.remove(id)
        texts.forEach { it.hideVisualsFrom(player) }
        super.hideVisualsFrom(player)
    }

    @Synchronized private fun retireGenerated(): CompletionStage<Void> {
        val steps = DestructionSteps()
        applying?.let { completion -> steps.await { completion } }
        val old = texts
        texts = emptyList()
        old.forEach { text -> steps.await { text.destroy() } }
        return steps.finish()
    }

    @Synchronized override fun collectDestruction(steps: DestructionSteps) {
        destroyed = true
        viewers.clear()
        attachment?.let { active -> steps.await { active.closeAsync() } }
        super.collectDestruction(steps)
    }

    private class Viewer(val id: UUID, val player: Player)
}
