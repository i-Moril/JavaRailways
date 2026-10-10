package work.art1st.javarailways.bukkit.extension.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.train.CarFrameSnapshot
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot

class CarFrameVelocityConversionTest {
    @Test
    fun `body and bogie preserve frame direction while converting metres per second to blocks per tick`() {
        val state = frame(20.0, -10.0, 5.0)
        val frame = CarFrameSnapshot.create(
            carId = "conversion",
            lengthMeters = 10.0,
            state = state,
            bogies = listOf(state)
        )

        val motion = frame.bukkitMotion()

        assertEquals(1.0, motion.bodyVelocityBlocksPerTick.x(), 0.0)
        assertEquals(-0.5, motion.bodyVelocityBlocksPerTick.y(), 0.0)
        assertEquals(0.25, motion.bodyVelocityBlocksPerTick.z(), 0.0)
        assertEquals(motion.bodyVelocityBlocksPerTick, motion.bogieVelocitiesBlocksPerTick.single())
    }

    private fun frame(velocityX: Double, velocityY: Double, velocityZ: Double) =
        RenderableFrameSnapshot(
            positionX = 0.0,
            positionY = 0.0,
            positionZ = 0.0,
            directionX = 1.0,
            directionY = 0.0,
            directionZ = 0.0,
            normalX = 0.0,
            normalY = 1.0,
            normalZ = 0.0,
            velocityX = velocityX,
            velocityY = velocityY,
            velocityZ = velocityZ
        )
}
