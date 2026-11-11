package work.art1st.javarailways.bukkit.render.model

import java.lang.reflect.Proxy
import net.kyori.adventure.text.Component
import org.bukkit.util.Transformation
import org.joml.Matrix4f
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionText
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.core.physics.train.createTestWorld

class TextModelTransformTest {
    @Test fun `stretched glyph stays on tilted installed screen through car and train rotations`() {
        val trainRotation = Quaterniond().rotateY(-.8).rotateX(.1)
        val carRotation = Quaterniond().rotateY(.3).rotateZ(.08)
        val installation = Quaterniond().rotateY(Math.PI / 2).rotateX(Math.toRadians(22.5))
        val rotation = Quaterniond(trainRotation).mul(carRotation).mul(installation)
        val parent = CompactTransform(Vector3d(1.2, 2.3, -4.5), rotation)
        val localPosition = Vector3d(.2, .1, .003)
        val scale = Vector3d(4.0, .75, 1.0)
        withTransformation(parent, CompactTransform(localPosition, scale = scale)) { actual ->
            val matrix = matrix(actual)
            val expectedOrigin = rotation.transform(Vector3d(localPosition)).add(parent.position)
            val corners = listOf(
                Vector3d(-.0875, .025, 0.0), Vector3d(.1125, .025, 0.0),
                Vector3d(.1125, .225, 0.0), Vector3d(-.0875, .225, 0.0),
            )
            val rendered = corners.map { corner -> matrix.transformPosition(Vector3f(corner)) }
            corners.zip(rendered).forEach { (corner, point) ->
                val expected = rotation.transform(Vector3d(corner).mul(scale)).add(expectedOrigin)
                assertVector(expected, point)
            }
            val normal = Vector3f(rendered[1]).sub(rendered[0])
                .cross(Vector3f(rendered[3]).sub(rendered[0])).normalize()
            val expectedNormal = rotation.transform(Vector3d(0.0, 0.0, 1.0))
            assertEquals(1.0, Vector3d(normal).dot(expectedNormal), 1e-5)
            assertEquals(.8, rendered[1].distance(rendered[0]).toDouble(), 1e-5)
            assertEquals(.15, rendered[3].distance(rendered[0]).toDouble(), 1e-5)
        }
    }

    @Test fun `uniform text scale preserves previous rotated geometry`() {
        val parent = CompactTransform(Vector3d(2.0, 3.0, 4.0), Quaterniond().rotateY(1.1).rotateX(.4))
        val local = CompactTransform(Vector3d(.2, .3, .4), scale = Vector3d(2.0))
        withTransformation(parent, local) { actual ->
            val relative = local.applyTo(parent)
            val previous = Matrix4f().translate(relative.position.get(Vector3f()))
                .scale(relative.scale.get(Vector3f())).rotate(relative.rotation.get(org.joml.Quaternionf()))
            val current = matrix(actual)
            for (point in listOf(Vector3f(), Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f), Vector3f(0f, 0f, 1f))) {
                val expected = previous.transformPosition(Vector3f(point))
                assertVector(Vector3d(expected), current.transformPosition(Vector3f(point)))
            }
        }
    }

    private fun matrix(value: Transformation) = Matrix4f().translate(value.translation)
        .rotate(value.leftRotation).scale(value.scale).rotate(value.rightRotation)

    private fun assertVector(expected: Vector3d, actual: Vector3f) {
        assertEquals(expected.x(), actual.x().toDouble(), 1e-5)
        assertEquals(expected.y(), actual.y().toDouble(), 1e-5)
        assertEquals(expected.z(), actual.z().toDouble(), 1e-5)
    }

    private fun withTransformation(parent: CompactTransform, local: CompactTransform, check: (Transformation) -> Unit) {
        val world = createTestWorld()
        var captured: Transformation? = null
        val visual = Proxy.newProxyInstance(DisplayVisual::class.java.classLoader, arrayOf(DisplayVisual::class.java)) { _, method, args ->
            when (method.name) {
                "setTransformation" -> { captured = args!![0] as Transformation; null }
                else -> error("Unexpected visual call ${method.name}")
            }
        } as DisplayVisual
        try {
            val text = PartExtensionText(Component.text("\u2588"), ModelPose.IDENTITY,
                ModelScale(local.scale.x(), local.scale.y(), local.scale.z()), key = "rectangle")
            val model = TextModel(world, ModelState(local), visual, text)
            model.updateState(parent, CompactTransform(position = Vector3d(100.0, 64.0, 200.0)), Vector3d(), true)
            check(requireNotNull(captured))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
