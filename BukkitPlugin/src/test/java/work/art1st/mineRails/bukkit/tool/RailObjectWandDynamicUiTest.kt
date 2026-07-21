package work.art1st.mineRails.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.destination.DestinationRoleType
import work.art1st.mineRails.basic.api.ui.config.ConfigGroup
import work.art1st.mineRails.basic.api.ui.config.ConfigItemImpl
import work.art1st.mineRails.basic.impl.config.ConfigUIRegistry
import java.util.UUID

class RailObjectWandDynamicUiTest {
    @Test
    fun `waypoint dialog only exposes fields for the selected type`() {
        val keys = RailObjectWand.WayPointEditKeys()
        val registry = ConfigUIRegistry()
        registry.register(
            ConfigGroup(
                id = RailObjectWand.WayPointEditKeys.GROUP_ID,
                label = "waypoint.dialog.title",
                items = mutableListOf(
                    ConfigItemImpl(keys.name),
                    ConfigItemImpl(keys.role),
                    ConfigItemImpl(keys.approachDirection),
                    ConfigItemImpl(keys.stationId),
                    ConfigItemImpl(keys.berthId),
                    ConfigItemImpl(keys.doorSide),
                    ConfigItemImpl(keys.depotId),
                    ConfigItemImpl(keys.stablingPointId),
                    ConfigItemImpl(keys.stablingUsableLength),
                    ConfigItemImpl(keys.stablingAllowedTags)
                )
            )
        )
        val playerId = UUID.randomUUID()
        val session = registry.getPlayerSession(playerId)

        assertEquals(
            setOf(keys.name.id, keys.role.id, keys.approachDirection.id),
            visibleIds(registry, playerId)
        )
        assertEquals(
            setOf(keys.role.id),
            registry.buildGroupForPlayer(
                RailObjectWand.WayPointEditKeys.GROUP_ID,
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
        registry.buildGroupForPlayer(RailObjectWand.WayPointEditKeys.GROUP_ID, playerId)
            .effectiveItems
            .mapTo(linkedSetOf()) { it.key.id }
}
