package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
    fun `rejects direct and indirect reference cycles with reference path`() {
        for (indirect in listOf(false, true)) {
            val a = PartDefinition(name = "test/a", type = PartType.DUMMY,
                parts = listOf(PartRefEntry.import(if (indirect) "b" else "a")))
            val b = PartDefinition(name = "test/b", type = PartType.DUMMY,
                parts = listOf(PartRefEntry.import("a")))
            val failure = assertThrows<IllegalArgumentException> {
                SavedCarModelTree.build(CarDefinition(name = "test/car", length = 10.0,
                    bodyParts = listOf(PartRefEntry.import("a"))),
                    SnapshotPartRegistry(mapOf(a.name to a, b.name to b)))
            }
            assertTrue(failure.message.orEmpty().contains(
                if (indirect) "test/a -> test/b -> test/a" else "test/a -> test/a"))
        }
    }

    @Test
    fun `allows diamond reuse and repeated roots`() {
        val leaf = PartDefinition(name = "test/leaf", type = PartType.DUMMY)
        val left = PartDefinition(name = "test/left", type = PartType.DUMMY,
            parts = listOf(PartRefEntry.import("leaf")))
        val right = PartDefinition(name = "test/right", type = PartType.DUMMY,
            parts = listOf(PartRefEntry.import("leaf")))
        val registry = SnapshotPartRegistry(listOf(left, right, leaf).associateBy { it.name })
        val car = CarDefinition(name = "test/car", length = 10.0,
            bodyParts = listOf("left", "right", "leaf").map { PartRefEntry.import(it) })
        val snapshot = SavedCarModelTree.build(car, registry)
        assertEquals(setOf(left.name, right.name, leaf.name), snapshot.parts.keys)
        assertEquals(SavedCarModelTree.computeHash(car, snapshot.parts), snapshot.hash)
    }

    @Test
    fun `snapshot part registry resolves stored parts with package namespace`() {
        val part = PartDefinition(name = "s100/body", type = PartType.DUMMY)
        val registry = SnapshotPartRegistry(mapOf(part.name to part))

        assertEquals(part, registry.resolve("body", "s100").resolved)
        assertEquals(part, registry.resolve("s100/body", "ignored").resolved)
    }
}
