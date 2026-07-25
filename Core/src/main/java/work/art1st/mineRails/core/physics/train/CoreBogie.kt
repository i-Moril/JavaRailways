package work.art1st.mineRails.core.physics.train

import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.unaryMinus
import work.art1st.mineRails.core.api.physics.exception.BogieException
import work.art1st.mineRails.core.api.physics.rail.Reversible
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.api.physics.train.Bogie
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.render.RenderableStateSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.util.trait.render.DynamicRenderableTrait
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.plugin.ExtensionDataContainer
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.util.CommitBuffer
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
) : Bogie, DynamicRenderableTrait<Bogie>, Reversible, Extendable<RenderableExtension<Bogie>> by extensionDataContainer, PersistentBy<SavedBogieV1> {

    companion object {
        @JvmStatic
        fun create(
            location: RailLocationWithFacing,
            extensionData: Map<String, SerializableExtensionData> = emptyMap()
        ): CoreBogie = CoreBogie(location, true, extensionData)
    }
    private val sameDirectionBuffer = CommitBuffer(isSameDirectionAsFacing)
    private var directionPhysicsDirty = false
    private val publishedSameDirectionAsFacing: Boolean get() = sameDirectionBuffer.get()
    private val activeSameDirectionAsFacing: Boolean
        get() = if (directionPhysicsDirty) sameDirectionBuffer.getActive() else sameDirectionBuffer.get()

    private val _location = CommitBuffer(location)
    override var location: RailLocationWithFacing by _location
        private set
    internal val activeLocation
        get() = if (physicsDirty) _location.getActive() else _location.get()

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
        get() = if (physicsDirty) _velocity.getActive() else _velocity.get()

    override val world
        get() = location.rail.world

    override var lastState: RenderableStateSnapshot = takeSnapshot()
    private var physicsDirty = false

    init {
        this.initialize(savedExtensionData)
    }

    @Throws(BogieException::class)
    override fun moveForward(distance: Double) {
        updateLocation(activeLocation.forward(distance) ?: throw BogieException(this))
    }

    /** Publishes Core state off the Bukkit main thread after all physics workers complete. */
    internal fun publishPhysicsState() {
        if (physicsDirty) {
            _location.commit()
            _velocity.commit()
            physicsDirty = false
        }
        if (directionPhysicsDirty) {
            sameDirectionBuffer.commit()
            directionPhysicsDirty = false
        }
    }

    internal fun updateLocation(location: RailLocationWithFacing) {
        this.location = location
        physicsDirty = true
    }

    @MainThread
    override fun reverse() {
        sameDirectionBuffer.set(!publishedSameDirectionAsFacing)
        sameDirectionBuffer.commit()
        val reversedLocation = location.reversed
        _location.set(reversedLocation)
        _location.commit()
        _location.set(reversedLocation)
    }

    internal fun reverseFromPhysics() {
        sameDirectionBuffer.set(!activeSameDirectionAsFacing)
        directionPhysicsDirty = true
        updateLocation(activeLocation.reversed)
    }

    override fun save(): SavedBogieV1 {
        val savedExtension = HashMap<String, SerializableExtensionData>()
        extensionDataContainer.extensionData.forEach { ext ->
            ext?.save()?.let { savedExtension[it.first] = it.second }
        }
        return SavedBogieV1(location.save(), publishedSameDirectionAsFacing, savedExtension)
    }
}
