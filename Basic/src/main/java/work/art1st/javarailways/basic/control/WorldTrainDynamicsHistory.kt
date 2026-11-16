package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.VehicleDeviceState
import work.art1st.javarailways.basic.api.ato.VehicleControlCommand
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.Car
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** World-owned, runtime-only device history. Construction of an unused controller never writes it. */
class WorldTrainDynamicsHistory {
    data class Snapshot(val state: TrainDynamics.State, val lastCommand: VehicleControlCommand,
        val cars: List<WeakReference<work.art1st.javarailways.core.api.physics.train.Car>> = emptyList())
    private class TrainReference(train: Train, queue: ReferenceQueue<Train>) : WeakReference<Train>(train, queue) {
        val id = train.id
    }
    private class CarReference(car: Car, queue: ReferenceQueue<Car>? = null) : WeakReference<Car>(car, queue) {
        private val identityHash = System.identityHashCode(car)
        override fun hashCode() = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            other is CarReference && get()?.let { it === other.get() } == true
    }
    private val carQueue = ReferenceQueue<Car>()
    private val carDevices = ConcurrentHashMap<CarReference, VehicleDeviceState>()

    /** Exact physical car state survives a controllerless split without retaining the car. */
    fun deviceForCar(car: Car): VehicleDeviceState? {
        discardCollectedCars()
        return carDevices[CarReference(car)]
    }

    private data class Entry(val train: TrainReference, val snapshot: Snapshot)
    private val queue = ReferenceQueue<Train>()
    private val entries = ConcurrentHashMap<String, Entry>()

    fun latest(train: Train): Snapshot? {
        discardCollectedTrains()
        return entries[train.id]?.takeIf { it.train.get() === train }?.snapshot
    }

    fun read(train: Train, speedMetersPerSecond: Double): Snapshot? =
        latest(train)?.takeIf { abs(it.state.speed - speedMetersPerSecond) <= 1e-9 }

    internal fun record(train: Train, snapshot: Snapshot) {
        discardCollectedTrains()
        discardCollectedCars()
        snapshot.cars.forEachIndexed { index, ref ->
            ref.get()?.let { car -> carDevices[CarReference(car, carQueue)] = snapshot.state.devices[index] }
        }
        entries.compute(train.id) { _, previous ->
            Entry(previous?.train?.takeIf { it.get() === train } ?: TrainReference(train, queue), snapshot)
        }
    }

    /** Explicit world lifecycle cleanup; exact identities prevent deleting a replacement train's history. */
    fun remove(train: Train) {
        entries.computeIfPresent(train.id) { _, entry -> if (entry.train.get() === train) null else entry }
    }

    fun clear() {
        entries.clear()
        carDevices.clear()
    }

    private fun discardCollectedCars() {
        while (true) {
            val collected = carQueue.poll() as? CarReference ?: return
            carDevices.remove(collected)
        }
    }

    private fun discardCollectedTrains() {
        while (true) {
            val collected = queue.poll() as? TrainReference ?: return
            entries.computeIfPresent(collected.id) { _, entry -> if (entry.train === collected) null else entry }
        }
    }
}
