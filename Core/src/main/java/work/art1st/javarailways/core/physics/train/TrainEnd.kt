package work.art1st.javarailways.core.physics.train

import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.Reversible
import work.art1st.javarailways.core.api.util.annotation.MainThread
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.util.CommitBuffer

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

    override val ref: RailObjectRef = RailObjectRef(
        namespace = "core",
        id = "train-end/${train.id}/${type.name.lowercase()}"
    )

    @Volatile
    final override var lifecycleState: RailObjectLifecycleState = RailObjectLifecycleState.ACTIVE
        private set

    private val ownerTrain = train

    val trainOrNull: CoreTrain?
        get() = ownerTrain.takeUnless { lifecycleState == RailObjectLifecycleState.REMOVED }

    val train: CoreTrain
        get() = trainOrNull ?: throw IllegalStateException("TrainEnd no longer references a live train")

    private val locationBuffer = CommitBuffer(location)
    private var physicsDirty: Boolean = false

    override var location: RailLocationWithFacing
        get() = locationBuffer.get()
        internal set(value) {
            locationBuffer.set(value)
            physicsDirty = true
        }

    internal val activeLocation: RailLocationWithFacing
        get() = if (physicsDirty) locationBuffer.getActive() else locationBuffer.get()

    /** Failed movement never entered the rail index; discard its staged endpoint only. */
    internal fun discardPhysicsChanges() {
        locationBuffer.set(locationBuffer.get())
        physicsDirty = false
    }

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

    private val destruction = Destruction()

    @MainThread
    override fun destroy(): CompletionStage<Void> = destruction.run {
        location.rail.removeTrainEnd(this)
        lifecycleState = RailObjectLifecycleState.REMOVED
        CompletableFuture.completedFuture(null)
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