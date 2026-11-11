package work.art1st.javarailways.simpleato.control

import work.art1st.javarailways.basic.api.ato.VehicleControlCapabilities
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.simpleato.api.atp.AtpTarget

/** Unrouted manual driving supervises visible physical track, obstacles and native speed limits. */
internal fun manualProtectionFrame(train: Train, capabilities: VehicleControlCapabilities): TrainControlFrame {
        val physicalFrame = requireNotNull(train.publishedFrame) { "manual ATP requires a published frame" }
        val bodyLimits = nativeBodySpeedLimits(train, physicalFrame)
        val position = physicalFrame.head
        val rail = checkNotNull(train.world.getRail(position.railId)) { "manual head rail is missing" }
        val head = RailLocationWithFacing(rail.getView(position.facing) as RailView,
            if (position.facing == ViewDirection.AB) position.arcLengthInAbMeters
            else rail.curve.totalArcLength - position.arcLengthInAbMeters)
        val horizon = maxOf(100.0, capabilities.maximumSpeedMetersPerSecond * 30.0)
        val segments = mutableListOf<work.art1st.javarailways.core.physics.rail.RailSegment>()
        var view = head.railView
        var start = head.arcLength
        var observed = 0.0
        // Selected topology may contain cycles; cap traversal even for zero-length rail chains.
        for (index in 0 until 256) {
            val end = minOf(view.curve.totalArcLength, start + horizon - observed)
            check(end >= start) { "manual head outside selected route" }
            segments += work.art1st.javarailways.core.physics.rail.RailSegment(view, start, end)
            observed += end - start
            if (observed >= horizon) break
            val next = view.activeRail ?: break
            view = next
            start = 0.0
        }
        check(segments.isNotEmpty()) { "manual ATP has no forward route" }
        val targets = mutableListOf(AtpTarget("vehicle/max-speed", 0.0, capabilities.maximumSpeedMetersPerSecond))
        // Preserve restrictions under the whole train until the rear has cleared them.
        bodyLimits.forEach { (railId, limit) ->
            targets += AtpTarget("infrastructure/body/$railId", 0.0, limit)
        }
        var distance = 0.0
        segments.forEachIndexed { index, segment ->
            segment.railView.rail.infrastructureMetadata.speedLimits.effectiveLimitMetersPerSecond()?.let {
                targets += AtpTarget("infrastructure/${segment.railView.rail.id}/$index", distance, it)
            }
            val signals = segment.railView.staticRailObjects.asSequence()
                .filter { it.lifecycleState == work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState.ACTIVE }
                .filterIsInstance<OperationalSpeedLimitSignal>()
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
            segment.railView.dynamicRailObjects.forEach { end ->
                if (end.trainOrNull === train || end.lifecycleState !=
                    work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState.ACTIVE) return@forEach
                val arc = end.location.arcLengthIn(segment.railView.viewDirection)
                if (arc >= segment.startArcLength && arc <= segment.endArcLength) {
                    targets += AtpTarget("following/${end.ref.id}/$index", distance + arc - segment.startArcLength, 0.0)
                }
            }
            distance += segment.totalArcLength
        }
        targets += AtpTarget("manual/observed-boundary", distance, 0.0)
        val grade = RouteGradeBounds.mostAdverseRouteGrade(segments, capabilities.worstCurrentGrade)
        return TrainControlFrame(targets.map { MotionReferenceConstraint(it.id, it.distanceMeters, it.targetSpeedMetersPerSecond) }, targets, grade)
    }
