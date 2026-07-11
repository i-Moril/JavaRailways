package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.Reversible
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.Destroyable
import java.lang.ref.WeakReference
import work.art1st.mineRails.core.util.CommitBuffer

/**
 * 车辆端部
 * 自动添加到Rail
 */
open class TrainEnd(
    location: RailLocationWithFacing,
    train: CoreTrain,
    val type: Type
) : RailObject, Reversible, Destroyable, Synchronizable {
    init {
        location.rail.addTrainEnd(this)
    }

    private val trainRef = WeakReference(train)

    val trainOrNull: CoreTrain?
        get() = trainRef.get()

    val train: CoreTrain
        get() = trainOrNull ?: throw IllegalStateException("TrainEnd no longer references a live train")

    private val locationBuffer = CommitBuffer(location)
    private var physicsDirty: Boolean = false

    override var location: RailLocationWithFacing
        get() = locationBuffer.get()
        set(value) {
            locationBuffer.set(value)
            physicsDirty = true
        }

    internal val activeLocation: RailLocationWithFacing
        get() = if (physicsDirty) locationBuffer.getActive() else locationBuffer.get()

    internal fun publishPhysicsState() {
        if (!physicsDirty) return
        val oldLocation = locationBuffer.get()
        val newLocation = locationBuffer.getActive()
        if (newLocation.rail != oldLocation.rail) {
            oldLocation.rail.moveTrainEndTo(this, newLocation.rail)
        }
        locationBuffer.commit()
        physicsDirty = false
    }

    override fun commit() = Unit

    @MainThread
    override fun destroy() {
        location.rail.removeTrainEnd(this)
    }

    @MainThread
    override fun reverse() {
        val reversedLocation = location.reversed
        locationBuffer.set(reversedLocation)
        locationBuffer.commit()
        locationBuffer.set(reversedLocation)
        physicsDirty = false
    }

    enum class Type {
        HEAD,
        TAIL
    }
}