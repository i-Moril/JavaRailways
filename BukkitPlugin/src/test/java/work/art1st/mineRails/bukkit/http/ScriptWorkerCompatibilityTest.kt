package work.art1st.mineRails.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.timetable.TimetableServiceActivation
import work.art1st.mineRails.basic.persistence.TimetableSnapshotCodec
import work.art1st.mineRails.basic.timetable.TimetableAction
import work.art1st.mineRails.scriptworker.executeScript

class ScriptWorkerCompatibilityTest {
    @Test
    fun `worker JSON restores through authoritative Basic snapshot codec`() {
        val output = executeScript("""
            function main(timetable) {
              timetable.setPeriodDays(1)
              const demand = timetable.predicate('demo:demand', { minimum: 1 })
              timetable.service('s1', 'train-1', {
                lineId: 'metro',
                activation: { type: 'conditional', condition: demand, falseBehavior: 'SKIP_CYCLE' }
              }, t => {
                t.check('origin')
                t.at(10).goto('central@one')
                t.at(20).whenCondition(demand, a => a.action('demo:ring', { count: 2 }))
              })
            }
        """.trimIndent(), "demo")

        val restored = TimetableSnapshotCodec.decode(output).restore()

        assertEquals("metro", restored.services.single().lineId?.value)
        assertEquals(
            TimetableServiceActivation.Conditional::class,
            restored.services.single().activation::class
        )
        assertEquals(
            TimetableAction.Conditional::class,
            (restored.services.single().steps.last() as work.art1st.mineRails.basic.timetable.TimetableStep.At).action::class
        )
    }
}
