package work.art1st.javarailways.bukkit.passenger

import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PassengerGeometryTest {
    private val origin = PassengerPoint(0.0, 0.0, 0.0)
    private val forward = PassengerPoint(0.0, 0.0, 1.0)

    @Test
    fun `view yaw follows horizontal look and diagonals keep ordinary speed`() {
        assertPoint(PassengerPoint(0.0, 0.0, 1.0), PassengerWalking.viewDirection(0f, 1, 0))
        assertPoint(PassengerPoint(-1.0, 0.0, 0.0), PassengerWalking.viewDirection(90f, 1, 0))
        assertPoint(PassengerPoint(-1.0, 0.0, 0.0), PassengerWalking.viewDirection(0f, 0, 1))
        val a = surface()
        for ((f, s) in listOf(1 to 0, 0 to 1, 1 to 1, -1 to 1)) {
            val result = PassengerWalking.advance(listOf(a), a, origin, PassengerWalking.viewDirection(0f, f, s))
            assertEquals(0.215, result.worldFeet.vector().length(), 1e-9)
        }
    }

    @Test
    fun `contact uses actual horizontal footprint and foot height`() {
        val a = surface()
        assertNotNull(a.contact(PassengerPoint(0.5, 0.0, 0.9)))
        assertNull(a.contact(PassengerPoint(1.01, 0.0, 0.0)))
        assertNull(a.contact(PassengerPoint(0.0, 2.0, 0.0)))
        assertNull(a.contact(PassengerPoint(0.0, -1.0, 0.0)))
    }

    @Test
    fun `touching handoff keeps remaining distance and reverses without centering`() {
        val a = surface()
        val b = surface(Vector3d(0.0, 0.0, 2.0))
        val result = PassengerWalking.advance(listOf(a, b), a, PassengerPoint(0.3, 0.0, 0.9), forward)
        assertSame(b, result.surface)
        assertPoint(PassengerPoint(0.3, 0.0, -0.885), result.local)
        assertPoint(PassengerPoint(0.3, 0.0, 1.115), result.worldFeet)
        val back = PassengerWalking.advance(listOf(a, b), b, result.local, PassengerPoint(0.0, 0.0, -1.0))
        assertSame(a, back.surface)
        assertPoint(PassengerPoint(0.3, 0.0, 0.9), back.local)
    }

    @Test
    fun `overlap retains current owner and an actual gap releases even if endpoint is in another platform`() {
        val a = surface()
        val overlap = surface(Vector3d(0.0, 0.0, 1.0))
        val retained = PassengerWalking.advance(listOf(overlap, a), a, PassengerPoint(0.0, 0.0, 0.5), forward)
        assertSame(a, retained.surface)
        val gapped = surface(Vector3d(0.0, 0.0, 2.00001))
        val released = PassengerWalking.advance(listOf(a, gapped), a, PassengerPoint(0.0, 0.0, 0.9), forward)
        assertNull(released.surface)
        assertPoint(PassengerPoint(0.0, 0.0, 1.115), released.worldFeet)
    }

    @Test
    fun `one step traverses multiple touching short platforms without dropping distance`() {
        val parts = (0..5).map { surface(Vector3d(0.0, 0.0, it * 0.04), halfZ = 0.02) }
        val result = PassengerWalking.advance(parts, parts.first(), origin, forward)
        assertSame(parts.last(), result.surface)
        assertEquals(0.215, result.worldFeet.z, 1e-9)
    }

    @Test
    fun `nonidentical sloped support planes hand off at a shared seam and keep arc distance`() {
        val a = surface()
        val angle = PI / 6
        val rotation = Quaterniond().rotateX(-angle)
        val seam = Vector3d(0.0, 0.0, 1.0)
        val bOrigin = Vector3d(seam).sub(rotation.transform(Vector3d(0.0, 0.0, -1.0)))
        val b = surface(bOrigin, rotation)
        val result = PassengerWalking.advance(listOf(a, b), a, PassengerPoint(0.0, 0.0, 0.9), forward)
        assertSame(b, result.surface)
        assertEquals(-0.885, result.local.z, 1e-9)
        assertEquals(0.115 * sin(angle), result.worldFeet.y, 1e-9)
        assertEquals(1.0 + 0.115 * cos(angle), result.worldFeet.z, 1e-9)
        val back = PassengerWalking.advance(listOf(a, b), b, result.local, PassengerPoint(0.0, 0.0, -1.0))
        assertSame(a, back.surface)
        assertEquals(0.9, back.local.z, 1e-9)
    }

    @Test
    fun `rotated neighboring platform preserves the world crossing point`() {
        val a = surface()
        val rotation = Quaterniond().rotateY(PI / 4)
        val bOrigin = Vector3d(0.0, 0.0, 1.0).sub(rotation.transform(Vector3d(0.0, 0.0, -1.0)))
        val b = surface(bOrigin, rotation)
        val result = PassengerWalking.advance(listOf(a, b), a, PassengerPoint(0.0, 0.0, 0.9), forward)
        assertSame(b, result.surface)
        assertPoint(PassengerPoint(0.0, 0.0, 1.115), result.worldFeet)
    }

    @Test
    fun `copied local transforms survive later live JOML mutation and retirement invalidates source`() {
        val position = Vector3d(1.0, 2.0, 3.0)
        val rotation = Quaterniond().rotateY(0.3)
        val source = PassengerSurfaceSource(1.0, 1.0)
        source.publish(CompactTransform(position, rotation))
        val pose = source.current()!!
        position.set(99.0, 99.0, 99.0)
        rotation.identity()
        assertPoint(PassengerPoint(1.0, 2.0, 3.0), pose.position)
        val world = pose.toWorld(PassengerPoint(0.0, 0.0, 1.0))
        assertEquals(1.0 + sin(0.3), world.x, 1e-9)
        assertPoint(PassengerPoint(0.0, 0.0, 1.0), pose.toLocal(world))
        source.retire()
        source.publish(CompactTransform())
        assertNull(source.current())
    }

    private fun surface(position: Vector3d = Vector3d(), rotation: Quaterniond = Quaterniond(), halfZ: Double = 1.0) =
        PassengerSurface(PassengerSurfaceSource(1.0, halfZ), PassengerPose.capture(CompactTransform(position, rotation)), 1.0, halfZ)

    private fun assertPoint(expected: PassengerPoint, actual: PassengerPoint) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.z, actual.z, 1e-9)
    }
}
