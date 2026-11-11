package work.art1st.javarailways.bukkit.render.model

import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ExtensionAudiencePointTest {
    @Test fun `screen reference includes nested rotation and animated offset with inclusive radius and zero disable`() {
        val world = UUID.randomUUID()
        val parent = CompactTransform(Vector3d(10.0, 0.0, 0.0), Quaterniond().rotationY(Math.PI / 2))
        val state = ModelState(CompactTransform(Vector3d(2.0, 0.0, 0.0)))
        state.applyRotation(parent, CompactTransform(Vector3d(100.0, 0.0, 0.0)),
            CompactTransform(position = Vector3d(1.0, 0.0, 0.0)))
        val p = state.position
        val screen = ExtensionAudiencePoint(world, p.x(), p.y(), p.z())
        assertEquals(110.0, screen.x, 1e-10)
        assertEquals(-3.0, screen.z, 1e-10)
        assertTrue(screen.copy(x = screen.x + 4).isWithin(screen, 4.0))
        assertFalse(screen.copy(x = screen.x + 4.01).isWithin(screen, 4.0))
        assertFalse(screen.isWithin(screen, 0.0))
        assertFalse(screen.copy(worldId = UUID.randomUUID()).isWithin(screen, null))
        assertTrue(screen.copy(x = 10_000.0).isWithin(screen, null))
    }
}
