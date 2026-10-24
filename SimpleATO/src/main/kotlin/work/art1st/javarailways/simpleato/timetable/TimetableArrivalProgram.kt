package work.art1st.javarailways.simpleato.timetable

/** Arrival hooks are stationary subtrees in the existing service cursor. */
internal fun TimetableAction.arrivalSteps(): List<TimetableStep.At> = when (this) {
    is TimetableAction.DestinationOptions -> arrivalSteps
    is TimetableAction.CallStation -> arrivalSteps
    is TimetableAction.SelectStop -> arrivalSteps
    else -> emptyList()
}

internal fun TimetableAction.isArrivalAction(): Boolean = when (this) {
    is TimetableAction.SetDisplay, is TimetableAction.ResetDisplay, is TimetableAction.Stop,
    TimetableAction.WaitUntil, is TimetableAction.Extension, is TimetableAction.Conditional -> true
    else -> false
}
