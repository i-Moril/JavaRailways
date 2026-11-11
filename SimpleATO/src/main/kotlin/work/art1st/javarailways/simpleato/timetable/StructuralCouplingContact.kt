package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.CouplingApproachProof
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot

/** Contact existence is certified separately from a conditional retained onward corridor. */
internal sealed interface StructuralContactFeasibility {
    /** Called with a fresh host-issued token on the serialized owner; never revives an old token. */
    fun requireContact(proof: CouplingApproachProof, currentTopology: TopologyState)
}

/** A geometric template, not an admission or an epoch-expiry exception. */
internal class ExactInitialStructuralContact private constructor(
    private val world: World,
    private val topology: TopologyState,
    private val template: CouplingApproachProof,
    private val approaching: ValidatedStructuralSource,
    private val stationary: ValidatedStructuralSource
) : StructuralContactFeasibility {
    override fun requireContact(proof: CouplingApproachProof, currentTopology: TopologyState) {
        require(currentTopology === topology && world.validateCouplingApproach(proof)) {
            "coupling contact requires a fresh host proof on the captured topology"
        }
        require(proof.request == template.request && samePose(proof.approachingFrame, template.approachingFrame) &&
            samePose(proof.stationaryFrame, template.stationaryFrame) && proof.contactHead == template.contactHead &&
            proof.headTraversal == template.headTraversal && proof.protectedTraversal == template.protectedTraversal) {
            "coupling contact geometry no longer matches the certified initial pair"
        }
        for ((source, frame) in listOf(approaching to proof.approachingFrame, stationary to proof.stationaryFrame)) {
            require(source.window.acceptsSource(frame, currentTopology)) { "coupling source is outside its event window" }
            source.envelope.requireContains(frame, currentTopology, source.sourceCarIds, source.expectedReversed)
        }
    }
    companion object {
        fun capture(world: World, topology: TopologyState, proof: CouplingApproachProof,
            approaching: ValidatedStructuralSource, stationary: ValidatedStructuralSource): ExactInitialStructuralContact =
            ExactInitialStructuralContact(world, topology, proof, approaching, stationary).also {
                it.requireContact(proof, topology)
            }

        private fun samePose(a: TrainFrameSnapshot, b: TrainFrameSnapshot): Boolean =
            a.trainId == b.trainId && a.reversed == b.reversed && a.head == b.head && a.tail == b.tail &&
                a.cars == b.cars && a.bodyTraversal == b.bodyTraversal && !a.derailed && a.speedMetersPerSecond == 0.0
    }
}

internal class StraightWindowStructuralContact(private val world: World, private val topology: TopologyState,
    private val geometry: work.art1st.javarailways.core.physics.train.StraightStoppedContact,
    private val approaching: ValidatedStructuralSource, private val stationary: ValidatedStructuralSource,
    private val approachingEnd: work.art1st.javarailways.core.api.physics.train.TrainEndSide,
    private val stationaryEnd: work.art1st.javarailways.core.api.physics.train.TrainEndSide) : StructuralContactFeasibility {
    override fun requireContact(proof: CouplingApproachProof, currentTopology: TopologyState) {
        require(currentTopology === topology && world.validateCouplingApproach(proof)) { "straight contact requires a fresh host proof" }
        require(proof.request.approachingEnd == approachingEnd && proof.request.stationaryEnd == stationaryEnd)
        for ((source, frame) in listOf(approaching to proof.approachingFrame, stationary to proof.stationaryFrame)) {
            require(source.window.acceptsSource(frame, currentTopology)) { "coupling source is outside its event window" }
            source.envelope.requireContains(frame, currentTopology, source.sourceCarIds, source.expectedReversed)
        }
        geometry.requireCurrentContact(proof, currentTopology)
    }
}
