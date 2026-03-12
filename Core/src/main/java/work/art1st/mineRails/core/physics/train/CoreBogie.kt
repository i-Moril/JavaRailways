package work.art1st.mineRails.core.physics.train

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.unaryMinus
import org.koin.core.scope.Scope
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
import work.art1st.mineRails.core.api.util.persistence.Loads
import work.art1st.mineRails.core.api.util.persistence.PersistentBy

typealias BogieConfig = CoreBogie.ConfigV1
typealias SavedBogie = CoreBogie.SavedBogieV1

/**
 * 转向架类
 * 转向架位于铁轨上，即转向架坐标位于曲线上，方向为曲线的切线方向
 */
class CoreBogie @MainThread internal constructor(
    location: RailLocationWithFacing,
    /* isSameDirectionAsFacing: direction是否与location的facing一致. */
    private var isSameDirectionAsFacing: Boolean = true,
    savedExtensionData: Map<String, SerializableExtensionData>,
    val extensionDataContainer: ExtensionDataContainer<RenderableExtension<Bogie>> = ExtensionDataContainer()
) : Bogie, DynamicRenderableTrait<Bogie>, Reversible, Extendable<RenderableExtension<Bogie>> by extensionDataContainer, PersistentBy<SavedBogie> {
    private val _location = CommitBuffer(location)
    override var location: RailLocationWithFacing by _location
        private set
    internal val activeLocation
        get() = _location.getActive()

    override val position
        get() = location.position
    /* direction: Asset显示的方向 */
    override val direction
        get() = if (isSameDirectionAsFacing) location.tangent else -location.tangent
    override val normal
        get() = location.normal
    private val _velocity: CommitBuffer<Vector3dc> = CommitBuffer(Vector3d(0.0, 0.0, 0.0))
    /* velocity: 速度的大小和方向 */
    override var velocity: Vector3dc by _velocity
    internal val activeVelocity
        get() = _velocity.getActive()

    override val world
        get() = location.rail.world

    override var lastState: RenderableStateSnapshot = takeSnapshot()
    override var dirty = false

    init {
        this.initialize(savedExtensionData)
    }

    @Throws(BogieException::class)
    override fun moveForward(distance: Double) {
        updateLocation(location.forward(distance) ?: throw BogieException(this))
    }

    override fun commit() {
        if (dirty) {
            _location.commit()
            _velocity.commit()
            super.commit()
        }
    }

    fun updateLocation(location: RailLocationWithFacing) {
        this.location = location
        dirty = true
    }

    @MainThread
    override fun reverse() {
        isSameDirectionAsFacing = !isSameDirectionAsFacing
        location = location.reversed
    }

    override fun save(): SavedBogie {
        val savedExtension = HashMap<String, SerializableExtensionData>()
        extensionDataContainer.extensionData.forEach { ext ->
            ext?.save()?.let { savedExtension[it.first] = it.second }
        }
        return SavedBogie(location, isSameDirectionAsFacing, savedExtension)
    }

    @Serializable
    data class SavedBogieV1(
        @Contextual val location: RailLocationWithFacing,
        val isSameDirectionAsFacing: Boolean,
        val extension: Map<String, SerializableExtensionData>
    ) : Loads<CoreBogie, Unit> {
        override fun load(dependency: Unit): CoreBogie {
            return CoreBogie(location, isSameDirectionAsFacing, extension)
        }
    }

    @Serializable
    data class ConfigV1(
        val extension: Map<String, SerializableExtensionData>
    ) {
    }
}

