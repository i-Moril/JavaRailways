package work.art1st.javarailways.bukkit.debug

import work.art1st.javarailways.bukkit.extension.bukkitWorldDataOrNull
import work.art1st.javarailways.core.api.event.DerailEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi

/** Adds one-shot event rows to the train debug CSV for failures that happen between ticks. */
@OptIn(AsyncEventApi::class)
class TrainDebugEventListener : EventListener {
    @HandlesEvent
    fun onDerail(event: DerailEvent) {
        if (!TrainDebugLog.enabled) return
        val train = event.train
        val coreTrain = train as? CoreTrain
        val bukkitWorld = train.world.bukkitWorldDataOrNull?.worldId
        train.cars.forEachIndexed { index, car ->
            TrainDebugLog.logRow(listOf(
                System.currentTimeMillis(), "derail", bukkitWorld, train.id, index, car.hashCode(),
                train.speed, train.isDerailed, coreTrain?.controller?.javaClass?.simpleName ?: "",
                coreTrain?.head?.location?.rail?.id ?: train.headLocation.rail.id,
                coreTrain?.head?.location?.arcLength,
                train.headLocation.position.x(), train.headLocation.position.y(), train.headLocation.position.z(),
                car.position.x(), car.position.y(), car.position.z(),
                car.direction.x(), car.direction.y(), car.direction.z(),
                car.normal.x(), car.normal.y(), car.normal.z(),
                car.bogies.getOrNull(0)?.position?.x(), car.bogies.getOrNull(0)?.position?.y(), car.bogies.getOrNull(0)?.position?.z(),
                car.bogies.getOrNull(1)?.position?.x(), car.bogies.getOrNull(1)?.position?.y(), car.bogies.getOrNull(1)?.position?.z(),
                car.bogies.getOrNull(0)?.direction?.x(), car.bogies.getOrNull(0)?.direction?.y(), car.bogies.getOrNull(0)?.direction?.z(),
                car.bogies.getOrNull(1)?.direction?.x(), car.bogies.getOrNull(1)?.direction?.y(), car.bogies.getOrNull(1)?.direction?.z(),
                null, null, null, null, null, null, null,
                event.msg
            ))
        }
    }
}
