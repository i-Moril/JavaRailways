package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RailObjectMarkerRenameTest {
    @Test
    fun `rename updates all viewers in its world and preserves same id in another world`() {
        val world = UUID.randomUUID()
        val otherWorld = UUID.randomUUID()
        val first = RailObjectWand.MarkerHandle(world, "station", "Station")
        val second = RailObjectWand.MarkerHandle(world, "station", "Station")
        val other = RailObjectWand.MarkerHandle(otherWorld, "station", "Other station")
        val maps = listOf(first, second, other).map { ConcurrentHashMap(mapOf(it.id to it)) }
        val updated = mutableListOf<RailObjectWand.MarkerHandle>()

        // Handles have no entity yet: world isolation must also cover pending entity creation.
        RailObjectWand.renameMarkers(maps, world, "station", "renamed", "Renamed", updated::add)

        assertEquals(listOf(first, second), updated)
        maps.take(2).forEachIndexed { index, markers ->
            assertNull(markers["station"])
            assertSame(listOf(first, second)[index], markers["renamed"])
            assertEquals("renamed", markers["renamed"]!!.id)
            assertEquals("Renamed", markers["renamed"]!!.displayName)
        }
        assertSame(other, maps[2]["station"])
        assertNull(maps[2]["renamed"])
        assertEquals("station", other.id)
        assertEquals("Other station", other.displayName)
    }
}
