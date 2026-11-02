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
    private var signature: List<Pair<String, Double>> = emptyList()
    private var decisionAge = Double.POSITIVE_INFINITY
    private var manualAcknowledgementRequired = false
    @Volatile private var acknowledgementRequested = false
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
        val current = if (inputUnavailableReason != null) AtpJudgement(AtpStatus.UNAVAILABLE,
            AtpReason.INPUT_UNAVAILABLE, inputUnavailableReason) else protection.emergency(input.state,
                targets, mostAdverseGrade, input.maximumControlDeltaSeconds)
        var assessment = "CURRENT_EMERGENCY"
        var judgement = current
        var candidate = proposedCommand ?: lastDecision?.command ?: input.state.lastCommand
        val checked = HashMap<Pair<VehicleControlCommand, Boolean>, AtpJudgement>()
        val emergencyChecked = HashMap<VehicleControlCommand, AtpJudgement>()
        fun candidateJudgement(command: VehicleControlCommand, withReserve: Boolean = false): AtpJudgement = checked.getOrPut(command to withReserve) {
            val eb = emergencyChecked.getOrPut(command) {
                protection.afterCandidate(input.state, command, input.deltaSeconds, targets,
                    mostAdverseGrade, input.maximumControlDeltaSeconds, currentCapabilities = input.capabilities)
            }
            if (eb.status != AtpStatus.SAFE || command.emergencyBrake) eb else if (withReserve &&
                candidateJudgement(command, false).status != AtpStatus.SAFE) candidateJudgement(command, false) else
                protection.afterCandidate(input.state, command, input.deltaSeconds, targets,
                    mostAdverseGrade, input.maximumControlDeltaSeconds, serviceRecovery = true, currentCapabilities = input.capabilities, planningReserve = withReserve)
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
                    val nextSignature = input.constraints.map { it.id to it.targetSpeedMetersPerSecond }
                    val targetChanged = target?.id != stopId || nextSignature != signature
                    if (targetChanged) stopping.reset()
                    stopId = target?.id; stopDistance = target?.distanceMeters; signature = nextSignature
                    decisionAge += input.deltaSeconds
                    val heldSafe = lastDecision?.let { candidateJudgement(it.command, true).status == AtpStatus.SAFE } ?: false
                    val operatingStops = input.constraints.filter { it.targetSpeedMetersPerSecond == 0.0 }
                    val nominalHeldDistance = if (heldSafe && operatingStops.isNotEmpty())
                        NominalServiceBrakeModel.stoppingDistanceMeters(input, lastDecision!!.command) else 0.0
                    // A cached cruising command may lose operating reachability before the next 0.2 s decision.
                    // This only requests a fresh ordinary decision; it is not a protection intervention.
                    val operatingReconsider = operatingStops.any {
                        !nominalHeldDistance.isFinite() || nominalHeldDistance >= it.distanceMeters
                    }
                    // Terminal control follows actual intervals; cruise reuses a checked demand for up to 0.2 s.
                    if (targetChanged || decisionAge >= 0.2 || target != null || !heldSafe || operatingReconsider) {
                        fun select(withReserve: Boolean): ReferenceControlDecision {
                            val admissible: (VehicleControlCommand) -> Boolean = { command ->
                                (withReserve || command.tractionRatio == 0.0) &&
                                    candidateJudgement(command, withReserve).status == AtpStatus.SAFE
                            }
                            return if (target != null) stopping.select(ideal, input, target, admissible)
                                else driving.select(ideal, input, admissible)
                        }
                        val planned = select(true)
                        planningReserveAvailable = planned.ordinaryCommandAdmissible
                        lastDecision = if (planningReserveAvailable) planned else select(false)
                        decisionAge = 0.0
                    }
                    candidate = lastDecision!!.command
                    judgement = candidateJudgement(candidate)
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
