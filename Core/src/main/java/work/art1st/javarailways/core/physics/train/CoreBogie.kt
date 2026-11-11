package work.art1st.javarailways.core.physics.train

import java.util.concurrent.CompletionStage
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.unaryMinus
import work.art1st.javarailways.core.api.physics.exception.BogieException
import work.art1st.javarailways.core.api.physics.rail.Reversible
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.api.physics.train.Bogie
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.render.RenderableStateSnapshot
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot
import work.art1st.javarailways.core.api.render.Vector3Snapshot
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.api.render.DynamicRenderable
import work.art1st.javarailways.core.api.util.plugin.Extendable
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import work.art1st.javarailways.core.api.util.plugin.collectSavedExtensionData
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.util.CommitBuffer
import work.art1st.configLib.persistence.PersistentBy

/**
 * 转向架类
 * 转向架位于铁轨上，即转向架坐标位于曲线上，方向为曲线的切线方向
 */
class CoreBogie @MainThread internal constructor(
    location: RailLocationWithFacing,
    /* isSameDirectionAsFacing: direction是否与location的facing一致. */
    isSameDirectionAsFacing: Boolean = true,
    savedExtensionData: Map<String, SerializableExtensionData>,
    val extensionDataContainer: ExtensionDataContainer<RenderableExtension<Bogie>> = ExtensionDataContainer()
) : Bogie, DynamicRenderable, Reversible, Extendable<RenderableExtension<Bogie>> by extensionDataContainer, PersistentBy<SavedBogieV1> {

    companion object {
        @JvmStatic
        fun create(
            location: RailLocationWithFacing,
            extensionData: Map<String, SerializableExtensionData> = emptyMap()
        ): CoreBogie = CoreBogie(location, true, extensionData)
    }
    private val sameDirectionBuffer = CommitBuffer(isSameDirectionAsFacing)
    private val publishedSameDirectionAsFacing: Boolean get() = sameDirectionBuffer.get()

    private val _location = CommitBuffer(location)
    override var location: RailLocationWithFacing by _location
        private set
    internal val activeLocation
        get() = if (locationPhysicsDirty) _location.getActive() else _location.get()

    override val position
        get() = location.position
    /* direction: Asset显示的方向 */
    override val direction
        get() = if (publishedSameDirectionAsFacing) location.tangent else -location.tangent
    override val normal
        get() = location.normal
    private val _velocity: CommitBuffer<Vector3dc> = CommitBuffer(Vector3d(0.0, 0.0, 0.0))
    /* velocity: 速度的大小和方向 */
    override var velocity: Vector3dc by _velocity
        internal set
    internal val activeVelocity
        get() = if (velocityPhysicsDirty) _velocity.getActive() else _velocity.get()

    override val world
        get() = location.rail.world

    override val initialRenderState: RenderableStateSnapshot = initialRenderSnapshot()
    private var locationPhysicsDirty = false
    private var velocityPhysicsDirty = false

    init {
        extensionDataContainer.extensionData.forEach { it?.initialize(this, savedExtensionData) }
    }

    private fun initialRenderSnapshot(): RenderableStateSnapshot = RenderableStateSnapshot(
        position = position,
        direction = direction,
        normal = normal,
        velocity = velocity,
        world = world
    )

    private val destruction = Destruction()

    override fun destroy(): CompletionStage<Void> = destruction.run {
        val steps = DestructionSteps()
        extensionDataContainer.extensionData.filterNotNull().forEach { steps.await { it.destroy() } }
        steps.finish()
    }

    @Throws(BogieException::class)
    override fun moveForward(distance: Double) {
        updateLocation(activeLocation.forward(distance) ?: throw BogieException(this))
    }

    /** Publishes Core state off the Bukkit main thread after all physics workers complete. */
    internal fun publishPhysicsState() {
        if (locationPhysicsDirty) {
            _location.commit()
            locationPhysicsDirty = false
        }
        if (velocityPhysicsDirty) {
            _velocity.commit()
            velocityPhysicsDirty = false
        }
    }

    /** Discard an unsuccessful worker step without publishing either buffer slot. */
    internal fun discardPhysicsChanges() {
        _location.set(_location.get())
        _velocity.set(_velocity.get())
        locationPhysicsDirty = false
        velocityPhysicsDirty = false
    }

    internal fun updateLocation(location: RailLocationWithFacing) {
        this.location = location
        locationPhysicsDirty = true
    }

    internal data class PlacementState(
        val location: RailLocationWithFacing,
        val sameDirection: Boolean,
        val velocity: Vector3Snapshot
    )

    internal fun capturePlacementState(): PlacementState = PlacementState(
        location, publishedSameDirectionAsFacing, Vector3Snapshot.capture(velocity)
    )

    internal fun restorePlacementState(state: PlacementState) {
        sameDirectionBuffer.set(state.sameDirection)
        sameDirectionBuffer.commit()
        sameDirectionBuffer.set(state.sameDirection)
        updateLocation(state.location)
        updateVelocity(state.velocity.toVector3d())
        publishPhysicsState()
    }

    internal fun captureActivePlacementFrame(): RenderableFrameSnapshot =
        prepareStationaryFrameAt(activeLocation)

    /** Owner-local candidate at a new location, preserving physical model orientation. */
    internal fun prepareStationaryFrameAt(location: RailLocationWithFacing): RenderableFrameSnapshot =
        RenderableFrameSnapshot.create(
            Vector3Snapshot.capture(location.position),
            Vector3Snapshot.capture(if (publishedSameDirectionAsFacing) location.tangent else -location.tangent),
            Vector3Snapshot.capture(location.normal),
            Vector3Snapshot(0.0, 0.0, 0.0)
        )

    /** Updates the active physics velocity; publication remains at the epoch barrier. */
    internal fun updateVelocity(velocity: Vector3dc) {
        this.velocity = Vector3d(velocity)
        velocityPhysicsDirty = true
    }

    @MainThread
    override fun reverse() {
        applyPreparedOperatingReversal(location.reversed)
    }

    /** Apply an already-resolved facing change without solving geometry during structural commit. */
    internal fun applyPreparedOperatingReversal(reversedLocation: RailLocationWithFacing) {
        sameDirectionBuffer.set(!publishedSameDirectionAsFacing)
        sameDirectionBuffer.commit()
        _location.set(reversedLocation)
        _location.commit()
        _location.set(reversedLocation)
    }

    override fun save(): SavedBogieV1 {
        val savedExtension = extensionDataContainer.extensionData.collectSavedExtensionData("bogie")
        return SavedBogieV1(location.save(), publishedSameDirectionAsFacing, savedExtension)
    }
}
