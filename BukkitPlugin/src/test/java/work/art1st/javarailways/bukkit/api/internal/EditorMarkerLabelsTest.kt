package work.art1st.javarailways.bukkit.api.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kyori.adventure.text.format.NamedTextColor
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerGlyph
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerLabels
import work.art1st.javarailways.bukkit.api.editor.EditorTextAppearance
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class EditorMarkerLabelsTest {
    @Test
    fun `target client bitmap transform centres glyph and preserves declared physical dimensions`() {
        // Official 26.1.2 client SHA1 4e618f09a0c649dde3fdf829df443ce0b8831e65:
        // TextDisplayRenderer.submitInner rotates Y by pi, scales -.025, then translates
        // (1 - advance/2, -9); GlyphBitmap with ascent 7 occupies [0,8] on both axes.
        val point = Vector3d(10.0, 20.0, 30.0)
        val normal = Vector3d(-0.2, 1.0, 0.3).normalize()
        for (sign in listOf(1.0, -1.0)) {
            val along = Vector3d(1.0, 0.5, 0.2).normalize().mul(sign)
            val up = Vector3d(normal).sub(Vector3d(along).mul(normal.dot(along))).normalize()
            val across = Vector3d(along).cross(up).normalize()
            val label = EditorMarkerLabels.at("centred", EditorMarkerGlyph.BOUNDARY,
                point, along, normal, 1.6, 0.25, NamedTextColor.WHITE)
            val appearance = label.appearance()
            val pose = appearance.transform()
            val rendered = Matrix4d().translation(label.x(), label.y(), label.z())
                .translate(pose.x(), pose.y(), pose.z())
                .rotate(Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW()))
                .scale(appearance.scaleX(), appearance.scaleY(), 1.0)
                .rotateY(Math.PI).scale(-0.025).translate(1.0 - 9.0 / 2, -9.0, 0.0)
            val centre = rendered.transformPosition(Vector3d(4.0, 4.0, 0.0))
            assertVector(Vector3d(point).add(Vector3d(up).mul(EditorMarkerLabels.NORMAL_LIFT_METERS)), centre)
            assertVector(Vector3d(centre).add(Vector3d(across).mul(0.8)),
                rendered.transformPosition(Vector3d(8.0, 4.0, 0.0)))
            assertVector(Vector3d(centre).add(Vector3d(along).mul(0.125)),
                rendered.transformPosition(Vector3d(4.0, 0.0, 0.0)))
        }
    }

    @Test
    fun `rail plane basis preserves direction reversal slope bank and immutable input`() {
        val position = Vector3d(1.0, 2.0, 3.0)
        val directions = listOf(Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 0.0, -1.0), Vector3d(1.0, 0.5, 0.0).normalize())
        for (direction in directions) {
            val normal = Vector3d(0.2, 1.0, 0.4).normalize()
            val beforeNormal = Vector3d(normal)
            val beforeDirection = Vector3d(direction)
            val label = EditorMarkerLabels.at("arrow", EditorMarkerGlyph.DIRECTION, position, direction, normal, 1.5, 3.0, NamedTextColor.AQUA)
            val pose = label.appearance().transform()
            val rotation = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW())
            val expectedNormal = Vector3d(normal).sub(Vector3d(direction).mul(normal.dot(direction))).normalize()
            assertVector(direction, rotation.transform(Vector3d(0.0, 1.0, 0.0)))
            assertVector(expectedNormal, rotation.transform(Vector3d(0.0, 0.0, 1.0)))
            assertVector(Vector3d(position).add(Vector3d(expectedNormal).mul(EditorMarkerLabels.NORMAL_LIFT_METERS)), Vector3d(label.x(), label.y(), label.z()))
            assertEquals(EditorTextAppearance.Mode.FIXED_GLYPH, label.appearance().mode())
            assertEquals(EditorMarkerLabels.FONT, label.text().font())
            assertEquals(7.5, label.appearance().scaleX())
            assertEquals(15.0, label.appearance().scaleY())
            assertEquals(beforeDirection, direction)
            assertEquals(beforeNormal, normal)
            assertEquals(Vector3d(1.0, 2.0, 3.0), position)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditorMarkerLabels.at("bad", EditorMarkerGlyph.DIRECTION, position, Vector3d(), Vector3d(0.0, 1.0, 0.0), 1.0, 1.0, NamedTextColor.WHITE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditorMarkerLabels.at("bad", EditorMarkerGlyph.DIRECTION, position, Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 1.0, 0.0), 1.0, 1.0, NamedTextColor.WHITE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditorMarkerLabels.at("bad", EditorMarkerGlyph.DIRECTION, position, Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0), 0.0, 1.0, NamedTextColor.WHITE)
        }
    }

    @Test
    fun `shipped font maps API glyphs to tintable masks with solid coverage and equal metrics`() {
        val pack = Path.of("examples/editor-markers/resourcepack")
        val metadata = Json.parseToJsonElement(Files.readString(pack.resolve("pack.mcmeta"))).jsonObject.getValue("pack").jsonObject
        assertEquals("84", metadata.getValue("min_format").jsonArray[0].jsonPrimitive.content)
        assertEquals(metadata["min_format"], metadata["max_format"])
        val font = Json.parseToJsonElement(Files.readString(pack.resolve("assets/javarailways/font/editor_markers.json"))).jsonObject
        val provider = font.getValue("providers").jsonArray.single().jsonObject
        assertEquals("bitmap", provider.getValue("type").jsonPrimitive.content)
        assertEquals("8", provider.getValue("height").jsonPrimitive.content)
        assertEquals("7", provider.getValue("ascent").jsonPrimitive.content)
        assertEquals(EditorMarkerGlyph.values().joinToString("") { it.text() }, provider.getValue("chars").jsonArray.single().jsonPrimitive.content)
        val texture = provider.getValue("file").jsonPrimitive.content.split(':')
        val image = ImageIO.read(pack.resolve("assets/${texture[0]}/textures/${texture[1]}").toFile())
        assertEquals(48, image.width)
        assertEquals(16, image.height)
        for (glyph in 0..2) {
            var transparent = false
            var opaque = false
            var rightmost = -1
            for (y in 0..15) for (x in 0..15) {
                val pixel = image.getRGB(glyph * 16 + x, y)
                val alpha = pixel ushr 24
                if (alpha == 0) transparent = true else {
                    assertEquals(255, alpha)
                    assertEquals(0xffffff, pixel and 0xffffff, "Component controls the marker tint")
                    opaque = true
                    rightmost = maxOf(rightmost, x)
                }
            }
            assertTrue(opaque)
            assertEquals(glyph != EditorMarkerGlyph.COVERAGE.ordinal, transparent,
                "coverage is a solid bar; the arrow and boundary retain their alpha masks")
            assertEquals(15, rightmost, "all glyphs must preserve the helper's nine-pixel advance")
        }
    }

    private fun assertVector(expected: Vector3d, actual: Vector3d) {
        assertTrue(expected.distance(actual) < 1e-9, "$expected != $actual")
    }
}
