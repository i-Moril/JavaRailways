package work.art1st.javarailways.simpleato.control

import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.simpleato.api.atp.*
import work.art1st.javarailways.simpleato.atp.ConservativeAtpModel

enum class VehicleExecutionMode { AUTO, MANUAL, SIMULATION }
enum class AtpEventKind { TRIGGERED, RELEASED, RELEASE_REFUSED }
data class AtpControlEvent(val kind: AtpEventKind, val mode: VehicleExecutionMode,
    val state: VehicleControlState, val candidate: VehicleControlCommand,
    val judgement: AtpJudgement, val reference: ReferenceControlDecision?, val capabilityIdentity: Any,
    val mostAdverseGrade: Double, val targets: List<AtpTarget>, val context: Any?,
    val assessmentKind: String)
class AtpSimulationAbort(val event: AtpControlEvent) : IllegalStateException("ATP simulation aborted: $event")

/** Physics-worker-owned executor. Only ordinary strategy decisions are throttled; protection is not. */
class VehicleControlExecutor(private val onEvent: (AtpControlEvent) -> Unit = {}) {
    private var model: ConservativeAtpModel? = null
    private var reference: NominalMotionReference? = null
    private val driving = DrivingReferenceStrategy()
    private val stopping = StoppingReferenceStrategy()
    private var stopId: String? = null
    private var stopDistance: Double? = null
    private var signature: Long = 0L
    private var decisionAge = Double.POSITIVE_INFINITY
    private var manualAcknowledgementRequired = false
    @Volatile private var acknowledgementRequested = false
    private val reserveJudgements = HashMap<VehicleControlCommand, AtpJudgement>()
    private val plainJudgements = HashMap<VehicleControlCommand, AtpJudgement>()
    private val emergencyJudgements = HashMap<VehicleControlCommand, AtpJudgement>()
    var intervention: AtpControlEvent? = null; private set
    var lastDecision: ReferenceControlDecision? = null; private set
    var planningReserveAvailable: Boolean = true; private set

