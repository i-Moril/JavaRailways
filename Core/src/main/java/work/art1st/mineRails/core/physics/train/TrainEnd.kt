package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.api.physics.ReversibleRailObject
import work.art1st.mineRails.core.api.util.SynchronizedObject
import work.art1st.mineRails.core.util.Destroyable
import java.lang.ref.WeakReference

/**
 * 车辆端部
 */
open class TrainEnd(
    location: RailLocationWithFacing,
    train: Train,
    val type: Type
) : ReversibleRailObject, Destroyable, SynchronizedObject {
    init {
        location.rail.addTrainEnd(this)
    }

    private val _train: WeakReference<Train> = WeakReference(train)
    val train: Train
        get() = _train.get()!!

    private var commitTask: Runnable? = null
    override var location: RailLocationWithFacing = location
        set(value) {
            if (value.rail != field.rail) {
                commitTask = Runnable {
                    field.rail.moveTrainEndTo(this, value.rail)
                }
            }
            field = value
        }

    override fun commit() {
        commitTask?.run()
        commitTask = null
    }

    override fun destroy() {
        location.rail.removeTrainEnd(this)
    }

    enum class Type {
        HEAD,
        TAIL
    }
}