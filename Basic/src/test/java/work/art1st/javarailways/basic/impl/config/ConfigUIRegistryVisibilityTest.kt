package work.art1st.javarailways.basic.impl.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ui.config.ConfigConstraints
import work.art1st.javarailways.basic.api.ui.config.ConfigGroup
import work.art1st.javarailways.basic.api.ui.config.ConfigItemImpl
import work.art1st.javarailways.basic.api.ui.config.ConfigItem
import work.art1st.javarailways.basic.api.ui.config.ConfigKey
import work.art1st.javarailways.basic.api.ui.config.ConfigValueType
import java.util.UUID

class ConfigUIRegistryVisibilityTest {
    private enum class Type { REGULAR, STATION }

    @Test
    fun `recursive event and predicate filtering defines one complete effective item list`() {
        val registry = ConfigUIRegistry()
        fun item(id: String, visible: Boolean = true, order: Int = 0): ConfigItem = ConfigItemImpl(ConfigKey(
            id, id, defaultValue = "value", valueType = ConfigValueType.STRING,
            visibleWhen = { visible }, order = order))
        val root = item("root", order = 20)
        val hiddenChild = ConfigGroup("hide", "hide", items = mutableListOf(item("hide.direct")),
            subGroups = mutableListOf(ConfigGroup("hide.deep", "deep", items = mutableListOf(item("hide.descendant")))))
        val visibleChild = ConfigGroup("visible", "visible", items = mutableListOf(item("visible.keep", order = 10),
            item("visible.predicate", false), item("visible.event")))
        val hiddenPredicate = ConfigGroup("predicate", "predicate", items = mutableListOf(item("predicate.item")),
            visibleWhen = { false })
        registry.register(ConfigGroup("root", "root", items = mutableListOf(root),
            subGroups = mutableListOf(hiddenChild, visibleChild, hiddenPredicate)))
        val extraKey = ConfigKey("extra.value", "extra", defaultValue = "default", valueType = ConfigValueType.STRING, order = 5)
        val player = UUID.randomUUID()
        registry.getPlayerSession(player).setValue(extraKey, "player")
        registry.addBuildListener("root") { event ->
            event.hideGroup("hide")
            event.hideItem("visible.event")
            event.addItem(root) // The same identity is not a second editable field.
            event.addItem(item("extra.hidden", false))
            event.addGroup(ConfigGroup("extra", "extra", items = mutableListOf(ConfigItemImpl(extraKey)),
                subGroups = mutableListOf(ConfigGroup("extra.deep", "deep", items = mutableListOf(item("extra.deep.keep", order = 10))))))
            event.addGroup(ConfigGroup("extra.hiddenGroup", "hidden", items = mutableListOf(item("extra.group.item"))))
            event.hideGroup("extra.hiddenGroup")
        }

        val built = registry.buildGroupForPlayer("root", player)

        assertEquals(listOf("extra.value", "visible.keep", "extra.deep.keep", "root"), built.effectiveItems.map { it.key.id })
        assertEquals(listOf("visible", "extra"), built.effectiveSubGroups.map { it.id })
        assertTrue(built.hiddenItemIds.containsAll(setOf("hide.direct", "hide.descendant", "visible.predicate",
            "visible.event", "predicate.item", "extra.hidden", "extra.group.item")))
        assertEquals("player", built.snapshot.get<String>(extraKey.id))
    }

    @Test
    fun `built snapshot freezes construction values session overrides defaults and key membership`() {
        val registry = ConfigUIRegistry()
        val global = ConfigKey("global", "global", defaultValue = 1, valueType = ConfigValueType.INTEGER)
        val local = ConfigKey("local", "local", defaultValue = 10, valueType = ConfigValueType.INTEGER)
        val default = ConfigKey("default", "default", defaultValue = 20, valueType = ConfigValueType.INTEGER)
        registry.register(ConfigGroup("root", "root", items = mutableListOf(ConfigItemImpl(global),
            ConfigItemImpl(local), ConfigItemImpl(default))))
        registry.setValue(global, 2)
        val player = UUID.randomUUID()
        val session = registry.getPlayerSession(player)
        session.setValue(local, 11)
        val built = registry.buildGroupForPlayer("root", player)
        assertEquals(2, built.snapshot.get<Int>(global.id))
        assertEquals(11, built.snapshot.get<Int>(local.id))
        registry.setValue(global, 3)
        registry.setValue(default, 21)
        session.setValue(local, 12)
        session.commit()
        session.reset()
        registry.removePlayerSession(player)
        registry.register(ConfigItemImpl(ConfigKey("late", "late", defaultValue = 40, valueType = ConfigValueType.INTEGER)))

        assertEquals(2, built.snapshot.get<Int>(global.id))
        assertEquals(11, built.snapshot.get<Int>(local.id))
        assertEquals(20, built.snapshot.get<Int>(default.id))
        assertTrue(built.snapshot.contains(global.id))
        assertTrue(built.snapshot.contains(local.id))
        assertTrue(built.snapshot.contains(default.id))
        assertFalse(built.snapshot.contains("late"))
        assertNull(built.snapshot.get<Int>("late"))
        assertEquals(99, built.snapshot.getOrDefault("late", 99))
        assertEquals(3, registry.buildGroupForPlayer("root", player).snapshot.get<Int>(global.id))
        assertEquals(12, registry.buildGroupForPlayer("root", player).snapshot.get<Int>(local.id))
    }

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
