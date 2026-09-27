package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.timetable.TimetableServiceActivation
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.scriptworker.executeScript

class ScriptWorkerCompatibilityTest {
    @Test
    fun `worker JSON restores through authoritative Basic snapshot codec`() {
        val output = executeScript("""
            function main(timetable) {
              timetable.setPeriodDays(1)
              const demand = timetable.predicate('demo:demand', { minimum: 1 })
              timetable.service('s1', 'train-1', {
                diagramLineId: 'metro',
                activation: { type: 'conditional', condition: demand, falseBehavior: 'SKIP_CYCLE' }
              }, t => {
                t.check('origin')
                t.at(10).destination('central@one', { minimumDwellTicks: 40, doorSide: 'LEFT' })
                t.at(20).whenCondition(demand, a => a.action('demo:ring', { count: 2 }))
              })
            }
        """.trimIndent(), "demo")

        val saved = TimetableSnapshotCodec.decode(output)
        val restored = saved.restore()

        assertEquals("metro", saved.services.single().diagramLineId)
        assertEquals(
            TimetableServiceActivation.Conditional::class,
            restored.services.single().activation::class
        )
        assertEquals(
            TimetableAction.Conditional::class,
            (restored.services.single().steps.last() as work.art1st.javarailways.simpleato.timetable.TimetableStep.At).action::class
        )
    }
}
