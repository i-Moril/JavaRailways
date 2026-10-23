package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.route.FixedFormationChangeIntent
import work.art1st.javarailways.basic.api.route.FormationTrainIdentity
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.simpleato.api.depot.*

/** Complete-unit composition for one host-certified contact; the approaching container survives. */
internal class FixedUnitCouplePlan private constructor(
    val intent: FixedFormationChangeIntent.Couple,
    val ledgerChange: FixedFormationChange,
    val output: FixedFormationOwner
) {
    companion object {
        fun prepare(event: TimetableFormationEvent.Couple, instanceId: String,
            proof: CouplingApproachProof, approachingAssignmentId: String, stationaryAssignmentId: String,
            approachingGrant: RouteGrantSnapshot, stationaryGrant: RouteGrantSnapshot,
            outputAssignmentId: String, depots: DepotStore
        ): FixedUnitCouplePlan {
            val moving = proof.approachingFrame
            val stationary = proof.stationaryFrame
            require(!moving.derailed && !stationary.derailed &&
                moving.speedMetersPerSecond == 0.0 && stationary.speedMetersPerSecond == 0.0) {
                "coupling sources must be stopped and aligned"
            }
            require(approachingGrant.trainId == moving.trainId && stationaryGrant.trainId == stationary.trainId) {
                "coupling grants do not identify their respective sources"
            }
            val movingEnd = if (moving.reversed) TrainEndSide.B else TrainEndSide.A
            val stationaryHead = if (stationary.reversed) TrainEndSide.B else TrainEndSide.A
            val stationaryEnd = if (event.stationaryEnd == FormationStationaryEnd.HEAD) stationaryHead
                else if (stationaryHead == TrainEndSide.A) TrainEndSide.B else TrainEndSide.A
            require(proof.request.approachingEnd == movingEnd && proof.request.stationaryEnd == stationaryEnd) {
                "contact proof does not match the authored operational ends"
            }
            val movingCars = moving.cars.map { it.carId }
            val stationaryCars = stationary.cars.map { it.carId }
            depots.fixedUnitsForFormation(movingCars)
            depots.fixedUnitsForFormation(stationaryCars)
            val incoming = if (movingEnd == stationaryEnd) stationaryCars.asReversed() else stationaryCars
            val outputCars = if (movingEnd == TrainEndSide.B) movingCars + incoming else incoming + movingCars
            // Also rejects overlapping units and incompatible control families across both sources.
            depots.fixedUnitsForFormation(outputCars)
            val output = FixedFormationOwner(moving.trainId, outputAssignmentId, outputCars, moving.reversed)
            val disposition = when (event.output.disposition) {
                is FormationOutputDisposition.Duty -> FixedFormationDisposition.CONTINUE_DUTY
                FormationOutputDisposition.Hold -> FixedFormationDisposition.HOLD
            }
            return FixedUnitCouplePlan(FixedFormationChangeIntent.Couple(instanceId,
                approachingGrant.grantId, stationaryGrant.grantId, proof,
                FormationTrainIdentity(output.trainId, output.carIds, output.reversed),
                event.maximumApproachSpeedMetersPerSecond),
                FixedFormationChange(instanceId, FixedFormationChangeKind.COUPLE,
                    listOf(FixedFormationOwner(moving.trainId, approachingAssignmentId, movingCars, moving.reversed),
                        FixedFormationOwner(stationary.trainId, stationaryAssignmentId, stationaryCars, stationary.reversed)),
                    listOf(FixedFormationOutput(output, disposition))), output)
        }
    }
}
