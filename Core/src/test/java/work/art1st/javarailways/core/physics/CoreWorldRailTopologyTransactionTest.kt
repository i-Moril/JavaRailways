package work.art1st.javarailways.core.physics

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CoreWorldRailTopologyTransactionTest {
    @Test
    fun `world readers cannot observe an infrastructure transaction mid-commit`() {
        val world = createTestWorld()
        CoreRail(world, straightCurve(0.0, 10.0), mutableListOf(), mutableListOf())
        val changed = CountDownLatch(1)
        val release = CountDownLatch(1)

        val writer = CompletableFuture.runAsync {
            world.railTopologyTransaction {
                CoreRail(world, straightCurve(10.0, 20.0), mutableListOf(), mutableListOf())
                changed.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        check(changed.await(5, TimeUnit.SECONDS))
        val reader = CompletableFuture.supplyAsync { world.allRails.size }

        Thread.sleep(50)
        assertFalse(reader.isDone, "reader observed a partially committed rail topology")
        release.countDown()

        assertEquals(2, reader.get(5, TimeUnit.SECONDS))
        writer.get(5, TimeUnit.SECONDS)
    }

    private fun straightCurve(fromX: Double, toX: Double): PrimeCurve {
        val normal = Vector3d(0.0, 1.0, 0.0)
        val tangent = Vector3d(1.0, 0.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(fromX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(toX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), toX - fromX)
        ))
    }
}