    fun prepare(capabilities: VehicleControlCapabilities) {
        if (model?.capabilities?.configurationIdentity === capabilities.configurationIdentity) return
        model = ConservativeAtpModel(capabilities)
        reference = runCatching { NominalMotionReference.fromCapabilities(capabilities) }.getOrNull()
        stopping.reset(); stopId = null; stopDistance = null; lastDecision = null; decisionAge = Double.POSITIVE_INFINITY
    }
    fun acknowledgeManual(): Boolean {
        if (intervention == null) return false
        acknowledgementRequested = true
        return true
    }
    fun choose(input: MotionReferenceInput, targets: List<AtpTarget>, mostAdverseGrade: Double,
        mode: VehicleExecutionMode = VehicleExecutionMode.AUTO,
        proposedCommand: VehicleControlCommand? = null, context: Any? = null,
        inputUnavailableReason: String? = null): VehicleControlCommand {
        prepare(input.capabilities)
        val protection = model!!
        reserveJudgements.clear(); plainJudgements.clear(); emergencyJudgements.clear()
        val current = if (inputUnavailableReason != null) AtpJudgement(AtpStatus.UNAVAILABLE,
            AtpReason.INPUT_UNAVAILABLE, inputUnavailableReason) else protection.emergency(input.state,
                targets, mostAdverseGrade, input.maximumControlDeltaSeconds)
        var assessment = "CURRENT_EMERGENCY"
        var judgement = current
        var candidate = proposedCommand ?: lastDecision?.command ?: input.state.lastCommand
        fun candidateJudgement(command: VehicleControlCommand, withReserve: Boolean = false): AtpJudgement {
            val cache = if (withReserve) reserveJudgements else plainJudgements
            cache[command]?.let { return it }
            val eb = emergencyJudgements[command] ?: protection.afterCandidate(input.state, command,
                input.deltaSeconds, targets, mostAdverseGrade, input.maximumControlDeltaSeconds,
                currentCapabilities = input.capabilities).also { emergencyJudgements[command] = it }
            val result = if (eb.status != AtpStatus.SAFE || command.emergencyBrake) eb else if (withReserve &&
                candidateJudgement(command, false).status != AtpStatus.SAFE) candidateJudgement(command, false) else
                protection.afterCandidate(input.state, command, input.deltaSeconds, targets,
                    mostAdverseGrade, input.maximumControlDeltaSeconds, serviceRecovery = true,
                    currentCapabilities = input.capabilities, planningReserve = withReserve)
            cache[command] = result
            return result
        }
        if (current.status == AtpStatus.SAFE) {
            if (mode == VehicleExecutionMode.MANUAL) {
                candidate = proposedCommand ?: VehicleControlCommand.COAST
                // Manual proposal supervises its known interval and EB recovery, not operating service policy.
                judgement = protection.afterCandidate(input.state, candidate, input.deltaSeconds, targets,
                    mostAdverseGrade, input.maximumControlDeltaSeconds, currentCapabilities = input.capabilities)
                assessment = "MANUAL_PROPOSAL"
            } else {
                val ideal = reference?.forGrade(mostAdverseGrade)
                if (ideal == null) {
                    judgement = AtpJudgement(AtpStatus.UNAVAILABLE, AtpReason.INVALID_MODEL,
                        "no ordinary operating profile for this grade")
                    assessment = "OPERATING_MODEL"
                } else {
                    val target = ReferenceStrategyMode.stoppingTarget(ideal, input, stopId, stopDistance)
                    val nextSignature = constraintSignature(input.constraints)
                    val targetChanged = target?.id != stopId || nextSignature != signature
                    if (targetChanged) stopping.reset()
                    stopId = target?.id; stopDistance = target?.distanceMeters; signature = nextSignature
                    decisionAge += input.deltaSeconds
                    val heldCommand = lastDecision?.command
                    val heldJudgement = heldCommand?.let { candidateJudgement(it, true) }
                    val heldSafe = heldJudgement?.status == AtpStatus.SAFE
                    val relaxedProtection = hardTargetsBeyondProtection(input, targets, current)
                    var nearestOperatingStop = Double.POSITIVE_INFINITY
                    for (constraint in input.constraints)
                        if (constraint.targetSpeedMetersPerSecond == 0.0)
                            nearestOperatingStop = minOf(nearestOperatingStop, constraint.distanceMeters)
                    // A cached cruising command may lose operating reachability before the next 0.2 s decision.
                    // This only requests a fresh ordinary decision; it is not a protection intervention.
                    // The target must first be inside a generous multiple of the ideal zero-speed
                    // distance; far targets cannot become reachable-loss events in one interval.
                    val operatingReconsider = if (!nearestOperatingStop.isFinite()) false else {
                        val nearEnough = nearestOperatingStop <=
                            ideal.stoppingDistanceMeters(input.state.speedMetersPerSecond) * 4.0 +
                            input.state.speedMetersPerSecond * 2.0 + 50.0
                        if (!nearEnough) false else {
                            val nominalHeldDistance = if (heldSafe)
                                NominalServiceBrakeModel.stoppingDistanceMeters(input, lastDecision!!.command) else 0.0
                            !nominalHeldDistance.isFinite() || nominalHeldDistance >= nearestOperatingStop
                        }
                    }
                    // Terminal control follows actual intervals; cruise reuses a checked demand for up to 0.2 s.
                    if (targetChanged || decisionAge >= 0.2 || target != null || !heldSafe || operatingReconsider) {
                        fun select(withReserve: Boolean, relaxed: Boolean = false): ReferenceControlDecision {
                            val admissible: (VehicleControlCommand) -> Boolean = { command ->
                                (withReserve || command.tractionRatio == 0.0) &&
                                    (relaxed || candidateJudgement(command, withReserve).status == AtpStatus.SAFE)
                            }
                            return if (target != null) stopping.select(ideal, input, target, admissible)
                                else driving.select(ideal, input, admissible)
                        }
                        val planned = select(true, relaxedProtection)
                        // The relaxed catalogue still commits only a command that passes the
                        // normal hard check. A failure restores the strict per-candidate path.
                        val relaxedChosen = if (relaxedProtection &&
                            candidateJudgement(planned.command, true).status != AtpStatus.SAFE)
                            select(true, false) else planned
                        planningReserveAvailable = relaxedChosen.ordinaryCommandAdmissible
                        lastDecision = if (planningReserveAvailable) relaxedChosen else select(false, false)
                        decisionAge = 0.0
                    }
                    candidate = lastDecision!!.command
                    // A reserve-safe judgement for the same command is also safe without the
                    // extra planning reserve; reuse it instead of running the model twice.
                    judgement = if (heldJudgement != null && heldJudgement.status == AtpStatus.SAFE &&
                        candidate == heldCommand) heldJudgement else candidateJudgement(candidate)
                    assessment = "ORDINARY_RECOVERY"
                    if (!lastDecision!!.ordinaryCommandAdmissible && judgement.status == AtpStatus.SAFE)
                        judgement = AtpJudgement(AtpStatus.INTERVENTION, AtpReason.NO_ORDINARY_RECOVERY,
                            "no hard-safe ordinary candidate")
                }
            }
        }
        val old = intervention
        if (old != null) {
            if (mode == VehicleExecutionMode.SIMULATION) throw AtpSimulationAbort(old)
            val acknowledged = !manualAcknowledgementRequired || acknowledgementRequested
            acknowledgementRequested = false
            if (input.state.speedMetersPerSecond == 0.0 && acknowledged && judgement.status == AtpStatus.SAFE) {
                intervention = null; manualAcknowledgementRequired = false
                onEvent(event(AtpEventKind.RELEASED, mode, input, candidate, judgement, targets,
                    mostAdverseGrade, context, assessment))
            } else return VehicleControlCommand.EMERGENCY_BRAKE
        }
        if (judgement.status != AtpStatus.SAFE) {
            val incident = event(AtpEventKind.TRIGGERED, mode, input, candidate, judgement, targets,
                mostAdverseGrade, context, assessment)
            intervention = incident
            manualAcknowledgementRequired = mode == VehicleExecutionMode.MANUAL
            onEvent(incident)
            if (mode == VehicleExecutionMode.SIMULATION) throw AtpSimulationAbort(incident)
            return VehicleControlCommand.EMERGENCY_BRAKE
        }
        return candidate
    }
    private fun event(kind: AtpEventKind, mode: VehicleExecutionMode, input: MotionReferenceInput,
        candidate: VehicleControlCommand, judgement: AtpJudgement, targets: List<AtpTarget>,
        grade: Double, context: Any?, assessment: String) = AtpControlEvent(kind, mode,
        input.state.copy(devices = input.state.devices.toList()), candidate, judgement, lastDecision,
        input.capabilities.configurationIdentity, grade, targets.toList(), context, assessment)
}

