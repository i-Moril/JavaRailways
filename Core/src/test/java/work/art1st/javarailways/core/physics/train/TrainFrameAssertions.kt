package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import work.art1st.javarailways.core.api.physics.train.CarFrameSnapshot
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot

/** Numeric equality deliberately treats signed zero as the same physical state. */
internal fun assertCarFramesEqual(expected: List<CarFrameSnapshot>, actual: List<CarFrameSnapshot>) {
    assertEquals(expected.size, actual.size)
    expected.zip(actual).forEach { (before, after) ->
        assertEquals(before.carId, after.carId)
        assertEquals(before.lengthMeters, after.lengthMeters)
        assertRenderStateEqual(before.state, after.state)
        assertEquals(before.bogies.size, after.bogies.size)
        before.bogies.zip(after.bogies).forEach { (beforeBogie, afterBogie) ->
            assertRenderStateEqual(beforeBogie, afterBogie)
        }
    }
}

private fun assertRenderStateEqual(expected: RenderableFrameSnapshot, actual: RenderableFrameSnapshot) {
    assertEquals(0.0, expected.positionVector().distance(actual.positionVector()), 0.0)
    assertEquals(0.0, expected.directionVector().distance(actual.directionVector()), 0.0)
    assertEquals(0.0, expected.normalVector().distance(actual.normalVector()), 0.0)
    assertEquals(0.0, expected.velocityVector().distance(actual.velocityVector()), 0.0)
}
