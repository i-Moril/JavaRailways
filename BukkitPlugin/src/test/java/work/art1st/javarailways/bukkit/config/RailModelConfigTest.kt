package work.art1st.javarailways.bukkit.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class RailModelConfigTest {
    @Test
    fun `defaults missing vector components in rail model config`() {
        val yaml = """
            repeat_length: 0.95
            elements:
              - type: item
                material: RAIL
                offset:
                  z: 1.5
                rotation:
                  y: 90.0
                scale:
                  z: 2.0
        """.trimIndent()

        val config = loadRailModelConfig(ByteArrayInputStream(yaml.toByteArray()))
        val element = config.elements.single()

        assertEquals(0.0, element.offset.x)
        assertEquals(0.0, element.offset.y)
        assertEquals(1.5, element.offset.z)
        assertEquals(0.0, element.rotation.x)
        assertEquals(90.0, element.rotation.y)
        assertEquals(0.0, element.rotation.z)
        assertEquals(1.0, element.scale.x)
        assertEquals(1.0, element.scale.y)
        assertEquals(2.0, element.scale.z)
    }

    @Test
    fun `parses optional display brightness override`() {
        val yaml = """
            repeat_length: 0.95
            elements:
              - type: item
                material: RAIL
                brightness:
                  block: 12
                  sky: 7
                offset: {}
                rotation: {}
                scale: {}
        """.trimIndent()

        val brightness = loadRailModelConfig(ByteArrayInputStream(yaml.toByteArray()))
            .elements.single().brightness ?: error("brightness should be parsed")

        assertEquals(12, brightness.block)
        assertEquals(7, brightness.sky)
    }

    @Test
    fun `rejects display brightness outside vanilla range`() {
        val yaml = """
            repeat_length: 0.95
            elements:
              - type: item
                material: RAIL
                brightness:
                  block: 16
                offset: {}
                rotation: {}
                scale: {}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            loadRailModelConfig(ByteArrayInputStream(yaml.toByteArray()))
        }
    }

    @Test
    fun `missing scale block still fails explicitly`() {
        val yaml = """
            repeat_length: 0.95
            elements:
              - type: item
                material: RAIL
                offset: {}
                rotation: {}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            loadRailModelConfig(ByteArrayInputStream(yaml.toByteArray()))
        }
    }
}
