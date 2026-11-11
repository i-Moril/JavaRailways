package work.art1st.javarailways.simpleato.api.timetable

/** Separate inventory, completion-timing and storage-capacity conclusions. */
enum class TimetableCirculationStatus { PROVEN, IMPOSSIBLE, UNPROVEN }

data class TimetableCirculationFinding(
    val code: String,
    val detail: String,
    val dutyId: String? = null,
    val stepIndex: Int? = null,
    val blocking: Boolean = false
)

/** Public read-only compiler diagnostic; contains no live ledger or internal search graph. */
class TimetableCirculationSummary(
    val inventory: TimetableCirculationStatus,
    val timing: TimetableCirculationStatus,
    val capacity: TimetableCirculationStatus,
    findings: Collection<TimetableCirculationFinding>,
    val periodicInventoryCycles: Int? = null
) {
    val findings: List<TimetableCirculationFinding> = java.util.List.copyOf(findings)
    init { require(periodicInventoryCycles == null || periodicInventoryCycles > 0) }
}
