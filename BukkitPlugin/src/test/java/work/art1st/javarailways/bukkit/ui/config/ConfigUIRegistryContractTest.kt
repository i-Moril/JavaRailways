package work.art1st.javarailways.bukkit.ui.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.wand.config.ConfigGroup
import work.art1st.javarailways.bukkit.api.wand.config.WandConfigItem
import work.art1st.javarailways.bukkit.api.wand.config.WandConfigItemImpl
import work.art1st.javarailways.bukkit.api.wand.config.ConfigKey
import work.art1st.javarailways.bukkit.api.wand.config.ConfigValueType
import java.util.UUID

class ConfigUIRegistryContractTest {
    @Test
    fun `commit reports unregistered dynamic items and retains their dirty state`() {
        val registry = ConfigUIRegistry()
        val session = registry.getPlayerSession(UUID.randomUUID())
        val dynamic = ConfigKey("dynamic.value", "dynamic", defaultValue = "initial", valueType = ConfigValueType.STRING)
        session.setValue(dynamic, "edited")

        val result = session.commit()

        assertFalse(result.succeeded)
        assertEquals(setOf("dynamic.value"), result.failures.map { it.keyId }.toSet())
        assertTrue(session.hasPendingChanges())
        assertEquals("edited", session.getValue(dynamic))
    }

    @Test
    fun `commit clears only successfully written keys`() {
        val registry = ConfigUIRegistry()
        val registered = ConfigKey("registered", "registered", defaultValue = 1, valueType = ConfigValueType.INTEGER)
        val unregistered = ConfigKey("unregistered", "unregistered", defaultValue = 2, valueType = ConfigValueType.INTEGER)
        registry.register(WandConfigItemImpl(registered))
        val session = registry.getPlayerSession(UUID.randomUUID())
        session.setValue(registered, 5)
        session.setValue(unregistered, 7)

        val result = session.commit()

        assertEquals(setOf("registered"), result.committedKeyIds)
        assertEquals(setOf("unregistered"), result.failures.map { it.keyId }.toSet())
        assertEquals(setOf("unregistered"), session.getDirtyKeyIds())
        assertEquals(5, registry.get(registered))
        assertNull(registry.get(unregistered))
    }

    @Test
    fun `trigger keys include dependencies declared by currently hidden dynamic items`() {
        val registry = ConfigUIRegistry()
        val control = ConfigKey("control", "control", defaultValue = true, valueType = ConfigValueType.BOOLEAN)
        val visible = ConfigKey(
            "visible", "visible", defaultValue = "v", valueType = ConfigValueType.STRING,
            dependsOn = listOf(control.id)
        )
        registry.register(ConfigGroup("root", "root", items = mutableListOf(WandConfigItemImpl(visible))))
        val hiddenKey = ConfigKey(
            "hidden.value", "hidden", defaultValue = "h", valueType = ConfigValueType.STRING,
            dependsOn = listOf(control.id)
        )
        registry.register(WandConfigItemImpl(hiddenKey))
        registry.addBuildListener("root") { event ->
            event.addItem(WandConfigItemImpl(hiddenKey))
            event.hideItem(hiddenKey.id)
        }

        val built = registry.buildGroupForPlayer("root", UUID.randomUUID())

        assertEquals(setOf(control.id), built.triggerKeyIds)
    }

