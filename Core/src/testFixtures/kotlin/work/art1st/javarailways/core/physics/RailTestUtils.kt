package work.art1st.javarailways.core.physics

import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.UUID

/** Test-fixture-only synchronous creation; this helper is not packaged in the Core runtime JAR. */
fun CoreWorld.createRailForTest(curve: PrimeCurve): CoreRail =
    IsolatedSimulationThreadMarker.run {
        railTopologyTransaction { createRailInTransaction(UUID.randomUUID(), curve) }
    }

/** Test-fixture-only atomic batch creation preserving the caller's curve order. */
fun CoreWorld.createRailsForTest(curves: List<PrimeCurve>): List<CoreRail> =
    IsolatedSimulationThreadMarker.run {
        railTopologyTransaction {
            curves.map { curve -> createRailInTransaction(UUID.randomUUID(), curve) }
        }
    }
