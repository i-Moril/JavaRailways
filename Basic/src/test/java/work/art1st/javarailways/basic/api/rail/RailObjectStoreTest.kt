package work.art1st.javarailways.basic.api.rail

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.RailLocation
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.TestWorldFixture

class RailObjectStoreTest {
    private val fixtures = mutableListOf<TestWorldFixture>()

    private fun newTestWorld() = TestWorldFixture.create().also { fixtures += it }.world

    @AfterEach
    fun closeTestWorlds() {
        var firstFailure: Throwable? = null
        fixtures.asReversed().forEach { fixture ->
            try {
                fixture.close()
            } catch (failure: Throwable) {
                val primary = firstFailure
                if (primary == null) firstFailure = failure else primary.addSuppressed(failure)
            }
        }
        fixtures.clear()
        firstFailure?.let { throw it }
    }

    @Test
    fun `generic owner must publish removed lifecycle before unregistering`() {
        val world = newTestWorld()
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

    @Test
    fun `registration failure leaves no half-registered entry`() {
        val world = newTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        val ref = RailObjectRef("test", "half-registered")
        val failing = object : RailObject {
            override val ref = ref
            override var lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = object : RailLocation by rail.viewAB.getRailLocationAt(20.0) {
                override val rail: Rail
                    get() = error("injected location failure")
            }
        }
        val store = RailObjectStore()

        assertThrows(IllegalStateException::class.java) { store.register(failing) }
        assertNull(store.get(ref))

        val recovered = object : RailObject {
            override val ref = ref
            override var lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = rail.viewAB.getRailLocationAt(20.0)
        }
        store.register(recovered)
        assertSame(recovered, store.get(ref))
        assertSame(recovered, store.objectsOnRail(rail.id).single())
    }
}
