package work.art1st.mineRails.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.destination.DestinationRoleType
import work.art1st.mineRails.basic.api.destination.OperationalDestination
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import java.util.UUID

class RailObjectDestinationEditTest {
    @Test
    fun `editing a station destination preserves fields omitted by the dialog`() {
        val railId = UUID.randomUUID()
        val originalRole = DestinationRole.StationBerthRole(
            stationId = StationId("central"),
            stationDisplayName = "Central Station",
            berthId = "one",
            doorSide = DoorSide.LEFT,
            stoppingOffsetMetersBefore = 2.5,
            positionToleranceMeters = 0.4,
            usableLengthMeters = 95.0,
            defaultDwellTicks = 80L,
            releaseDistanceMetersAfter = 6.0
        )
        val original = OperationalDestination(
            id = "central-one",
            displayName = "Central platform one",
            railId = railId,
            arcLengthInAbMeters = 42.0,
            approachDirection = ViewDirection.AB,
            role = originalRole
        )
        val edit = RailObjectDestinationEdit(
            id = "central-one-renamed",
            roleType = DestinationRoleType.STATION_BERTH,
            approachDirection = ViewDirection.BA,
            stationId = "central",
            berthId = "one",
            doorSide = DoorSide.RIGHT
        )

        val updated = applyRailObjectDestinationEdit(
            WayPoint(original.id, original.displayName, railId, original.arcLengthInAbMeters),
            original,
            edit
        )
        val role = updated.role as DestinationRole.StationBerthRole

        assertEquals("Central platform one", updated.displayName)
        assertEquals("Central Station", role.stationDisplayName)
        assertEquals(2.5, role.stoppingOffsetMetersBefore)
        assertEquals(0.4, role.positionToleranceMeters)
        assertEquals(95.0, role.usableLengthMeters)
        assertEquals(80L, role.defaultDwellTicks)
        assertEquals(6.0, role.releaseDistanceMetersAfter)
        assertEquals(DoorSide.RIGHT, role.doorSide)
        assertEquals(ViewDirection.BA, updated.approachDirection)
    }

    @Test
    fun `editing can remove a destination approach restriction`() {
        val railId = UUID.randomUUID()
        val original = OperationalDestination(
            id = "regular",
            displayName = "Regular",
            railId = railId,
            arcLengthInAbMeters = 10.0,
            approachDirection = ViewDirection.AB
        )

        val updated = applyRailObjectDestinationEdit(
            WayPoint(original.id, original.displayName, railId, original.arcLengthInAbMeters),
            original,
            RailObjectDestinationEdit(
                id = original.id,
                roleType = DestinationRoleType.REGULAR,
                approachDirection = null
            )
        )

        assertNull(updated.approachDirection)
    }

    @Test
    fun `editing a depot destination preserves its display name`() {
        val railId = UUID.randomUUID()
        val original = OperationalDestination(
            id = "north-one",
            displayName = "North depot road one",
            railId = railId,
            arcLengthInAbMeters = 30.0,
            approachDirection = ViewDirection.AB,
            role = DestinationRole.DepotStablingPointRole(
                depotId = "north",
                stablingPointId = "one",
                depotDisplayName = "North Depot",
                usableLengthMeters = 80.0,
                allowedTags = setOf("metro")
            )
        )
        val edit = RailObjectDestinationEdit(
            id = original.id,
            roleType = DestinationRoleType.DEPOT_STABLING_POINT,
            approachDirection = original.approachDirection,
            depotId = "north",
            stablingPointId = "one",
            stablingUsableLengthMeters = 90.0,
            stablingAllowedTags = setOf("metro", "express")
        )

        val updated = applyRailObjectDestinationEdit(
            WayPoint(original.id, original.displayName, railId, original.arcLengthInAbMeters),
            original,
            edit
        )
        val role = updated.role as DestinationRole.DepotStablingPointRole

        assertEquals("North depot road one", updated.displayName)
        assertEquals("North Depot", role.depotDisplayName)
        assertEquals(90.0, role.usableLengthMeters)
        assertEquals(setOf("metro", "express"), role.allowedTags)
    }
}
