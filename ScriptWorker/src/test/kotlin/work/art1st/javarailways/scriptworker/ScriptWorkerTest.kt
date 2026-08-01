package work.art1st.javarailways.scriptworker

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

    @Test fun `waypoint acquisition accepts a string or ordered list at a nonzero tick`() {
        val single = run("timetable.setPeriodDays(1); timetable.service('s', timetable.takeFromWaypoint('station@1', 600), t => { t.at(700).destination('next'); });")
        val singleAction = single.services.single().steps.first().action!!
        assertEquals(SavedTimetableActionTypeV1.TAKE_FROM_WAYPOINT, singleAction.type)
        assertEquals(listOf("station@1"), singleAction.wayPointIds)
        assertEquals(600L, single.services.single().steps.first().tick)

        val list = run("timetable.setPeriodDays(1); timetable.service('s', timetable.takeFromWaypoint(['station@1', 'siding'], 600), t => { t.at(700).destination('next'); });")
        assertEquals(listOf("station@1", "siding"), list.services.single().steps.first().action!!.wayPointIds)
    }

    @Test fun `depot acquisition is a service selector rather than a callback action`() {
        val result = run("timetable.setPeriodDays(1); timetable.service('s', timetable.takeFromDepot('north', {tags:['metro']}, 500), t => t.at(700).destination('next'));")
        val first = result.services.single().steps.first()
        assertEquals(500L, first.tick)
        assertEquals(SavedTimetableActionTypeV1.TAKE_FROM_DEPOT, first.action?.type)
        assertEquals(setOf("metro"), first.action?.requiredTags)
        assertThrows(Throwable::class.java) {
            run("timetable.setPeriodDays(1); timetable.service('s', t => t.at(500).takeFromDepot('north'));")
        }
    }

    @Test fun `initial position selectors serialize full-match regex combinators`() {
        val result = run("""
          timetable.setPeriodDays(1);
          timetable.setInitialPosition(
            timetable.and('s100-.*', timetable.not('.*-maintenance')),
            timetable.or('yard@1', 'yard@2')
          );
          timetable.service('s', 's100-1', t => { t.check('yard@1'); t.at(1).destination('next'); });
        """)
        val initial = result.initialPositions.single()
        assertEquals(SavedObjectSelectorTypeV1.AND, initial.trainSelector.type)
        assertEquals("s100-.*", initial.trainSelector.selectors.first().pattern)
        assertEquals(SavedObjectSelectorTypeV1.NOT, initial.trainSelector.selectors.last().type)
        assertEquals(SavedObjectSelectorTypeV1.OR, initial.wayPointSelector.type)
    }

    @Test fun `selector not overload does not replace condition not`() {
        val result = run("""
          timetable.setPeriodDays(1);
          const blocked = timetable.predicate('demo:blocked', {});
          timetable.service('s', 'train', {
            activation: { type: 'conditional', condition: timetable.not(blocked) }
          }, t => t.at(1).destination('next'));
        """)
        assertEquals(SavedTimetableConditionTypeV1.NOT, result.services.single().activation.condition?.type)
    }

    @Test fun `normal JavaScript if is evaluated while building`() {
        val result = run("timetable.setPeriodDays(1); const express = true; timetable.service('s','train', t => { if (express) t.at(1).destination('fast'); else t.at(1).destination('slow'); });")
        assertEquals(listOf("fast"), result.services.single().steps.last().action!!.wayPointIds)
    }

    @Test fun `runtime conditional is serialized rather than evaluated`() {
        val result = run("""
          timetable.setPeriodDays(1);
          const occupied = timetable.predicate('javarailways:occupied', { section: 'A' });
          timetable.service('s','train', t => { t.check('x'); t.at(1).destination('y'); t.at(2).whenCondition(occupied, a => a.action('demo:ring', {count: 2}), a => a.action('demo:wait', {})); });
        """)
        val action = result.services.single().steps.last().action!!
        assertEquals(SavedTimetableActionTypeV1.CONDITIONAL, action.type)
        assertEquals("javarailways:occupied", action.condition!!.call!!.typeId)
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

    @Test fun `service diagram direction is serialized explicitly and legacy line id remains accepted`() {
        val result = run("timetable.setPeriodDays(1); timetable.service('s','train',{diagramLineId:'metro',lineDirection:'down'},t=>t.at(1).destination('a'));")
        assertEquals("metro", result.services.single().diagramLineId)
        assertEquals(SavedLineDirectionV1.DOWN, result.services.single().lineDirection)

        val legacy = run("timetable.setPeriodDays(1); timetable.service('s','train',{lineId:'legacy'},t=>t.at(1).destination('a'));")
        assertEquals("legacy", legacy.services.single().diagramLineId)
    }

    @Test fun `diagram lines and visual arrival ticks are serialized`() {
        val result = run("""
          timetable.setPeriodDays(1);
          timetable.diagramLine('metro', { upStations: ['a', 'b'], downStations: ['b', 'a'] });
          timetable.service('s','train',{diagramLineId:'metro'},t=>t.at(100).destination('b@1',{arrivalTick:900}));
        """)
        assertEquals(listOf("a", "b"), result.diagramLines.single().upStationIds)
        assertEquals(listOf("b", "a"), result.diagramLines.single().downStationIds)
        assertEquals(900L, result.services.single().steps.first().action?.arrivalTick)
    }

    @Test fun `origin scopes produce presentation-only step trace`() {
        val result = run("""
          timetable.setPeriodDays(1);
          timetable.service('duty-a','train',t => {
            t.origin('duty:duty-a/run:up-01', scoped => {
              scoped.check('a');
              scoped.at(100).destination('b', {arrivalTick: 200});
            });
            t.at(300).reverse();
          });
        """)
        assertEquals(listOf(0, 1, 2), result.compilationTrace.map { it.stepIndex })
        assertEquals(setOf("duty:duty-a/run:up-01"), result.compilationTrace.map { it.origin }.toSet())
        assertEquals(setOf("duty-a"), result.compilationTrace.map { it.serviceId }.toSet())
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
