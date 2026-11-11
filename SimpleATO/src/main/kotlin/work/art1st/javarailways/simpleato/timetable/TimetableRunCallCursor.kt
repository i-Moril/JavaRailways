package work.art1st.javarailways.simpleato.timetable

/** The validator proves this exact ordered sequence against the scope's compiled movements. */
internal class TimetableRunCallCursor(calls: List<TimetableRunCall>) {
    private val origin = calls.firstOrNull()?.takeIf { it.status == TimetableServiceStopStatus.ORIGIN }
    private data class Event(val destinationId: String, val passing: Boolean, val call: TimetableRunCall?)
    private val events = calls.drop(1).flatMap { call ->
        call.viaIds.map { Event(it,true,null) } + Event(call.destinationId,call.status == TimetableServiceStopStatus.PASS,call)
    }
    private var index = 0
    var valid: Boolean = true
        private set

    /** A later CHECK at the same waypoint must not rewind an already travelled scope. */
    fun originForCheck(destinationId: String): TimetableRunCall? =
        origin?.takeIf { valid && index == 0 && it.destinationId == destinationId }

    fun reached(destinationId: String, passing: Boolean): TimetableRunCall? {
        if (!valid || events.isEmpty()) return null
        val event = events.getOrNull(index)
        if (event == null || event.destinationId != destinationId || event.passing != passing) {
            valid = false
            return null
        }
        index++
        return event.call
    }
}
