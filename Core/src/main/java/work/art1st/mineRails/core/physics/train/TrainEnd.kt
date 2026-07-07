package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.Reversible
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.Destroyable
import java.lang.ref.WeakReference

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

    @MainThread
    override fun destroy() {
        location.rail.removeTrainEnd(this)
    }

    @MainThread
    override fun reverse() {
        location = location.reversed
    }

    enum class Type {
        HEAD,
        TAIL
    }
}