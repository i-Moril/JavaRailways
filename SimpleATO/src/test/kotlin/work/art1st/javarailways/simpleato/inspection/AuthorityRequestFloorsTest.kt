package work.art1st.javarailways.simpleato.inspection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding

class AuthorityRequestFloorsTest {
    private val route = FrozenRouteBinding("test:route", 1L, 2L, 300.0)

    @Test
    fun `request before or exactly at entry remains unchanged`() {
        val floors = compiled(floor(100.0, 150.0, "test:block"))
        val before = RouteBoundary(90.0)
        val at = RouteBoundary(100.0)

        assertSame(before, floors.adjust(route, before))
        assertSame(at, floors.adjust(route, at))
    }

    @Test
    fun `request inside a window is raised to its exit`() {
        val floors = compiled(floor(100.0, 150.0, "test:block"))

        assertEquals(RouteBoundary(150.0), floors.adjust(route, RouteBoundary(120.0)))
    }

    @Test
    fun `request at or after exit remains unchanged`() {
        val floors = compiled(floor(100.0, 150.0, "test:block"))
        val at = RouteBoundary(150.0)
        val after = RouteBoundary(170.0)

        assertSame(at, floors.adjust(route, at))
        assertSame(after, floors.adjust(route, after))
    }

    @Test
    fun `overlapping windows chain forward but adjacent entry does not`() {
        val floors = compiled(
            floor(100.0, 150.0, "test:first"),
            floor(140.0, 190.0, "test:overlap"),
            floor(190.0, 230.0, "test:adjacent")
        )

        assertEquals(RouteBoundary(190.0), floors.adjust(route, RouteBoundary(120.0)))
        assertEquals(RouteBoundary(230.0), floors.adjust(route, RouteBoundary(191.0)))
    }

    @Test
    fun `stale route and out of range floor are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompiledAuthorityRequestFloors.compile(
                route,
                listOf(floor(250.0, 310.0, "test:outside"))
            )
        }
        val floors = compiled(floor(100.0, 150.0, "test:block"))
        assertThrows(IllegalArgumentException::class.java) {
            floors.adjust(route.copy(routeRevision = 2L), RouteBoundary(120.0))
        }
    }

    private fun compiled(vararg floors: AuthorityRequestFloor) =
        CompiledAuthorityRequestFloors.compile(route, floors.asList())

    @Test
    fun `retry ceiling retreats before whole overlapping windows but not adjacent ones`() {
        val floors = compiled(floor(10.0, 30.0, "test:first"),
            floor(20.0, 50.0, "test:overlap"), floor(50.0, 80.0, "test:adjacent"))
        assertEquals(RouteBoundary(10.0), floors.limitTo(route, RouteBoundary(40.0)))
        assertEquals(RouteBoundary(50.0), floors.limitTo(route, RouteBoundary(50.0)))
        assertEquals(RouteBoundary(50.0), floors.limitTo(route, RouteBoundary(60.0)))
        assertEquals(RouteBoundary(90.0), floors.limitTo(route, RouteBoundary(90.0)))
    }

    private fun floor(trigger: Double, end: Double, id: String) =
        AuthorityRequestFloor(trigger, end, id)
}
