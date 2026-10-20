package work.art1st.javarailways.bukkit.operation

import work.art1st.javarailways.core.api.physics.train.Car
import java.util.IdentityHashMap

/** Opt-in test history; the production simulation retains no effect records. */
internal class SimulationBehaviorEffectCollector {
    private val effects = IdentityHashMap<Car, MutableList<SimulationBehaviorEffect>>()

    @Synchronized
    fun record(car: Car, effect: SimulationBehaviorEffect) {
        effects.getOrPut(car) { mutableListOf() }.add(effect)
    }

    @Synchronized
    fun snapshot(car: Car): List<SimulationBehaviorEffect> = effects[car]?.toList().orEmpty()
}
