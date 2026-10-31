package work.art1st.javarailways.bukkit.behavior

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.bukkit.config.model.*
import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult

class CarBehaviorTest {
    @Test
    fun `same source has lazy independent persistent globals and pure sound completes`() {
        val source = TrainBehaviorScript("behavior.js", "let n=0; function bell(){sound('bell'+(++n));}")
        val first = Fixture(source)
        val second = Fixture(source)
        try {
            assertTrue(first.sounds.isEmpty())
            first.call("bell")
            first.call("bell")
            second.call("bell")
            assertEquals(listOf("bell1", "bell2"), first.sounds)
            assertEquals(listOf("bell1"), second.sounds)
            assertEquals(2, first.completions.size)
            assertTrue(first.completions.all { it.isSuccess })
        } finally { first.close(); second.close() }
    }

    @Test
    fun `source replacement resets only JS globals and preserves already accepted physical effects`() {
        val fixture = Fixture(TrainBehaviorScript("first.js", """
            let n=0;
            function count(){sound('old'+(++n));}
            function open(){return action('delayed');}
        """.trimIndent()), mapOf("delayed" to TrainActionConfig("delayed", sounds = listOf(TrainActionSoundConfig("old-effect", at = 1.0)))))
        try {
            fixture.call("count")
            fixture.call("count")
            fixture.call("open")
            assertEquals(2, fixture.completions.size)
            fixture.currentScript = TrainBehaviorScript("second.js", "let n=0; function count(){sound('new'+(++n));}")
            fixture.call("count")
            assertEquals(listOf("old1", "old2", "new1"), fixture.sounds)
            fixture.delayed.single().run()
            assertEquals(listOf("old1", "old2", "new1", "old-effect"), fixture.sounds)
            assertEquals(4, fixture.completions.size)
            assertTrue(fixture.completions.all { it.isSuccess })
        } finally { fixture.close() }
    }

    @Test
    fun `actions registered inside behavior calls become visible and executable`() {
        val fixture = Fixture(TrainBehaviorScript("dynamic.js", """
            let n=0;
            function registerBell(){defineAction('bell', function(){sound('bell'+(++n));});}
        """.trimIndent()))
        try {
            assertTrue(fixture.behavior.metadata(false).isEmpty())
            fixture.call("registerBell")
            assertEquals(listOf("bell"), fixture.behavior.metadata(false).map { it.name })
            assertTrue(fixture.behavior.action("bell", false, fixture.completions::add) is TrainModelOperationResult.Accepted)
            assertEquals(listOf("bell1"), fixture.sounds)
            assertTrue(fixture.completions.all { it.isSuccess })
        } finally { fixture.close() }
    }

    @Test
    fun `door freezes relative end once and completes only every delayed effect`() {
        val source = TrainBehaviorScript("behavior.js", """
            function door(side, scene) {
              const physical = operatingEnd() === 'tail' ? (side === 'left' ? 'right' : 'left') : side;
              return ['door-'+physical+'-open'];
            }
        """.trimIndent())
        val actions = mapOf("door-right-open" to TrainActionConfig("door-right-open", animations = listOf(
            TrainActionAnimationConfig("door-right", "open", 1.0), TrainActionAnimationConfig("light", "on", 1.0)
        ), sounds = listOf(TrainActionSoundConfig("bell", at = 2.0))))
        val fixture = Fixture(source, actions)
        try {
            assertTrue(fixture.behavior.door(DoorSide.LEFT, true, true, fixture.completions::add) is TrainModelOperationResult.Accepted)
            fixture.behavior.metadata(false) // another command/control-end must not reinterpret accepted effects
            fixture.delayed[1].run()
            assertTrue(fixture.completions.isEmpty())
            fixture.delayed[0].run()
            assertTrue(fixture.completions.isEmpty())
            fixture.delayed[2].run()
            assertEquals(listOf("light:on", "door-right:open"), fixture.animations)
            assertEquals(listOf("bell"), fixture.sounds)
            assertEquals(1, fixture.completions.size)
            assertTrue(fixture.completions.single().isSuccess)
        } finally { fixture.close() }
    }

    @Test
    fun `missing effects and delayed failure report terminal failure once`() {
        val fixture = Fixture(null, mapOf("broken" to TrainActionConfig("broken", animations = listOf(TrainActionAnimationConfig("missing", at = 1.0)))))
        try {
            assertTrue(fixture.behavior.action("absent", false, fixture.completions::add) is TrainModelOperationResult.Rejected)
            assertTrue(fixture.completions.single().isFailure)
            fixture.completions.clear()
            fixture.behavior.action("broken", false, fixture.completions::add)
            fixture.delayed.single().run()
            fixture.delayed.single().run()
            assertEquals(1, fixture.completions.size)
            assertTrue(fixture.completions.single().isFailure)
        } finally { fixture.close() }
    }

