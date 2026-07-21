package work.art1st.mineRails.scriptworker

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScriptWorkerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun run(body: String) = json.decodeFromString<SavedTimetableV1>(executeScript("function main(timetable) { $body }", "test"))

    @Test fun `valid core DSL produces current snapshot`() {
        val result = run("""
            timetable.setPeriodDays(1);
            timetable.service('s1', 'train-1', function(t) {
              t.check('depot'); t.at(10).pass('a').destination('b'); t.at(20).wait(5);
              t.at(30).reverse(); t.at(40).halt();
            });
        """)
        assertEquals("test", result.id)
        assertEquals(listOf("a", "b"), result.services.single().steps[2].action!!.wayPointIds)
        assertEquals(SavedTimetableActionTypeV1.REVERSE, result.services.single().steps[4].action!!.type)
    }

    @Test fun `normal JavaScript if is evaluated while building`() {
        val result = run("timetable.setPeriodDays(1); const express = true; timetable.service('s','train', t => { if (express) t.at(1).destination('fast'); else t.at(1).destination('slow'); });")
        assertEquals(listOf("fast"), result.services.single().steps.last().action!!.wayPointIds)
    }

    @Test fun `runtime conditional is serialized rather than evaluated`() {
        val result = run("""
          timetable.setPeriodDays(1);
          const occupied = timetable.predicate('minerails:occupied', { section: 'A' });
          timetable.service('s','train', t => { t.check('x'); t.at(1).destination('y'); t.at(2).whenCondition(occupied, a => a.action('demo:ring', {count: 2}), a => a.action('demo:wait', {})); });
        """)
        val action = result.services.single().steps.last().action!!
        assertEquals(SavedTimetableActionTypeV1.CONDITIONAL, action.type)
        assertEquals("minerails:occupied", action.condition!!.call!!.typeId)
        assertEquals(1, action.elseActions.size)
    }

    @Test fun `conditional service activation is serialized`() {
        val result = run("""
          timetable.setPeriodDays(1);
          const demand = timetable.predicate('demo:demand', { minimum: 2 });
          timetable.service('s', 'train', { activation: { type: 'conditional', condition: demand, falseBehavior: 'SKIP_CYCLE' } }, t => t.at(1).destination('a'));
        """)
        val activation = result.services.single().activation
        assertEquals(SavedTimetableActivationTypeV1.CONDITIONAL, activation.type)
        assertEquals("demo:demand", activation.condition?.call?.typeId)
        assertEquals("SKIP_CYCLE", activation.falseBehavior)
    }

    @Test fun `options reject unknown fields and normalize door side`() {
        val result = run("timetable.setPeriodDays(1); timetable.service('s','train', t => t.at(1).destination('central@one',{doorSide:'left'}));")
        assertEquals("LEFT", result.services.single().steps.first().action?.doorSideOverride)
        assertThrows(Throwable::class.java) {
            run("timetable.setPeriodDays(1); timetable.service('s','train', t => t.at(1).destination('central@one',{plannedDepartureTick:2}));")
        }
    }

    @Test fun `legacy goto call and stop APIs are not exposed`() {
        assertThrows(Throwable::class.java) {
            run("timetable.setPeriodDays(1); timetable.service('s','train', t => t.at(1).goto('a'));")
        }
        assertThrows(Throwable::class.java) {
            run("timetable.setPeriodDays(1); timetable.service('s','train', t => t.at(1).call('station','one'));")
        }
        assertThrows(Throwable::class.java) {
            run("timetable.setPeriodDays(1); timetable.service('s','train', t => t.at(1).stop(20));")
        }
    }

    @Test fun `host classes are inaccessible`() {
        val error = assertThrows(Throwable::class.java) { run("timetable.setPeriodDays(1); Java.type('java.lang.System');") }
        assertTrue(error.toString().contains("Java") || error.toString().contains("host", ignoreCase = true))
    }
}
