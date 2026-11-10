package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/** Transient arrival evidence, independent of a mission that may already have been replaced. */
internal class TimetableDisplayPosition {
    private data class Arrival(val call: Triple<String,String,String>, val head: RailPositionSnapshot,
        val tail: RailPositionSnapshot, val reversed: Boolean, val cars: List<String>, val doorSide: DoorSide?)
    private var arrival: Arrival? = null
    val arrivedCall: Triple<String,String,String>? get() = arrival?.call
    val doorSide: DoorSide? get() = arrival?.doorSide
    var inTransit: Boolean = false
        private set

    fun arrive(call: Triple<String,String,String>, frame: TrainFrameSnapshot, doorSide: DoorSide? = null): Boolean {
        if (frame.derailed || frame.speedMetersPerSecond != 0.0) return false
        val next = Arrival(call,frame.head,frame.tail,frame.reversed,frame.cars.map { it.carId },doorSide)
        val changed = next != arrival || inTransit
        arrival = next
        inTransit = false
        return changed
    }

    fun passed(): Boolean {
        val changed = arrival != null || !inTransit
        arrival = null
        inTransit = true
        return changed
    }

    /** Motion is observed from real frames, never from a submitted mission or a planned tick. */
    fun observe(frame: TrainFrameSnapshot): Boolean {
        val previous = arrival
        val before = arrivedCall to inTransit
        if (frame.derailed) { arrival = null; inTransit = false }
        else if (previous != null) {
            val sameFormation = previous.cars == frame.cars.map { it.carId } && previous.reversed == frame.reversed
            val samePlacement = previous.head == frame.head && previous.tail == frame.tail
            if (!sameFormation || !samePlacement || frame.speedMetersPerSecond != 0.0) {
                arrival = null
                inTransit = sameFormation
            }
        } else if (frame.speedMetersPerSecond > 0.0) inTransit = true
        return before != (arrivedCall to inTransit)
    }

    /** Only updates the physically retained current arrival; cannot invent a future platform side. */
    fun updateDoorSide(side: DoorSide?, frame: TrainFrameSnapshot): Boolean {
        val changed = observe(frame)
        val previous = arrival ?: return changed
        if (previous.doorSide == side) return changed
        arrival = previous.copy(doorSide = side)
        return true
    }

    /** A committed reversal swaps operating LEFT/RIGHT while retaining the physical platform side. */
    fun rebaseReversal(frame: TrainFrameSnapshot): Boolean = arrival?.let { previous ->
        val side = when (previous.doorSide) {
            DoorSide.LEFT -> DoorSide.RIGHT
            DoorSide.RIGHT -> DoorSide.LEFT
            else -> previous.doorSide
        }
        arrive(previous.call,frame,side)
    } ?: false
}
