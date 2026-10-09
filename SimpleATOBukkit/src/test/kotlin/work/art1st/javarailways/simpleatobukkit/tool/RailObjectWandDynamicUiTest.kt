package work.art1st.javarailways.simpleatobukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.destination.DestinationRoleType
import work.art1st.javarailways.basic.impl.config.ConfigUIRegistry
import java.util.UUID

class RailObjectWandDynamicUiTest {
    @Test
    fun `waypoint dialog only exposes fields for the selected type`() {
        val editor = RailObjectDestinationEditor()
        val keys = editor.keys
        val registry = editor.configUI
        val playerId = UUID.randomUUID()
        val session = registry.getPlayerSession(playerId)
        editor.seedSession(
            playerId,
            work.art1st.javarailways.basic.WayPoint("pending:internal", "", UUID.randomUUID(), 2.0),
            destination = null,
            pending = true
        )
        assertEquals("destination", session.getValue(keys.name))
        assertEquals("existing", initialWayPointDialogName("existing", pending = false))

        assertEquals(
            setOf(keys.name.id, keys.role.id, keys.approachDirection.id),
            visibleIds(registry, playerId)
        )
        assertEquals(
            setOf(keys.role.id),
            registry.buildGroupForPlayer(
                WayPointEditKeys.GROUP_ID,
                playerId
            ).triggerKeyIds
        )

        session.setValue(keys.role, DestinationRoleType.STATION_BERTH)
        assertEquals(
            setOf(
                keys.role.id,
                keys.approachDirection.id,
                keys.stationId.id,
                keys.berthId.id,
                keys.doorSide.id
            ),
            visibleIds(registry, playerId)
        )

        session.setValue(keys.role, DestinationRoleType.DEPOT_STABLING_POINT)
        assertEquals(
            setOf(
                keys.role.id,
                keys.approachDirection.id,
                keys.depotId.id,
                keys.stablingPointId.id,
                keys.stablingUsableLength.id,
                keys.stablingAllowedTags.id
            ),
            visibleIds(registry, playerId)
        )
    }

    private fun visibleIds(registry: ConfigUIRegistry, playerId: UUID): Set<String> =
        registry.buildGroupForPlayer(WayPointEditKeys.GROUP_ID, playerId)
            .effectiveItems
            .mapTo(linkedSetOf()) { it.key.id }
}
