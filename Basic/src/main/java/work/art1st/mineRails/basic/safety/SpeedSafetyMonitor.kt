package work.art1st.mineRails.basic.safety

import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.tick.TickProfiled
import work.art1st.mineRails.core.api.util.tick.TickingObject

/** Optional geometry-derived derail-speed enforcement. */
class SpeedSafetyMonitor(
    private val world: World,
    private val config: Config
) : TickingObject, TickProfiled {
    data class Config(
        val derailEnabled: Boolean,
        val toleranceMetersPerSecond: Double,
        val graceTicks: Int
    ) {
        init {
            require(toleranceMetersPerSecond >= 0.0)
            require(graceTicks > 0)
        }
    }

    private val overspeedTicks = HashMap<String, Int>()

    override val profileCategory: String = "speed-safety"
    override val profileId: String = "world"

    override fun tick(deltaT: Double) {
        if (!config.derailEnabled) return

        val liveTrainIds = HashSet<String>()
        world.allTrains.forEach { train ->
            liveTrainIds += train.id
            if (train.isDerailed) {
                overspeedTicks.remove(train.id)
                return@forEach
            }

            val derailSpeed = train.cars.asSequence()
                .flatMap { it.bogies.asSequence() }
                .mapNotNull { it.location.rail.metadata.derailSpeedMetersPerSecond }
                .minOrNull()
                ?: run {
                    overspeedTicks.remove(train.id)
                    return@forEach
                }

            if (train.speed <= derailSpeed + config.toleranceMetersPerSecond) {
                overspeedTicks.remove(train.id)
                return@forEach
            }

            val ticks = (overspeedTicks[train.id] ?: 0) + 1
            if (ticks >= config.graceTicks) {
                overspeedTicks.remove(train.id)
                train.derail(
                    "Derail speed exceeded: %.2f m/s > %.2f m/s".format(train.speed, derailSpeed)
                )
            } else {
                overspeedTicks[train.id] = ticks
            }
        }
        overspeedTicks.keys.retainAll(liveTrainIds)
    }
}
