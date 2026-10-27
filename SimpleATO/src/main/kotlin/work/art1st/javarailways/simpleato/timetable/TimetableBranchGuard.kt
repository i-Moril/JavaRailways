package work.art1st.javarailways.simpleato.timetable

internal data class TimetableProgramLimits(val maximumNodes: Int = 4096, val maximumBranchDepth: Int = 32) {
    init { require(maximumNodes > 0 && maximumBranchDepth > 0) }
}

internal data class TimetableBranchViolation(val address: TimetableStepAddress, val code: String, val detail: String)

/** Post-expansion fixed-obligation guard. Does not replace geometric/provider/timing validation. */
internal object TimetableBranchGuard {
    fun validate(program: TimetableProgramTraversal, conditionalActivation: Boolean = false,
        limits: TimetableProgramLimits = TimetableProgramLimits()): List<TimetableBranchViolation> {
        require(program.rootSize >= 0)
        data class Sequence(val parent: TimetableStepAddress?, val arm: TimetableBranchArm?, val size: Int, var index: Int = 0)
        val pending = java.util.ArrayDeque<Sequence>()
        pending.addLast(Sequence(null, null, program.rootSize))
        val violations = arrayListOf<TimetableBranchViolation>()
        var visited = 0
        var activeTask: String? = null
        val taskIds = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val sequence = pending.last()
            if (sequence.index >= sequence.size) { pending.removeLast(); continue }
            val index = sequence.index++
            val address = sequence.parent?.child(checkNotNull(sequence.arm), index) ?: TimetableStepAddress(index)
            if (++visited > limits.maximumNodes) {
                violations += TimetableBranchViolation(address, "PROGRAM_LIMIT", "Program exceeds ${limits.maximumNodes} nodes")
                break
            }
            val step = program.stepAt(address)
            val nested = address.branchPath.isNotEmpty()
            if (step is TimetableStep.Check) {
                if (nested) violations += TimetableBranchViolation(address, "BRANCH_CHECK_NOT_ALLOWED", "Branch children must be timed At steps")
                continue
            }
            val action = (step as TimetableStep.At).action
            if (action is TimetableAction.TaskStart || action is TimetableAction.TaskEnd) {
                if (nested) violations += TimetableBranchViolation(address, "CONDITIONAL_TASK_BOUNDARY", "Task boundaries must be top-level")
                else when (action) {
                    is TimetableAction.TaskStart -> {
                        if (activeTask != null || !taskIds.add(action.taskId)) violations += TimetableBranchViolation(address, "INVALID_TASK_BOUNDARY", "Task starts must be unique and cannot overlap")
                        activeTask = action.taskId
                    }
                    is TimetableAction.TaskEnd -> {
                        if (activeTask != action.taskId) violations += TimetableBranchViolation(address, "INVALID_TASK_BOUNDARY", "Task end must match the active task")
                        activeTask = null
                    }
                    else -> Unit
                }
            }
            val code = when {
                nested && (action is TimetableAction.TakeFromDepot || action is TimetableAction.TakeFromWaypoint || action is TimetableAction.TakeFromFormationOutput) -> "CONDITIONAL_ACQUISITION"
                (nested || conditionalActivation) && (action is TimetableAction.StoreAtDepot || action is TimetableAction.Couple || action is TimetableAction.Split) -> "CONDITIONAL_FIXED_EVENT"
                nested && action is TimetableAction.BeginOperationalTrip -> "CONDITIONAL_TRIP_BOUNDARY"
                nested && action is TimetableAction.Halt -> "CONDITIONAL_TERMINATION"
                else -> null
            }
            if (code != null) violations += TimetableBranchViolation(address, code,
                "${action::class.simpleName} cannot be skipped by a runtime condition")
            if (action is TimetableAction.Conditional) {
                if (address.branchPath.size >= limits.maximumBranchDepth) {
                    violations += TimetableBranchViolation(address, "PROGRAM_LIMIT", "Program exceeds branch depth ${limits.maximumBranchDepth}")
                    break
                }
                // Stack stores two sequence frames, never allocates one entry per declared child.
                for (arm in listOf(TimetableBranchArm.ELSE, TimetableBranchArm.THEN)) {
                    val size = program.branchSize(address, arm)
                    require(size >= 0) { "Negative branch size at $address" }
                    pending.addLast(Sequence(address, arm, size))
                }
            }
        }
        if (activeTask != null) violations += TimetableBranchViolation(TimetableStepAddress((program.rootSize - 1).coerceAtLeast(0)), "INVALID_TASK_BOUNDARY", "Task has no end boundary")
        return java.util.List.copyOf(violations)
    }
}

/** Authored earliest ticks join across both arms; physical completion times remain independent. */
internal object TimetableProgramSchedule {
    fun validate(program: TimetableProgramTraversal, periodTicks: Long): List<TimetableBranchViolation> {
        require(periodTicks > 0)
        val limits = TimetableBranchGuard.validate(program).filter { it.code == "PROGRAM_LIMIT" }
        if (limits.isNotEmpty()) return limits
        val errors = arrayListOf<TimetableBranchViolation>()
        fun sequence(parent: TimetableStepAddress?, arm: TimetableBranchArm?, count: Int, inheritedTick: Long): Long {
            var earliest = inheritedTick
            for (index in 0 until count) {
                val address = parent?.child(checkNotNull(arm), index) ?: TimetableStepAddress(index)
                val step = program.stepAt(address) as? TimetableStep.At ?: continue
                if (step.tick < earliest || step.tick < 0 || step.tick >= periodTicks) errors +=
                    TimetableBranchViolation(address, "INVALID_STEP_TICK", "Tick ${step.tick} must be at least $earliest and below period $periodTicks")
                earliest = maxOf(earliest, step.tick)
                if (step.action is TimetableAction.Conditional) {
                    earliest = maxOf(
                        sequence(address, TimetableBranchArm.THEN, program.branchSize(address, TimetableBranchArm.THEN), earliest),
                        sequence(address, TimetableBranchArm.ELSE, program.branchSize(address, TimetableBranchArm.ELSE), earliest))
                }
            }
            return earliest
        }
        sequence(null, null, program.rootSize, 0)
        return java.util.List.copyOf(errors)
    }
}
