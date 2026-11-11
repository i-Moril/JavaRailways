package work.art1st.javarailways.bukkit.extension.rail

import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.config.OffsetConfig
import work.art1st.javarailways.bukkit.config.loadRailModelConfig
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import java.nio.file.Files
import java.nio.file.Path

class RailPackageCoordinateTest {
    @Test
    fun `shipped rail packages preserve visual geometry and light probes across the frame change`() {
        // Fixed pre-change values, rather than deriving expectations from the converted files.
        val oldPackages = mapOf(
            "standard" to listOf(
                OldElement(Vector3d(-0.5, 0.0, 0.7), Vector3d(0.0, 0.5, 0.0), Vector3d(0.0, 90.0, 0.0), Vector3d(1.4, 1.0, 1.0)),
                OldElement(Vector3d(-0.505, -1.005, 0.0), Vector3d(0.505, 1.505, 0.0), Vector3d(), Vector3d(1.01))
            ),
            "standard-ballastless" to listOf(
                OldElement(Vector3d(-0.5, 0.0, 0.7), Vector3d(0.0, 0.5, 0.0), Vector3d(0.0, 90.0, 0.0), Vector3d(1.4, 1.0, 1.0)),
                OldElement(Vector3d(-0.505, -1.005, 0.0), Vector3d(0.505, 1.505, 0.0), Vector3d(), Vector3d(1.01))
            ),
            "standard-railonly" to listOf(
                OldElement(Vector3d(), Vector3d(), Vector3d(90.0, 0.0, 90.0), Vector3d(1.4, 1.0, 1.0))
            )
        )
        val forward = Vector3d(0.48, 0.6, 0.64)
        val up = Vector3d(-0.36, 0.8, -0.48).rotateAxis(Math.PI / 3.0, forward.x, forward.y, forward.z)
        val oldSide = Vector3d(forward).cross(up)
        val oldBasis = Matrix3d().setColumn(0, forward).setColumn(1, up).setColumn(2, oldSide)
        val oldRotation = Quaterniond().setFromNormalized(oldBasis)
        val newRotation = computeRailCurveRotation(forward, up)
        val origin = Vector3d(10.0, 20.0, 30.0)
        for ((name, oldElements) in oldPackages) {
            val config = loadRailModelConfig(Files.newInputStream(Path.of("../models/rails/$name/rail_model.yml")))
            assertEquals(oldElements.size, config.elements.size)
            config.elements.zip(oldElements).forEach { (element, old) ->
                val actual = computeRailDisplayPlacement(origin, forward, up, element.offset, element.lightProbeOffset)
                val oldVisual = oldBasis.transform(Vector3d(old.offset)).add(origin)
                val oldProbe = oldBasis.transform(Vector3d(old.probe)).add(oldVisual)
                assertVector(oldVisual, actual.visualPosition)
                assertVector(oldProbe, actual.lightProbePosition)
                val actualRotation = Quaterniond(newRotation).rotateXYZ(
                    Math.toRadians(element.rotation.x), Math.toRadians(element.rotation.y), Math.toRadians(element.rotation.z))
                val expectedRotation = Quaterniond(oldRotation).rotateXYZ(
                    Math.toRadians(old.rotation.x), Math.toRadians(old.rotation.y), Math.toRadians(old.rotation.z))
                // Nonuniform mesh scale must keep its own axes, independent of the rail frame.
                val scale = Vector3d(element.scale.x, element.scale.y, element.scale.z)
                for (point in listOf(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0))) {
                    val expected = expectedRotation.transform(Vector3d(point).mul(old.scale)).add(oldVisual)
                    val transformed = actualRotation.transform(Vector3d(point).mul(scale)).add(actual.visualPosition)
                    assertVector(expected, transformed)
                }
            }
        }
    }

    @Test
    fun `bundled default and root sample remain valid current rail configurations`() {
        val bundled = loadRailModelConfig(requireNotNull(javaClass.getResourceAsStream("/rail_model.yml")))
        assertEquals(-90.0, bundled.elements.single().rotation.y)
        val sample = loadRailModelConfig(Files.newInputStream(Path.of("../rail_model.yml")))
        assertEquals(3, sample.elements.size)
        assertEquals(OffsetConfig(-0.3, -0.1, 0.0), sample.elements[1].offset)
        assertEquals(OffsetConfig(0.3, -0.1, 0.0), sample.elements[2].offset)
    }

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() = ModelRuntimeTestSupport.bootstrap()
    }

    private data class OldElement(val offset: Vector3d, val probe: Vector3d, val rotation: Vector3d, val scale: Vector3d)

    private fun assertVector(expected: org.joml.Vector3dc, actual: org.joml.Vector3dc) {
        assertEquals(expected.x(), actual.x(), 1e-9)
        assertEquals(expected.y(), actual.y(), 1e-9)
        assertEquals(expected.z(), actual.z(), 1e-9)
    }
}
