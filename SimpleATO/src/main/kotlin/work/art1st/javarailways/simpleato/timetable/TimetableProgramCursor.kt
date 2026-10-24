package work.art1st.javarailways.simpleato.timetable

/** A branch choice is part of a step identity, never flattened into the top-level index. */
enum class TimetableBranchArm { THEN, ELSE, ARRIVAL }

data class TimetableBranchEntry(val arm: TimetableBranchArm, val index: Int) {
    init { require(index >= 0) }
}

/** Immutable execution/validation address within one fixed applied service definition. */
class TimetableStepAddress(val topLevelIndex: Int, branchPath: Collection<TimetableBranchEntry> = emptyList()) {
    val branchPath: List<TimetableBranchEntry> = java.util.List.copyOf(branchPath)
    init { require(topLevelIndex >= 0) }
    fun child(arm: TimetableBranchArm, index: Int) = TimetableStepAddress(topLevelIndex, branchPath + TimetableBranchEntry(arm, index))
    fun parent(): TimetableStepAddress? = if (branchPath.isEmpty()) null else TimetableStepAddress(topLevelIndex, branchPath.dropLast(1))
    override fun equals(other: Any?) = other is TimetableStepAddress && topLevelIndex == other.topLevelIndex && branchPath == other.branchPath
    override fun hashCode() = 31 * topLevelIndex + branchPath.hashCode()
    override fun toString() = "step#$topLevelIndex" + branchPath.joinToString("") { "/${it.arm.name.lowercase()}[${it.index}]" }
}

/**
 * Read-only traversal of a fixed definition. Branch children are timed At steps; Check is root-only.
 * The model adapter is installed with the timed-branch model change, not a second execution path.
 */
internal interface TimetableProgramTraversal {
    val rootSize: Int
    fun stepAt(address: TimetableStepAddress): TimetableStep
    /** Conditional arms and stationary arrival hooks share addressed children. */
    fun branchSize(address: TimetableStepAddress, arm: TimetableBranchArm): Int
}

/** Pure continuation value; holds no ticket, condition result, controller or mutable program state. */
internal class TimetableProgramCursor private constructor(
    private val program: TimetableProgramTraversal,
    val address: TimetableStepAddress?
) {
    val isComplete: Boolean get() = address == null
    fun currentStep(): TimetableStep? = address?.let(program::stepAt)

    /** Caller first observes the enclosing At tick and evaluates its condition exactly once. */
    fun enter(arm: TimetableBranchArm): TimetableProgramCursor {
        val current = checkNotNull(address) { "Cannot enter a completed program" }
        check(arm == TimetableBranchArm.ARRIVAL || (program.stepAt(current) as? TimetableStep.At)?.action is TimetableAction.Conditional) {
            "Only a conditional step has THEN/ELSE children: $current"
        }
        val count = program.branchSize(current, arm)
        require(count >= 0) { "Negative branch size at $current" }
        return if (count == 0) advance() else TimetableProgramCursor(program, current.child(arm, 0))
    }

    fun at(target: TimetableStepAddress) = TimetableProgramCursor(program, target)

    /** Capture this successor when starting an asynchronous leaf; never advance a changed cursor. */
    fun advance(): TimetableProgramCursor {
        var current = checkNotNull(address) { "Cannot advance a completed program" }
        while (true) {
            val entry = current.branchPath.lastOrNull()
            if (entry == null) {
                val next = current.topLevelIndex + 1
                return TimetableProgramCursor(program, if (next < program.rootSize) TimetableStepAddress(next) else null)
            }
            val parent = checkNotNull(current.parent())
            val count = program.branchSize(parent, entry.arm)
            require(count > entry.index) { "Cursor is outside its fixed branch: $current" }
            if (entry.index + 1 < count) return TimetableProgramCursor(program, parent.child(entry.arm, entry.index + 1))
            current = parent
        }
    }

    companion object {
        fun start(program: TimetableProgramTraversal, topLevelIndex: Int = 0): TimetableProgramCursor {
            require(program.rootSize >= 0 && topLevelIndex in 0..program.rootSize)
            return TimetableProgramCursor(program, if (topLevelIndex == program.rootSize) null else TimetableStepAddress(topLevelIndex))
        }
    }
}

/** One traversal of the current timed model, shared by validation and execution. */
internal class TimetableServiceTraversal(service: TimetableService) : TimetableProgramTraversal {
    private val roots = java.util.List.copyOf(service.steps)
    override val rootSize: Int get() = roots.size
    override fun stepAt(address: TimetableStepAddress): TimetableStep {
        var step = roots[address.topLevelIndex]
        for (entry in address.branchPath) {
            step = children((step as? TimetableStep.At)?.action ?: error("Branch path enters Check: $address"), entry.arm)[entry.index]
        }
        return step
    }
    override fun branchSize(address: TimetableStepAddress, arm: TimetableBranchArm): Int =
        children((stepAt(address) as? TimetableStep.At)?.action ?: error("Step has no children: $address"), arm).size

    private fun children(action: TimetableAction, arm: TimetableBranchArm): List<TimetableStep.At> = when (arm) {
        TimetableBranchArm.ARRIVAL -> action.arrivalSteps()
        TimetableBranchArm.THEN -> (action as TimetableAction.Conditional).thenSteps
        TimetableBranchArm.ELSE -> (action as TimetableAction.Conditional).elseSteps
    }

}