    @Test
    fun `config keys reject a default value of another declared type`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfigKey("mismatch", "mismatch", defaultValue = "text", valueType = ConfigValueType.NUMBER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfigKey("mismatch.int", "mismatch", defaultValue = 1, valueType = ConfigValueType.STRING)
        }
    }

    @Test
    fun `reads with a conflicting declared type fail immediately`() {
        val registry = ConfigUIRegistry()
        val stringKey = ConfigKey("same.id", "same", defaultValue = "text", valueType = ConfigValueType.STRING)
        registry.register(WandConfigItemImpl(stringKey))
        val integerKey = ConfigKey("same.id", "same", defaultValue = 1, valueType = ConfigValueType.INTEGER)

        assertThrows(IllegalArgumentException::class.java) { registry.get(integerKey) }
        assertThrows(IllegalArgumentException::class.java) { registry.getOrDefault(integerKey) }
        @Suppress("UNCHECKED_CAST")
        val unchecked = stringKey as ConfigKey<Any?>
        assertThrows(IllegalArgumentException::class.java) {
            registry.setValue(unchecked, 42)
        }
    }

    @Test
    fun `only WandConfigItemImpl values can be registered`() {
        val registry = ConfigUIRegistry()
        val key = ConfigKey("custom", "custom", defaultValue = "v", valueType = ConfigValueType.STRING)
        val custom = object : WandConfigItem {
            override val key: ConfigKey<*> = key
            override val currentValue: Any? = "v"
            override val isDirty: Boolean = false
        }

        assertThrows(IllegalArgumentException::class.java) { registry.register(custom) }
        assertNull(registry.getItem("custom"))
    }

    @Test
    fun `registration rejects known groups that disallow extensions`() {
        val registry = ConfigUIRegistry()
        registry.register(ConfigGroup("closed", "closed", allowExtensions = false))
        val item = WandConfigItemImpl(ConfigKey("extra", "extra", defaultValue = "v", valueType = ConfigValueType.STRING))

        assertThrows(IllegalArgumentException::class.java) { registry.registerToGroup("closed", item) }
        assertNull(registry.getItem("extra"))
    }

    @Test
    fun `pending contributions are settled once and dropped for closed groups`() {
        val registry = ConfigUIRegistry()
        val openItem = WandConfigItemImpl(ConfigKey("open.extra", "extra", defaultValue = "v", valueType = ConfigValueType.STRING))
        val closedItem = WandConfigItemImpl(ConfigKey("closed.extra", "extra", defaultValue = "v", valueType = ConfigValueType.STRING))
        registry.registerToGroup("open", openItem)
        registry.registerToGroup("closed", closedItem)
        assertEquals(setOf("open", "closed"), registry.pendingExtensionGroupIds())
        assertNull(registry.getItem("open.extra"))
        assertNull(registry.getItem("closed.extra"))

        registry.register(ConfigGroup("open", "open", allowExtensions = true))
        registry.register(ConfigGroup("closed", "closed", allowExtensions = false))

        assertTrue(registry.getItem("open.extra") === openItem)
        assertTrue(registry.getGroup("open")!!.items.contains(openItem))
        assertNull(registry.getItem("closed.extra"))
        assertTrue(registry.pendingExtensionGroupIds().isEmpty())
    }
    private enum class First { VALUE }
    private enum class Second { VALUE }
    private enum class WithBody { VALUE { override fun toString() = "value" }, OTHER }

    @Test
    fun `different enum classes conflict on registration global and local access`() {
        val registry = ConfigUIRegistry()
        val first = ConfigKey("enum", "enum", defaultValue = First.VALUE, valueType = ConfigValueType.ENUM)
        val second = ConfigKey("enum", "enum", defaultValue = Second.VALUE, valueType = ConfigValueType.ENUM)
        registry.register(WandConfigItemImpl(first))
        val session = registry.getPlayerSession(UUID.randomUUID())
        session.setValue(first, First.VALUE)
        assertThrows(IllegalArgumentException::class.java) { registry.register(WandConfigItemImpl(second)) }
        assertThrows(IllegalArgumentException::class.java) { registry.get(second) }
        assertThrows(IllegalArgumentException::class.java) { registry.setValue(second, Second.VALUE) }
        assertThrows(IllegalArgumentException::class.java) { session.getValue(second) }
        assertThrows(IllegalArgumentException::class.java) { session.setValue(second, Second.VALUE) }
        assertEquals(First.VALUE, registry.get(first))

        val local = ConfigUIRegistry().getPlayerSession(UUID.randomUUID())
        local.setValue(first, First.VALUE)
        assertThrows(IllegalArgumentException::class.java) { local.getValue(second) }
        assertThrows(IllegalArgumentException::class.java) { local.setValue(second, Second.VALUE) }
    }

    @Test
    fun `enum constant subclasses share their declaring type and number controls share Double`() {
        val registry = ConfigUIRegistry()
        val enum = ConfigKey("enum", "enum", defaultValue = WithBody.VALUE, valueType = ConfigValueType.ENUM)
        registry.register(WandConfigItemImpl(enum))
        registry.setValue(enum, WithBody.OTHER)
        assertEquals(WithBody.OTHER, registry.get(enum))
        val slider = ConfigKey("number", "number", defaultValue = 1.0, valueType = ConfigValueType.NUMBER)
        val text = ConfigKey("number", "number", defaultValue = 2.0, valueType = ConfigValueType.NUMBER_TEXT)
        registry.register(WandConfigItemImpl(slider))
        registry.setValue(text, 3.0)
        assertEquals(3.0, registry.get(slider))
    }

    @Test
    fun `initial values obey the actual enum type and constraints`() {
        val key = ConfigKey("enum", "enum", defaultValue = First.VALUE, valueType = ConfigValueType.ENUM)
        @Suppress("UNCHECKED_CAST")
        val unchecked = key as ConfigKey<Any?>
        assertThrows(IllegalArgumentException::class.java) { WandConfigItemImpl(unchecked, Second.VALUE) }
        assertThrows(IllegalArgumentException::class.java) { WandConfigItemImpl(key, null) }
        val constrained = ConfigKey("range", "range", defaultValue = 1.0, valueType = ConfigValueType.NUMBER,
            constraints = work.art1st.javarailways.bukkit.api.wand.config.ConfigConstraints.NumberRange(0.0, 2.0))
        assertThrows(IllegalArgumentException::class.java) { WandConfigItemImpl(constrained, 3.0) }
    }

    @Test
    fun `callback failure reports saved value clears dirty and is not retried`() {
        val registry = ConfigUIRegistry()
        val key = ConfigKey("value", "value", defaultValue = 1, valueType = ConfigValueType.INTEGER)
        registry.register(WandConfigItemImpl(key))
        var calls = 0
        registry.setValueChangedCallback { _, _, _ -> calls++; error("notification unavailable") }
        val session = registry.getPlayerSession(UUID.randomUUID())
        session.setValue(key, 2)
        val result = session.commit()
        assertFalse(result.succeeded)
        assertTrue(result.failures.isEmpty())
        assertEquals(setOf(key.id), result.committedKeyIds)
        assertEquals(listOf(key.id), result.notificationFailures.map { it.keyId })
        assertEquals(2, registry.get(key))
        assertFalse(session.hasPendingChanges())
        session.setValue(key, 2) // A reopened dialog captures its displayed inputs again.
        assertTrue(session.commit().succeeded)
        assertEquals(1, calls)
    }

    @Test
    fun `checked notification exception reports saved value without retrying`() {
        val registry = ConfigUIRegistry()
        val key = ConfigKey("value", "value", defaultValue = 1, valueType = ConfigValueType.INTEGER)
        registry.register(WandConfigItemImpl(key))
        var calls = 0
        registry.setValueChangedCallback { _, _, _ ->
            calls++
            throw java.io.IOException("notification storage unavailable")
        }
        val session = registry.getPlayerSession(UUID.randomUUID())
        session.setValue(key, 2)

        val result = session.commit()

        assertFalse(result.succeeded)
        assertTrue(result.failures.isEmpty())
        assertEquals(setOf(key.id), result.committedKeyIds)
        assertEquals(listOf(key.id), result.notificationFailures.map { it.keyId })
        assertEquals(2, registry.get(key))
        assertFalse(session.hasPendingChanges())
        session.setValue(key, 2)
        assertTrue(session.commit().succeeded)
        assertEquals(1, calls)
    }

    @Test
    fun `unregistered event items fail during build even when hidden`() {
        val registry = ConfigUIRegistry()
        registry.register(ConfigGroup("root", "root"))
        registry.addBuildListener("root") {
            it.addGroup(ConfigGroup("child", "child", items = mutableListOf(stringItem("unknown"))))
            it.hideGroup("child")
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.buildGroupForPlayer("root", UUID.randomUUID())
        }
        assertNull(registry.getItem("unknown"))
    }

    @Test
    fun `invalid group and sequence registration leave every earlier item unregistered`() {
        val registry = ConfigUIRegistry()
        val valid = stringItem("valid")
        val invalid = object : WandConfigItem {
            override val key = stringItem("invalid").key
            override val currentValue = "value"
            override val isDirty = false
        }
        val group = ConfigGroup("root", "root", items = mutableListOf(valid),
            subGroups = mutableListOf(ConfigGroup("nested", "nested", items = mutableListOf(invalid))))
        assertThrows(IllegalArgumentException::class.java) { registry.register(group) }
        assertNull(registry.getGroup("root"))
        assertNull(registry.getItem("valid"))
        assertThrows(IllegalArgumentException::class.java) { registry.register(sequenceOf(valid, invalid)) }
        assertNull(registry.getItem("valid"))
        registry.register(ConfigGroup("root", "root"))
        assertThrows(IllegalArgumentException::class.java) { registry.registerToGroup("root", group) }
        assertTrue(registry.getGroup("root")!!.subGroups.isEmpty())
        assertNull(registry.getItem("valid"))
        assertThrows(IllegalArgumentException::class.java) { registry.registerToGroup("pending", group) }
        assertTrue(registry.pendingExtensionGroupIds().isEmpty())
    }

    @Test
    fun `conflicting pending settlement leaves original group index and contributions unchanged`() {
        val registry = ConfigUIRegistry()
        registry.registerToGroup("root", stringItem("conflict"))
        val conflict = WandConfigItemImpl(ConfigKey("conflict", "conflict", defaultValue = 1, valueType = ConfigValueType.INTEGER))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(ConfigGroup("root", "root", items = mutableListOf(stringItem("valid"), conflict)))
        }
        assertNull(registry.getGroup("root"))
        assertNull(registry.getItem("valid"))
        assertNull(registry.getItem("conflict"))
        assertEquals(setOf("root"), registry.pendingExtensionGroupIds())
        registry.register(ConfigGroup("root", "root"))
        assertEquals("value", registry.getItem("conflict")!!.currentValue)
    }

    @Test
    fun `constraint rejection retains dirty while independent key commits`() {
        val registry = ConfigUIRegistry()
        val loose = ConfigKey("range", "range", defaultValue = 1.0, valueType = ConfigValueType.NUMBER)
        val strict = ConfigKey("range", "range", defaultValue = 1.0, valueType = ConfigValueType.NUMBER,
            constraints = work.art1st.javarailways.bukkit.api.wand.config.ConfigConstraints.NumberRange(0.0, 2.0))
        val other = ConfigKey("other", "other", defaultValue = 1, valueType = ConfigValueType.INTEGER)
        registry.register(sequenceOf(WandConfigItemImpl(strict), WandConfigItemImpl(other)))
        val session = registry.getPlayerSession(UUID.randomUUID())
        session.setValue(loose, 3.0)
        session.setValue(other, 2)
        val result = session.commit()
        assertEquals(setOf(other.id), result.committedKeyIds)
        assertEquals(listOf(loose.id), result.failures.map { it.keyId })
        assertEquals(setOf(loose.id), session.getDirtyKeyIds())
        assertEquals(1.0, registry.get(strict))
        assertEquals(2, registry.get(other))
    }

    @Test
    fun `same ID conflict within a batch leaves existing group unchanged`() {
        val registry = ConfigUIRegistry()
        val root = ConfigGroup("root", "root", items = mutableListOf(stringItem("original")))
        registry.register(root)
        val conflict = WandConfigItemImpl(ConfigKey("duplicate", "duplicate", defaultValue = 1, valueType = ConfigValueType.INTEGER))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(ConfigGroup("root", "root", items = mutableListOf(stringItem("duplicate"), conflict)))
        }
        assertEquals(listOf("original"), root.items.map { it.key.id })
        assertNull(registry.getItem("duplicate"))
    }

    @Test
    fun `setting the displayed global value captures it locally without making it dirty`() {
        val registry = ConfigUIRegistry()
        val key = ConfigKey("value", "value", defaultValue = 1, valueType = ConfigValueType.INTEGER)
        registry.register(WandConfigItemImpl(key))
        val session = registry.getPlayerSession(UUID.randomUUID())
        session.setValue(key, 1)
        assertFalse(session.hasPendingChanges())
        registry.setValue(key, 2)
        assertEquals(1, session.getValue(key))
        assertEquals(2, registry.get(key))
    }

    private fun stringItem(id: String) = WandConfigItemImpl(
        ConfigKey(id, id, defaultValue = "value", valueType = ConfigValueType.STRING)
    )

}
