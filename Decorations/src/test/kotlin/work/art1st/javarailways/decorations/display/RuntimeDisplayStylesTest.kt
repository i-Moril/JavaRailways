package work.art1st.javarailways.decorations.display

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RuntimeDisplayStylesTest {
    private val world = UUID.randomUUID()

    @Test fun `override is atomic across exact catalogs and remains queryable across rebuild`() {
        val styles = RuntimeDisplayStyles()
        val first = styles.register(world, "cabin", setOf("main", "common", "only-first"), "first@captured", "main")
        val second = styles.register(world, "cabin", setOf("main", "common", "only-second"), "second@captured", "main")
        styles.bind(first, "unit-a")
        styles.bind(second, "unit-a")
        assertEquals(setOf("main", "common"), styles.inspect(world, "unit-a", "cabin").availableTemplates)
        val initial = first.revision
        assertThrows<IllegalArgumentException> { styles.set(world, "unit-a", "cabin", "only-first") }
        assertNull(styles.overrideFor(first))
        assertEquals(initial, first.revision)
        val selection = styles.set(world, "unit-a", "cabin", "common")
        assertEquals(2, selection.screenCount)
        assertEquals("common", styles.overrideFor(second))
        val changed = first.revision
        styles.set(world, "unit-a", "cabin", "common")
        assertEquals(changed, first.revision)
        styles.unbind(first)
        styles.unbind(second)
        assertEquals("common", styles.inspect(world, "unit-a", "cabin").templateId)
        assertEquals(0, styles.inspect(world, "unit-a", "cabin").screenCount)
        assertThrows<IllegalArgumentException> { styles.set(world, "unit-a", "cabin", "main") }
        val rebuilt = styles.register(world, "cabin", setOf("main", "common"), "first@captured", "main")
        styles.bind(rebuilt, "unit-a")
        assertEquals("common", styles.overrideFor(rebuilt))
        val beforeReset = rebuilt.revision
        styles.reset(world, "unit-a", "cabin")
        assertNull(styles.overrideFor(rebuilt))
        assertTrue(rebuilt.revision > beforeReset)
        assertEquals("common", selection.templateId) // Prior result is an owned snapshot.
        assertEquals(setOf("main", "common"), selection.availableTemplates)
    }

    @Test fun `world unit and category isolation and effective unchanged defaults`() {
        val styles = RuntimeDisplayStyles()
        val otherWorld = UUID.randomUUID()
        val own = styles.register(world, "cabin", setOf("main", "alternate"), "a", "main")
        val otherUnit = styles.register(world, "cabin", setOf("main", "alternate"), "a", "main")
        val otherCategory = styles.register(world, "outside", setOf("main", "alternate"), "a", "main")
        val foreign = styles.register(otherWorld, "cabin", setOf("main", "alternate"), "a", "main")
        styles.bind(own, "unit-a")
        styles.bind(otherUnit, "unit-b")
        styles.bind(otherCategory, "unit-a")
        styles.bind(foreign, "unit-a")
        val originalRevision = own.revision
        styles.set(world, "unit-a", "cabin", "main")
        assertEquals(originalRevision, own.revision)
        styles.reset(world, "unit-a", "cabin")
        assertEquals(originalRevision, own.revision)
        styles.set(world, "unit-a", "cabin", "alternate")
        assertEquals("alternate", styles.overrideFor(own))
        listOf(otherUnit, otherCategory, foreign).forEach { assertNull(styles.overrideFor(it)) }
        styles.bind(own, null)
        assertNull(styles.overrideFor(own))
        styles.bind(own, "unit-b")
        assertNull(styles.overrideFor(own))
        styles.bind(own, "unit-a")
        assertEquals("alternate", styles.overrideFor(own))
        assertEquals(setOf("unit-a", "unit-b"), styles.unitIds(world))
        assertEquals(setOf("cabin", "outside"), styles.categories(world, "unit-a"))
    }

    @Test fun `accepted world unload invalidates late binding but new epoch can bind`() {
        val styles = RuntimeDisplayStyles()
        val old = styles.register(world, "cabin", setOf("main"), "old", "main")
        styles.bind(old, "unit-a")
        styles.set(world, "unit-a", "cabin", "main")
        val beforeUnload = old.revision
        styles.clearWorld(world)
        assertTrue(old.revision > beforeUnload)
        assertFalse(styles.isActive(old))
        styles.bind(old, "unit-a")
        assertTrue(styles.unitIds(world).isEmpty())
        assertNull(styles.inspect(world, "unit-a", "cabin").templateId)
        val replacement = styles.register(world, "cabin", setOf("main"), "new", "main")
        styles.bind(replacement, "unit-a")
        assertEquals(1, styles.set(world, "unit-a", "cabin", "main").screenCount)
        styles.close()
        styles.bind(replacement, "late")
        assertTrue(styles.unitIds(world).isEmpty())
        assertThrows<IllegalStateException> { styles.register(world, "cabin", setOf("main"), "late") }
        assertThrows<IllegalStateException> { styles.set(world, "unit-a", "cabin", "main") }
    }

    @Test fun `retained override can reset without live screens and callback runs outside lock`() {
        lateinit var styles: RuntimeDisplayStyles
        var callbacks = 0
        styles = RuntimeDisplayStyles {
            CompletableFuture.supplyAsync { styles.inspect(world, "unit-a", "cabin") }.get(2, TimeUnit.SECONDS)
            callbacks++
        }
        val binding = styles.register(world, "cabin", setOf("main", "alternate"), "captured", "main")
        styles.bind(binding, "unit-a")
        styles.set(world, "unit-a", "cabin", "alternate")
        styles.unbind(binding)
        assertEquals("alternate", styles.inspect(world, "unit-a", "cabin").templateId)
        assertNull(styles.reset(world, "unit-a", "cabin").templateId)
        assertEquals(3, callbacks)
        assertThrows<IllegalArgumentException> { styles.set(world, "phantom", "cabin", "main") }
    }
}
