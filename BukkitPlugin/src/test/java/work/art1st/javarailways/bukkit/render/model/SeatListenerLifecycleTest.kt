package work.art1st.javarailways.bukkit.render.model

import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.ThreadGuardPlatformBridge
import java.lang.reflect.Proxy

class SeatListenerLifecycleTest {
    private val listener = SeatModel.SeatInteractionListener
    private val player = unusedProxy<Player>()
    private lateinit var train: Train

    @BeforeEach
    fun createTrain() {
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
        )
        train = createTrainWithConfig("seat-listener-test", rail, world, listOf(10.0))
    }

    @AfterEach
    fun cleanup() {
        listener.close()
        ThreadGuard.clearPlatformBridge()
    }

    @Test
    fun `stopping one control owner removes only its callback and supports repeated close`() {
        var firstCalls = 0
        var secondCalls = 0
        val first = listener.addDriverSeatVacatedHandler { _, _ -> firstCalls++ }
        listener.addDriverSeatVacatedHandler { _, _ -> secondCalls++ }
        listener.notifyDriverSeatVacated(player, train)
        first.close()
        first.close()
        listener.notifyDriverSeatVacated(player, train)
        assertEquals(1, firstCalls)
        assertEquals(2, secondCalls)
    }

    @Test
    fun `a new runtime never calls callbacks retained from the previous runtime`() {
        var oldCalls = 0
        var newCalls = 0
        val old = listener.addDriverSeatVacatedHandler { _, _ -> oldCalls++ }
        listener.close()
        listener.close()
        listener.addDriverSeatVacatedHandler { _, _ -> newCalls++ }
        old.close()
        listener.notifyDriverSeatVacated(player, train)
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }

    private inline fun <reified T> unusedProxy(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, method, _ -> error("Lifecycle callback must not access ${method.name}") } as T
}
