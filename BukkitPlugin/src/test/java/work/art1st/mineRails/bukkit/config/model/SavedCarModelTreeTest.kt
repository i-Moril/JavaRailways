package work.art1st.mineRails.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SavedCarModelTreeTest {
    @Test
    fun `builds deterministic snapshot with recursively resolved parts`() {
        val platform = PartDefinition(
            name = "s100/floor_platform",
            type = PartType.PLATFORM,
            platform = PartPlatformConfig(rows = 3, columns = 18)
        )
        val body = PartDefinition(
            name = "s100/body",
            type = PartType.DUMMY,
            parts = listOf(PartRefEntry.import("floor_platform"))
        )
        val registry = SnapshotPartRegistry(
            mapOf(
                body.name to body,
                platform.name to platform
            )
        )
        val car = CarDefinition(
            name = "s100/passenger",
            length = 17.8,
            bodyParts = listOf(PartRefEntry.import("body")),
            control = CarControlConfig()
        )

        val first = SavedCarModelTree.build(car, registry)
        val second = SavedCarModelTree.build(car, registry)

        assertEquals(first.hash, second.hash)
        assertEquals(setOf("s100/body", "s100/floor_platform"), first.parts.keys)
        assertTrue(first.hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `snapshot part registry resolves stored parts with package namespace`() {
        val part = PartDefinition(name = "s100/body", type = PartType.DUMMY)
        val registry = SnapshotPartRegistry(mapOf(part.name to part))

        assertEquals(part, registry.resolve("body", "s100").resolved)
        assertEquals(part, registry.resolve("s100/body", "ignored").resolved)
    }
}