/** Stable O(constraints) target-shape signature without allocating a list of pairs. */
private fun constraintSignature(constraints: List<MotionReferenceConstraint>): Long {
    var hash = -0x340d631b7bdddcdbL
    for (constraint in constraints) {
        hash = (hash xor constraint.id.hashCode().toLong()) * 0x100000001b3L
        hash = (hash xor constraint.targetSpeedMetersPerSecond.toRawBits()) * 0x100000001b3L
    }
    return hash
}

/**
 * True while the current emergency certificate is safe and every hard target lies beyond a
 * conservative horizon for any ordinary command in the next known interval. This only
 * authorizes skipping per-candidate protection evaluation; the chosen command is still
 * hard-checked and a failure falls back to the strict predicate.
 */
internal fun hardTargetsBeyondProtection(input: MotionReferenceInput, targets: List<AtpTarget>,
    current: AtpJudgement): Boolean {
    if (current.status != AtpStatus.SAFE) return false
    if (targets.isEmpty()) return true
    val response = current.response ?: return false
    val h = input.maximumControlDeltaSeconds
    val downhill = maxOf(0.0, -input.capabilities.worstCurrentGrade) *
        input.capabilities.gravityMetersPerSecondSquared * input.capabilities.massToEffectiveMassRatio
    val speedAfter = input.state.speedMetersPerSecond +
        maxOf(0.0, input.capabilities.maximumTractionAccelerationMetersPerSecondSquared + downhill) * h
    // Two current emergency responses cover a traction interval, brake release and a
    // slower re-establishment; the interval terms cover the decision cache.
    val horizon = response.distanceMeters * 2.0 + speedAfter * h * 2.0 + 10.0
    return targets.all { it.distanceMeters > horizon }
}