    @Test
    fun `destroy ends accepted operations and cancels delayed effects`() {
        val fixture = Fixture(null, mapOf("open" to TrainActionConfig("open", sounds = listOf(TrainActionSoundConfig("bell", at = 1.0)))))
        fixture.behavior.action("open", false, fixture.completions::add)
        fixture.close()
        fixture.close()
        fixture.delayed.single().run()
        assertEquals(1, fixture.completions.size)
        assertTrue(fixture.completions.single().isFailure)
        assertTrue(fixture.sounds.isEmpty())
        assertTrue(fixture.behavior.action("open", false, fixture.completions::add) is TrainModelOperationResult.Rejected)
    }

    @Test
    fun `declaration scan errors are visible and scriptless car has no JS declarations`() {
        val fixture = Fixture(TrainBehaviorScript("bad.js", "function __javaRailwaysListActions(){throw new Error('bad metadata')};"))
        try { assertThrows(Exception::class.java) { fixture.behavior.metadata(false) } } finally { fixture.close() }
        Fixture(null).use { assertTrue(it.behavior.metadata(false).isEmpty()) }
    }

    @Test
    fun `metadata initialization cannot run top level action or sound before permission checks`() {
        listOf("sound('bell');", "action('bell');", "function __javaRailwaysListActions(){sound('bell');return [];}").forEach { source ->
            val fixture = Fixture(TrainBehaviorScript("metadata.js", source), mapOf("bell" to TrainActionConfig("bell", sounds = listOf(TrainActionSoundConfig("bell")))))
            try {
                assertThrows(Exception::class.java) { fixture.behavior.metadata(false) }
                assertTrue(fixture.behavior.action("bell", false, fixture.completions::add) is TrainModelOperationResult.Rejected)
                assertTrue(fixture.completions.single().isFailure)
                assertTrue(fixture.sounds.isEmpty())
                assertTrue(fixture.delayed.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test
    fun `door planner is isolated read only and identical for estimate and execution with arbitrary action names`() {
        val fixture = Fixture(TrainBehaviorScript("planner.js", """
            let calls = 0;
            function door() { calls++; return [calls === 1 ? 'custom-door' : 'wrong']; }
            function state() { sound('state'+calls); }
        """.trimIndent()), mapOf("custom-door" to TrainActionConfig("custom-door", sounds = listOf(TrainActionSoundConfig("door")))))
        fixture.use {
            assertEquals(0L, it.behavior.estimateDoorCloseDurationTicks(DoorSide.LEFT, false))
            assertTrue(it.sounds.isEmpty())
            it.behavior.door(DoorSide.LEFT, true, false, it.completions::add)
            it.call("state")
            assertEquals(listOf("door", "state0"), it.sounds)
            assertTrue(it.completions.all { result -> result.isSuccess })
        }
    }

    @Test
    fun `door plan cannot produce effects or return non string unbounded arrays`() {
        listOf("action('bell'); return ['bell'];", "sound('bell'); return ['bell'];", "log('side effect'); return ['bell'];",
            "return null;", "return [];", "return [1];", "return Array(129).fill('bell');").forEach { body ->
            Fixture(TrainBehaviorScript("invalid.js", "function door(){ $body }"),
                mapOf("bell" to TrainActionConfig("bell", sounds = listOf(TrainActionSoundConfig("bell"))))).use { fixture ->
                assertThrows(TrainBehaviorScriptException::class.java) {
                    fixture.behavior.estimateDoorCloseDurationTicks(DoorSide.LEFT, false)
                }
                assertTrue(fixture.sounds.isEmpty())
                assertTrue(fixture.animations.isEmpty())
            }
        }
    }

    private class Fixture(script: TrainBehaviorScript?, actions: Map<String, TrainActionConfig> = emptyMap()) : AutoCloseable {
        var currentScript = script
        val sounds = mutableListOf<String>()
        val animations = mutableListOf<String>()
        val completions = mutableListOf<Result<Unit>>()
        val delayed = mutableListOf<Delayed>()
        val behavior = CarBehavior({ currentScript }, { actions }, { name, scene ->
            animations.add("$name:$scene")
            if (name != "missing") java.util.concurrent.CompletableFuture.completedFuture(Unit)
            else java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("animation unavailable"))
        }, { key, _, _ -> sounds.add(key) }, { _, effect ->
            Delayed(effect).also { delayed.add(it) }
        }, {}, {}, { name, _ -> name != "missing" }, { _, _, _ -> 0L }, { actions })
        fun call(method: String) = behavior.call(method, emptyList(), false, completions::add)
        override fun close() = behavior.close()
    }

    private class Delayed(private val effect: () -> Unit) : AutoCloseable {
        private var closed = false
        fun run() { if (!closed) { closed = true; effect() } }
        override fun close() { closed = true }
    }
}
