package work.art1st.javarailways.simpleato.timetable

/** Exact executed choices within one cycle; source duties namespace equal nested addresses. */
internal data class StructuralBranchDecision(val serviceId: String, val address: TimetableStepAddress,
    val arm: TimetableBranchArm) { init { require(serviceId.isNotBlank()) } }
internal data class StructuralStopDecision(val serviceId: String, val address: TimetableStepAddress,
    val berthId: String) { init { require(serviceId.isNotBlank()); require(berthId.isNotBlank()) } }

internal class StructuralBranchTrace private constructor(decisions: Collection<StructuralBranchDecision>,
    stops: Collection<StructuralStopDecision> = emptyList()) {
    val decisions: List<StructuralBranchDecision> = java.util.List.copyOf(decisions.sortedWith(
        compareBy<StructuralBranchDecision> { it.serviceId }.thenBy { it.address.toString() }))
    val stopDecisions: List<StructuralStopDecision> = java.util.List.copyOf(stops.sortedWith(
        compareBy<StructuralStopDecision> { it.serviceId }.thenBy { it.address.toString() }))
    fun record(serviceId: String, address: TimetableStepAddress, arm: TimetableBranchArm): StructuralBranchTrace {
        val existing = decisions.singleOrNull { it.serviceId == serviceId && it.address == address }
        if (existing != null) {
            require(existing.arm == arm) { "a fixed program branch was evaluated inconsistently" }
            return this
        }
        return StructuralBranchTrace(decisions + StructuralBranchDecision(serviceId, address, arm), stopDecisions)
    }
    fun recordStop(serviceId: String, address: TimetableStepAddress, berthId: String): StructuralBranchTrace {
        val existing = stopDecisions.singleOrNull { it.serviceId == serviceId && it.address == address }
        if (existing != null) {
            require(existing.berthId == berthId) { "a fixed program stop was committed inconsistently" }
            return this
        }
        return StructuralBranchTrace(decisions, stopDecisions + StructuralStopDecision(serviceId, address, berthId))
    }
    fun compatibleWith(other: StructuralBranchTrace): Boolean = decisions.all { decision ->
        other.decisions.none { it.serviceId == decision.serviceId && it.address == decision.address && it.arm != decision.arm }
    } && stopDecisions.all { decision ->
        other.stopDecisions.none { it.serviceId == decision.serviceId && it.address == decision.address && it.berthId != decision.berthId }
    }
    fun merge(other: StructuralBranchTrace): StructuralBranchTrace {
        val branches = other.decisions.fold(this) { result, decision -> result.record(decision.serviceId, decision.address, decision.arm) }
        return other.stopDecisions.fold(branches) { result, decision -> result.recordStop(decision.serviceId, decision.address, decision.berthId) }
    }
    /** Length-delimited transient lineage; never an authored or persisted identifier. */
    fun lineage(): String = decisions.joinToString("") {
        val address = it.address.toString()
        "C${it.serviceId.length}:${it.serviceId}${address.length}:$address:${it.arm.name};"
    } + stopDecisions.joinToString("") {
        val address = it.address.toString()
        "S${it.serviceId.length}:${it.serviceId}${address.length}:$address${it.berthId.length}:${it.berthId};"
    }
    override fun equals(other: Any?) = other is StructuralBranchTrace && decisions == other.decisions && stopDecisions == other.stopDecisions
    override fun hashCode() = 31 * decisions.hashCode() + stopDecisions.hashCode()
    companion object { val EMPTY = StructuralBranchTrace(emptyList()) }
}
