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

    @Test fun `initial position selectors support exact names and tags`() {
        val result = run("""
          timetable.setPeriodDays(1);
          timetable.setInitialPosition(timetable.and(timetable.named('s100.1'), timetable.tags('metro', 'available')), timetable.named('yard@1'));
          timetable.service('s', 's100.1', t => { t.check('yard@1'); t.at(1).destination('next'); });
        """)
        val position = result.initialPositions.single()
        assertEquals(SavedObjectSelectorTypeV1.AND, position.trainSelector.type)
        assertEquals("^\\Qs100.1\\E$", position.trainSelector.selectors[0].pattern)
        assertEquals(setOf("metro", "available"), position.trainSelector.selectors[1].tags)
        assertEquals("^\\Qyard@1\\E$", position.wayPointSelector.pattern)
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
        assertEquals(1, action.elseSteps.size)
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

    @Test fun `service diagram direction is serialized explicitly`() {
        val result = run("timetable.setPeriodDays(1); timetable.service('s','train',{diagramLineId:'metro',lineDirection:'down'},t=>t.at(1).destination('a'));")
        assertEquals("metro", result.services.single().diagramLineId)
        assertEquals(SavedLineDirectionV1.DOWN, result.services.single().lineDirection)
    }

    @Test fun `infrastructure line and all-train maximum speed are serialized separately from diagram metadata`() {
        val result = run("""
          timetable.setPeriodDays(1);
          timetable.lineSpeedLimit('metro', 22.0);
          timetable.service('s','train',{lineId:'metro',diagramLineId:'diagram'},t=>t.at(1).destination('a'));
        """)
        assertEquals("metro", result.services.single().lineId)
        assertEquals("diagram", result.services.single().diagramLineId)
        assertEquals(SavedTimetableLineSpeedLimitV1("metro", 22.0), result.lineSpeedLimits.single())
    }

    @Test fun `removed service options are rejected`() {
        assertThrows(Throwable::class.java) {
            run("timetable.setPeriod(24000); timetable.service('s','train',t=>t.at(1).destination('a'));")
        }
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

    @Test fun `timed JS branch callbacks share sequence builder and retain addressed origins`() {
        val result = run("""
            timetable.setPeriodDays(1);
            const condition = timetable.predicate('test:choose', {});
            timetable.service('s', 'train', t => {
              t.check('start');
              t.origin('outer', t => t.at(10).whenCondition(condition,
                yes => yes.origin('yes-leaf', a => a.at(20).action('test:action', {})),
                no => no.origin('no-leaf', a => a.at(30).action('test:action', {}))));
              t.action('test:suffix', {});
            });
        """)
        val steps = result.services.single().steps
        assertEquals(20L, steps[1].action!!.thenSteps.single().tick)
        assertEquals(30L, steps[1].action!!.elseSteps.single().tick)
        assertEquals(30L, steps.last().tick)
        val yes = result.compilationTrace.single { it.origin == "yes-leaf" }
        assertEquals(1, yes.stepIndex)
        assertEquals(listOf(SavedTimetableBranchPathEntryV1(SavedTimetableBranchArmV1.THEN, 0)), yes.branchPath)
        assertEquals(SavedTimetableBranchArmV1.ELSE,
            result.compilationTrace.single { it.origin == "no-leaf" }.branchPath.single().arm)
    }

    @Test fun `JS nested conditional cannot hide a fixed event or move suffix before arm schedule`() {
        for (body in listOf(
            "t.at(10).whenCondition(condition, a => a.whenCondition(condition, b => b.split('split')))",
            "t.at(10).whenCondition(condition, a => a.at(20).action('test:action', {})); t.at(15).action('test:suffix', {})")) {
            assertThrows(Throwable::class.java) {
                run("timetable.setPeriodDays(1); const condition=timetable.predicate('test:choose',{}); timetable.service('s','train',t=>{t.check('start'); $body;});")
            }
        }
    }


    @Test fun `captured timed builder compares emitted actions rather than unused selections`() {
        val saved = run("""
            timetable.setPeriodDays(1);
            timetable.service('s','train',t=>{
              t.check('start'); const earlier=t.at(5); t.at(20);
              earlier.destination('target'); t.at(6).action('test:action',{});
            });
        """)
        assertEquals(listOf(5L,5L,6L), saved.services.single().steps.drop(1).map { it.tick })
        assertThrows(Throwable::class.java) {
            run("timetable.setPeriodDays(1);timetable.service('s','train',t=>{const earlier=t.at(5);t.at(20).action('test:action',{});earlier.action('test:action',{});});")
        }
    }


    @Test fun `selectStop emits one movement action with ordered finite station candidates`() {
        val saved = run("""
            timetable.setPeriodDays(1);
            timetable.service('s','train',t=>{
              t.check('start'); t.at(5).selectStop('central',['long','short'],'AVAILABLE_PRIORITY',
                {minimumDwellTicks:30,stoppingOffsetMetersBefore:1.5,doorSide:'RIGHT'});
            });
        """)
        val steps = saved.services.single().steps
        assertEquals(2, steps.size)
        val action = steps.last().action!!
        assertEquals(SavedTimetableActionTypeV1.SELECT_STOP, action.type)
        assertEquals("central", action.stationId)
        assertEquals(listOf(SavedStationStopCandidateV1("long",0), SavedStationStopCandidateV1("short",1)), action.berthCandidates)
        assertEquals(SavedStationStopSelectionPolicyV1.AVAILABLE_PRIORITY, action.selectionPolicy)
        assertEquals(30L, action.minimumDwellTicks)
        assertEquals(1.5, action.stoppingOffsetMetersBefore)
        assertEquals("RIGHT", action.doorSideOverride)
        assertTrue(action.wayPointIds.isEmpty())
    }

    @Test fun `selectStop rejects empty duplicate candidates and unsupported policy before generation`() {
        for (call in listOf("'central',[],'FIXED_PREFERENCE'", "'central',['same','same'],'AVAILABLE_PRIORITY'",
            "'central',['one'],'PREDICTED_RELEASE'", "'central',['one'],'FIXED_PREFERENCE',{unknown:1}")) {
            assertThrows(Throwable::class.java) {
                run("timetable.setPeriodDays(1);timetable.service('s','train',t=>{t.check('start');t.selectStop($call);});")
            }
        }
    }

}
