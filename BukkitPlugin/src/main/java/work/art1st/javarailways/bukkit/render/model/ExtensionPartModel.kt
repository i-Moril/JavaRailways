package work.art1st.javarailways.bukkit.render.model

import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderInput
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.javarailways.bukkit.api.internal.DefaultPartExtensionService
import work.art1st.javarailways.bukkit.api.internal.PartExtensionAttachment
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionText
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
    private var keyedTexts: Map<String, GeneratedText> = emptyMap()
    private class GeneratedText(val model: TextModel, val content: PartExtensionText)
    private var lastRoot: CompactTransform? = null
    private var lastVelocity: Vector3dc = Vector3d()
    private var destroyed = false
    private var applying: CompletableFuture<Void>? = null
    private val obsoleteCleanup = mutableListOf<CompletionStage<Void>>()
    private var firstSourceTick: Long? = null
    private var lastRequestedTick: Long? = null
    private var applyFailed = false
    private val viewers = linkedMapOf<UUID, Viewer>()
    private var screenPoint: ExtensionAudiencePoint? = null
    private var sharedRootPoint: ExtensionAudiencePoint? = null
    private var latestInput: PartExtensionRenderInput? = null
    private var latestSourceTick: Long? = null
    private var lastSourceData: JsonObject? = null
    private var policyFailed = false
    private var audiencePolicyReady = false
    private var observedRenderRevision = 0L
    private var revisionFailed = false
    private val attachment: PartExtensionAttachment? = service.createAttachment(
        typeId, context, refreshAudience = ::refreshAudience, refreshRendering = ::refreshRendering,
        retireVisuals = ::retireGenerated
    )

    val hasRenderer: Boolean get() = attachment?.canRender == true

    @Synchronized fun bindRoot(root: DisplayVisual) { check(this.root == null); this.root = root }

    /** A semantic context change must go blank before attempting replacement data. */
    @Synchronized fun invalidateContext() {
        if (destroyed) return
        attachment?.invalidateContext()
        lastRequestedTick = null
        latestInput = null
        latestSourceTick = null
        lastSourceData = null
        applyFailed = false
        // Preserve observers of every obsolete/in-flight cleanup until real model retirement.
        val cleanup = retireGenerated()
        obsoleteCleanup += cleanup
    }

    /** Unit/style changes invalidate only this group's generated frame, not the car model/session. */
    @Synchronized private fun refreshRendering() {
        if (destroyed) return
        val active = attachment ?: return
        val revision = try {
            active.renderRevision()?.also { revisionFailed = false } ?: return
        } catch (failure: Exception) {
            if (!revisionFailed) service.reportRuntimeFailure("Could not read extension render revision", failure)
            revisionFailed = true
            return
        }
        if (observedRenderRevision == revision) return
        observedRenderRevision = revision
        active.invalidateContext()
        lastRequestedTick = null
        applyFailed = false
        obsoleteCleanup += retireGenerated()
        requestLatest(force = true)
    }

    @Synchronized fun updateInput(data: JsonObject, tick: Long) {
        require(tick >= 0L) { "Extension source tick must be nonnegative" }
        if (destroyed || attachment?.isActive != true) return
        val first = firstSourceTick ?: tick.also { firstSourceTick = it }
        if (tick < first) return
        if (latestSourceTick != tick || lastSourceData !== data) {
            latestInput = PartExtensionRenderInput(data, tick - first)
            latestSourceTick = tick
            lastSourceData = data
        }
        requestLatest()
    }

    private fun requestLatest(force: Boolean = false) {
        val active = attachment ?: return
        if (!active.isActive) return
        val tick = latestSourceTick
        val last = lastRequestedTick
        if (!force && tick != null && last != null && tick - last < 10L) return
        val draw = viewers.values.any { it.visible }
        val input = latestInput ?: if (draw) PartExtensionRenderInput() else return
        active.request(input, draw = draw)
        lastRequestedTick = tick
    }

    @Synchronized
    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        lastRoot = CompactTransform(rootTransform)
        lastVelocity = Vector3d(rootVelocity)
        texts.forEach { it.updateState(state.rootRelativeTransform, rootTransform, rootVelocity, transformDirty) }
        // Parent/car rotations and independent part animations can move the screen while its root
        // remains still. Only immutable point/eye values cross the model/viewer owner boundary.
        val current = screenPoint
        val base = sharedRootPoint
        if (current != null && base != null) {
            // AbstractModel can replay an animation against the last full-walk root. Its local
            // offset remains correct; combine it with the latest root-only translation instead.
            val offset = state.rootRelativeTransform.position
            val point = current.copy(x = base.x + offset.x(), y = base.y + offset.y(), z = base.z + offset.z())
            if (point != current) {
                screenPoint = point
                viewers.values.toList().forEach(::scheduleRefresh)
            }
        }
    }

    /** Called only from the existing model owner commit, including far/root-only commits. */
    @Synchronized fun commitPending(location: Location) {
        val mountedRoot = root ?: return
        val oldRoot = lastRoot ?: return // No attachment before the first complete group transform.
        val active = attachment ?: return
        if (destroyed || !active.isActive) return
        // Warm compilation publishes the policy without drawing invisible/default output.
        active.start(initialDraw = false)
        val relative = state.rootRelativeTransform.position
        sharedRootPoint = ExtensionAudiencePoint(requireNotNull(location.world).uid, location.x, location.y, location.z)
        val point = ExtensionAudiencePoint(requireNotNull(location.world).uid,
            location.x + relative.x(), location.y + relative.y(), location.z + relative.z())
        val ready = active.audiencePolicy != null
        val policyChanged = audiencePolicyReady != ready
        audiencePolicyReady = ready
        if (screenPoint != point || policyChanged) {
            screenPoint = point
            viewers.values.toList().forEach(::scheduleRefresh)
        }
        refreshRendering()
        val frame = active.poll() ?: return
        obsoleteCleanup.removeAll { it.toCompletableFuture().let { future -> future.isDone && !future.isCompletedExceptionally } }
        val latestRoot = oldRoot.copy(position = Vector3d(location.x, location.y, location.z))
        val applyingCompletion = CompletableFuture<Void>()
        applying = applyingCompletion
        try {
            val changed = mutableListOf<GeneratedText>()
            val added = mutableListOf<TextModel>()
            val prepared = ModelConstruction.build { construction ->
                try {
                    // Finish every fallible allocation before mutating the last successful frame.
                    val generated = frame.texts.associate { text ->
                        if (!active.isActive || destroyed) throw CancellationException("Extension attachment was retired")
                        val previous = keyedTexts[text.key]
                        val model = previous?.model ?: run {
                            val visual = backend.create(requireNotNull(location.world), DisplayVisualDescriptor(
                                location.clone(), DisplayVisualContent.Text(text.text),
                                Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf()), viewRange,
                                TRAIN_DISPLAY_INTERPOLATION_TICKS, TRAIN_DISPLAY_TELEPORT_TICKS))
                            construction.own(visual, visual::destroy)
                            val created = TextModel(world, ModelState(text.pose.toCompactTransform(text.scale)), visual, text)
                            construction.own(created, created::destroy)
                            construction.transfer(visual)
                            created.mountTo(mountedRoot)
                            created.updateState(state.rootRelativeTransform, latestRoot, lastVelocity, true)
                            added += created
                            created
                        }
                        text.key to GeneratedText(model, text)
                    }
                    generated.forEach { (key, next) ->
                        val previous = keyedTexts[key] ?: return@forEach
                        if (!active.isActive || destroyed) throw CancellationException("Extension attachment was retired")
                        changed += previous
                        next.model.updateContent(next.content)
                        next.model.updateState(state.rootRelativeTransform, latestRoot, lastVelocity, true)
                    }
                    if (!active.isActive || destroyed) throw CancellationException("Extension attachment was retired")
                    added.forEach(construction::transfer)
                    generated
                } catch (failure: Throwable) {
                    // Provider retirement already owns the old frame; do not resurrect its content.
                    if (active.isActive && !destroyed) changed.asReversed().forEach { previous ->
                        try {
                            previous.model.updateContent(previous.content)
                            previous.model.updateState(state.rootRelativeTransform, latestRoot, lastVelocity, true)
                        } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
                    }
                    throw failure
                }
            }
            val removed = keyedTexts.filterKeys { it !in prepared }.values
            keyedTexts = prepared
            texts = prepared.values.map { it.model }
            val retirement = DestructionSteps()
            removed.forEach { old -> retirement.await { old.model.destroy() } }
            val retired = retirement.finish()
            obsoleteCleanup += retired
            retired.whenComplete { _, failure ->
                if (failure == null) applyingCompletion.complete(null) else applyingCompletion.completeExceptionally(failure)
            }
            applyFailed = false
            if (added.isNotEmpty()) viewers.values.toList().forEach { viewer -> replay(viewer, added, active) }
        } catch (failure: Throwable) {
            val cleanup = if (failure is DisplayCleanupException) failure.cleanup else CompletableFuture.completedFuture(null)
            cleanup.whenComplete { _, cleanupFailure ->
                if (cleanupFailure == null) applyingCompletion.complete(null) else applyingCompletion.completeExceptionally(cleanupFailure)
            }
            val cause = if (failure is DisplayCleanupException) failure.cause ?: failure else failure
            if (cause !is CancellationException) reportApplyFailure(cause)
            obsoleteCleanup += applyingCompletion
        } finally { if (applyingCompletion.isDone && !applyingCompletion.isCompletedExceptionally) applying = null }
    }

    private fun reportApplyFailure(failure: Throwable) {
        if (!applyFailed) service.reportRuntimeFailure("Could not apply extension text", failure)
        applyFailed = true
    }

    private fun eligible(viewer: Viewer, point: ExtensionAudiencePoint): Boolean {
        val policy = attachment?.audiencePolicy ?: return false
        val eye = viewer.eye ?: return false
        return try {
            val limit = policy.maximumDistanceBlocks(viewer.id)
            require(limit == null || limit.isFinite() && limit >= 0.0) { "Invalid extension audience distance: $limit" }
            policyFailed = false
            eye.isWithin(point, limit)
        } catch (failure: Exception) {
            if (!policyFailed) service.reportRuntimeFailure("Could not evaluate extension audience", failure)
            policyFailed = true
            false
        }
    }

    /** Caller owns this player's entity. No player getter is used on the model/worker side. */
    private fun refreshViewer(viewer: Viewer, replay: List<TextModel>? = null) {
        val active = attachment ?: return
        val point = screenPoint
        val eye = viewer.player.eyeLocation
        viewer.eye = ExtensionAudiencePoint(requireNotNull(eye.world).uid, eye.x, eye.y, eye.z)
        val visible = !destroyed && active.isActive && point != null && eligible(viewer, point)
        val changed = viewer.visible != visible
        viewer.visible = visible
        if (changed || visible && replay != null) bundles.frame {
            if (visible) (if (changed) texts else replay ?: texts).forEach { it.showVisualsTo(viewer.player) }
            else texts.forEach { it.hideVisualsFrom(viewer.player) }
            root?.refreshPassengersFor(viewer.player)
        }
        if (changed) {
            if (visible) requestLatest(force = true)
            else if (viewers.values.none { it.visible }) {
                active.invalidateContext() // Reject an invisible in-flight/queued frame as well.
                lastRequestedTick = null
            }
        }
    }

    /** At most one queued refresh per existing viewer; the callback uses the latest point/policy. */
    private fun scheduleRefresh(viewer: Viewer) {
        if (destroyed || viewers[viewer.id] !== viewer || viewer.refreshScheduled) return
        // If both values are known and the decision is unchanged, geometry causes no extra task.
        val point = screenPoint
        if (point != null && viewer.eye != null && eligible(viewer, point) == viewer.visible) return
        viewer.refreshScheduled = true
        try {
            val accepted = dispatcher.runForEntity(viewer.player, action = {
                synchronized(this) {
                    viewer.refreshScheduled = false
                    if (destroyed || attachment?.isActive != true || viewers[viewer.id] !== viewer) return@runForEntity
                    refreshViewer(viewer)
                }
            }, retired = { synchronized(this) { removeViewer(viewer) } })
            if (!accepted) removeViewer(viewer)
        } catch (failure: RuntimeException) {
            viewer.refreshScheduled = false
            service.reportRuntimeFailure("Could not refresh extension audience", failure)
        }
    }

    @Synchronized private fun refreshAudience(viewerId: UUID) {
        viewers[viewerId]?.let { viewer ->
            // Settings changed even when the previous position-based decision looked unchanged.
            viewer.eye = null
            scheduleRefresh(viewer)
        }
    }

    private fun removeViewer(viewer: Viewer) {
        if (viewers[viewer.id] !== viewer) return
        viewers.remove(viewer.id)
        if (viewer.visible && viewers.values.none { it.visible }) {
            attachment?.invalidateContext()
            lastRequestedTick = null
        }
    }

    private fun replay(viewer: Viewer, prepared: List<TextModel>, active: PartExtensionAttachment) {
        try {
            dispatcher.runForEntity(viewer.player, action = {
                synchronized(this) {
                    if (destroyed || !active.isActive || viewers[viewer.id] !== viewer) return@runForEntity
                    val current = prepared.filter { it in texts }
                    if (current.isNotEmpty()) refreshViewer(viewer, current)
                }
            }, retired = { synchronized(this) { removeViewer(viewer) } })
        } catch (failure: RuntimeException) { service.reportRuntimeFailure("Could not expose extension text to its existing viewer", failure) }
    }

    @Synchronized override fun showVisualsTo(player: Player) {
        if (destroyed) return
        val id = player.uniqueId
        val viewer = viewers[id]?.takeIf { it.player === player } ?: Viewer(id, player).also { viewers[id] = it }
        refreshViewer(viewer)
        if (viewer.visible) texts.forEach { it.showVisualsTo(player) }
        super.showVisualsTo(player)
    }
    @Synchronized override fun refreshVisualsFor(player: Player) {
        viewers[player.uniqueId]?.takeIf { it.player === player }?.let { refreshViewer(it) }
        super.refreshVisualsFor(player)
    }
    @Synchronized override fun hideVisualsFrom(player: Player) {
        val viewer = viewers[player.uniqueId]?.takeIf { it.player === player } ?: return
        removeViewer(viewer)
        texts.forEach { it.hideVisualsFrom(player) }
        super.hideVisualsFrom(player)
    }

    @Synchronized private fun retireGenerated(): CompletionStage<Void> {
        val steps = DestructionSteps()
        applying?.let { completion -> steps.await { completion } }
        obsoleteCleanup.forEach { completion -> steps.await { completion } }
        obsoleteCleanup.clear()
        val old = texts
        texts = emptyList()
        keyedTexts = emptyMap()
        old.forEach { text -> steps.await { text.destroy() } }
        return steps.finish()
    }

    @Synchronized override fun collectDestruction(steps: DestructionSteps) {
        destroyed = true
        viewers.clear()
        attachment?.let { active -> steps.await { active.closeAsync() } }
        super.collectDestruction(steps)
    }

    private class Viewer(val id: UUID, val player: Player) {
        var eye: ExtensionAudiencePoint? = null
        var visible = false
        var refreshScheduled = false
    }
}
