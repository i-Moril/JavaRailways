package work.art1st.javarailways.basic.api.ato.provider

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld

class RailObjectStoreTest {
    @Test
    fun `generic owner must publish removed lifecycle before unregistering`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        val store = RailObjectStore()
        val railObject = object : RailObject {
            override val ref = RailObjectRef("test", "owned-marker")
            override var lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = rail.viewAB.getRailLocationAt(20.0)
        }
        store.register(railObject)

        assertThrows(IllegalStateException::class.java) {
            store.unregister(railObject.ref)
        }
        assertSame(railObject, store.get(railObject.ref))

        railObject.lifecycleState = RailObjectLifecycleState.REMOVED
        assertSame(railObject, store.unregister(railObject.ref))
        assertNull(store.get(railObject.ref))
    }
}
