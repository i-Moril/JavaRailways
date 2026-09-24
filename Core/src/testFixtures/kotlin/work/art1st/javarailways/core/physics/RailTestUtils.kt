package work.art1st.javarailways.core.physics

import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.CoreRail
import java.util.UUID

/** Test-fixture-only synchronous creation; this helper is not packaged in the Core runtime JAR. */
fun CoreWorld.createRailForTest(curve: PrimeCurve): CoreRail =
    railTopologyTransaction { createRailInTransaction(UUID.randomUUID(), curve) }

/** Test-fixture-only atomic batch creation preserving the caller's curve order. */
fun CoreWorld.createRailsForTest(curves: List<PrimeCurve>): List<CoreRail> =
    railTopologyTransaction {
        curves.map { curve -> createRailInTransaction(UUID.randomUUID(), curve) }
    }
