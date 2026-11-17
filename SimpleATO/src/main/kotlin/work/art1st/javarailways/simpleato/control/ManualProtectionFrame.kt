package work.art1st.javarailways.simpleato.control

import work.art1st.javarailways.basic.api.ato.VehicleControlCapabilities
import work.art1st.javarailways.basic.api.ato.RailGradeBounds
import work.art1st.javarailways.basic.api.ato.nativeBodySpeedLimits
import work.art1st.javarailways.basic.api.ato.traceBoundedSelectedTrackAhead
import work.art1st.javarailways.basic.api.ato.staticTrackObjects
import work.art1st.javarailways.basic.api.ato.trackTrainEnds
import work.art1st.javarailways.basic.api.ato.nativeRailSpeedLimit
import work.art1st.javarailways.basic.api.rail.OperationalSpeedLimitTransition
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.basic.api.route.CouplingContactAuthority
import work.art1st.javarailways.simpleato.api.atp.AtpTarget

/** Unrouted manual driving supervises visible physical track, obstacles and native speed limits. */
internal fun manualProtectionFrame(train: Train, capabilities: VehicleControlCapabilities,
                                   contactAuthority: CouplingContactAuthority? = null): TrainControlFrame {
        contactAuthority?.let { authority ->
            check(authority.proof.request.approachingTrainId == train.id &&
                train.world.validatePublishedCouplingApproach(authority.proof)) {
                "manual coupling contact geometry is no longer available"
            }
        }
        val physicalFrame = requireNotNull(train.publishedFrame) { "manual ATP requires a published frame" }
        val bodyLimits = nativeBodySpeedLimits(train, physicalFrame)
        val horizon = maxOf(100.0, capabilities.maximumSpeedMetersPerSecond * 30.0)
        val segments = traceBoundedSelectedTrackAhead(train.world, physicalFrame.head, horizon, 256)
        val targets = mutableListOf(AtpTarget("vehicle/max-speed", 0.0, capabilities.maximumSpeedMetersPerSecond))
        contactAuthority?.let {
            targets += AtpTarget("coupling/${it.eventId}/maximum-approach-speed", 0.0,
                it.maximumApproachSpeedMetersPerSecond)
        }
        // Preserve restrictions under the whole train until the rear has cleared them.
        bodyLimits.forEach { (railId, limit) ->
            targets += AtpTarget("infrastructure/body/$railId", 0.0, limit)
        }
        var distance = 0.0
        segments.forEachIndexed { index, segment ->
            nativeRailSpeedLimit(segment.railView.rail)?.let {
                targets += AtpTarget("infrastructure/${segment.railView.rail.id}/$index", distance, it)
            }
            val signals = staticTrackObjects(segment.railView).asSequence()
                .filter { it.lifecycleState == work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState.ACTIVE }
                .filterIsInstance<OperationalSpeedLimitTransition>()
                .sortedBy { it.location.arcLengthIn(segment.railView.viewDirection) }
                .toList()
            if (index == 0) {
                signals.lastOrNull { it.location.arcLengthIn(segment.railView.viewDirection) <= segment.startArcLength }
                    ?.speedLimitMetersPerSecond?.let { targets += AtpTarget("infrastructure/current-transition", 0.0, it) }
            }
            signals.forEach { signal ->
                val arc = signal.location.arcLengthIn(segment.railView.viewDirection)
                if (arc >= segment.startArcLength && arc <= segment.endArcLength) {
                    signal.speedLimitMetersPerSecond?.let {
                        targets += AtpTarget("infrastructure/${signal.ref.id}/$index",
                            distance + arc - segment.startArcLength, it)
                    }
                }
            }
            // A stationary-obstacle assumption is conservative for an unbound manual route.
            trackTrainEnds(segment.railView).forEach { end ->
                if (end.trainOrNull === train || end.lifecycleState !=
                    work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState.ACTIVE) return@forEach
                contactAuthority?.let { authority ->
                    val proof = authority.proof
                    val targetIsHead = (proof.request.stationaryEnd == TrainEndSide.A) != proof.stationaryFrame.reversed
                    if (end.trainOrNull?.id == proof.request.stationaryTrainId && end.isHead == targetIsHead)
                        return@forEach
                }
                val arc = end.location.arcLengthIn(segment.railView.viewDirection)
                if (arc >= segment.startArcLength && arc <= segment.endArcLength) {
                    targets += AtpTarget("following/${end.ref.id}/$index", distance + arc - segment.startArcLength, 0.0)
                }
            }
            distance += segment.totalArcLength
        }
        targets += AtpTarget("manual/observed-boundary", distance, 0.0)
        val grade = segments.fold(capabilities.worstCurrentGrade) { grade, segment ->
            minOf(grade, RailGradeBounds.minimumGrade(segment.railView.curve))
        }
        return TrainControlFrame(targets.map { MotionReferenceConstraint(it.id, it.distanceMeters, it.targetSpeedMetersPerSecond) }, targets, grade)
    }
