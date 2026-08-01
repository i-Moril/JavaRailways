package work.art1st.javarailways.basic.impl.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ui.config.ConfigConstraints
import work.art1st.javarailways.basic.api.ui.config.ConfigGroup
import work.art1st.javarailways.basic.api.ui.config.ConfigItemImpl
import work.art1st.javarailways.basic.api.ui.config.ConfigKey
import work.art1st.javarailways.basic.api.ui.config.ConfigValueType
import java.util.UUID

class ConfigUIRegistryVisibilityTest {
    private enum class Type { REGULAR, STATION }

    @Test
    fun `visibility uses player session values and hidden dependencies remain rebuild triggers`() {
        val registry = ConfigUIRegistry()
        val typeKey = ConfigKey.build<Type>("waypoint.type") {
            label = "type"
            defaultValue(Type.REGULAR)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(Type.entries)
        }
        val stationKey = ConfigKey.build<String>("waypoint.station") {
            label = "station"
            defaultValue("")
            valueType = ConfigValueType.STRING
            dependsOn = listOf(typeKey.id)
            visibleWhen = { it.get<Type>(typeKey.id) == Type.STATION }
        }
        registry.register(
            ConfigGroup(
                id = "waypoint",
                label = "waypoint",
                items = mutableListOf(ConfigItemImpl(typeKey), ConfigItemImpl(stationKey))
            )
        )
        val regularPlayer = UUID.randomUUID()
        val stationPlayer = UUID.randomUUID()
        registry.getPlayerSession(stationPlayer).setValue(typeKey, Type.STATION)

        val regular = registry.buildGroupForPlayer("waypoint", regularPlayer)
        val station = registry.buildGroupForPlayer("waypoint", stationPlayer)

        assertFalse(regular.effectiveItems.any { it.key == stationKey })
        assertTrue(station.effectiveItems.any { it.key == stationKey })
        assertEquals(setOf(typeKey.id), regular.triggerKeyIds)
        assertEquals(setOf(typeKey.id), station.triggerKeyIds)
    }
}
