package work.art1st.javarailways.basic.infrastructure

import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld

class LiveTurnoutSelectionCommitterTest {
    private val worlds = mutableListOf<CoreWorld>()

    @AfterEach
    fun closeWorlds() {
        worlds.forEach { world ->
            world.physicsCoordinator.close()
            (world.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `zero selections are a no-op`() {
        val world = world()
        var writes = 0
        val committer = LiveTurnoutSelectionCommitter(world) { view, target ->
            writes++
            view.selectNextRail(target)
        }

        assertEquals(TurnoutCommitResult.Committed, committer.commit(emptyList()))
        assertEquals(0, writes)
    }

    @Test
    fun `reverse traversal aligns both stored directions of the same physical turnout`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.first.viewAB)
        fixture.second.viewBA.selectNextRail(null)

        val result = LiveTurnoutSelectionCommitter(fixture.world).commit(
            listOf(fixture.selection(fixture.second, fixture.stem))
        )

        assertEquals(TurnoutCommitResult.Committed, result)
        assertSame(fixture.second.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertSame(fixture.stem.viewBA, fixture.second.viewBA.nextActiveRail)
    }

    @Test
    fun `already aligned selection performs no live writes`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.second.viewAB)
        fixture.second.viewBA.selectNextRail(fixture.stem.viewBA)
        var writes = 0
        val committer = LiveTurnoutSelectionCommitter(fixture.world) { view, target ->
            writes++
            view.selectNextRail(target)
        }

        val result = committer.commit(listOf(fixture.selection(fixture.stem, fixture.second)))

        assertEquals(TurnoutCommitResult.Committed, result)
        assertEquals(0, writes)
    }

    @Test
    fun `multiple turnouts commit in frozen route order`() {
        val world = world()
        val firstTurnout = turnout(world, 0.0)
        val secondTurnout = turnout(world, 100.0)
        firstTurnout.stem.viewAB.selectNextRail(firstTurnout.first.viewAB)
        secondTurnout.stem.viewAB.selectNextRail(secondTurnout.first.viewAB)
        val writes = mutableListOf<java.util.UUID>()
        val committer = LiveTurnoutSelectionCommitter(world) { view, target ->
            writes += view.rail.id
            view.selectNextRail(target)
        }

        val result = committer.commit(listOf(
            firstTurnout.selection(firstTurnout.stem, firstTurnout.second),
            secondTurnout.selection(secondTurnout.stem, secondTurnout.second)
        ))

        assertEquals(TurnoutCommitResult.Committed, result)
        assertTrue(writes.indexOf(firstTurnout.stem.id) < writes.indexOf(secondTurnout.stem.id))
        assertSame(firstTurnout.second.viewAB, firstTurnout.stem.viewAB.nextActiveRail)
        assertSame(secondTurnout.second.viewAB, secondTurnout.stem.viewAB.nextActiveRail)
    }

    @Test
    fun `second directed apply failure restores every selection snapshot`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.first.viewAB)
        fixture.second.viewBA.selectNextRail(null)
        var writes = 0
        val committer = LiveTurnoutSelectionCommitter(fixture.world) { view, target ->
            writes++
            if (writes == 2) throw IllegalStateException("injected second apply failure")
            view.selectNextRail(target)
        }

        val result = committer.commit(listOf(fixture.selection(fixture.stem, fixture.second)))

        assertTrue(result is TurnoutCommitResult.Failed && result.rollbackSucceeded)
        assertSame(fixture.first.viewAB, fixture.stem.viewAB.nextActiveRail)
        assertEquals(null, fixture.second.viewBA.nextActiveRail)
    }

    @Test
    fun `rollback failure is reported after remaining snapshots are still restored`() {
        val world = world()
        val first = turnout(world, 0.0)
        val second = turnout(world, 100.0)
        first.stem.viewAB.selectNextRail(first.first.viewAB)
        first.second.viewBA.selectNextRail(null)
        second.stem.viewAB.selectNextRail(second.first.viewAB)
        second.second.viewBA.selectNextRail(null)
        var writes = 0
        val committer = LiveTurnoutSelectionCommitter(world) { view, target ->
            writes++
            if (writes == 4) throw IllegalStateException("injected apply failure")
            if (writes == 5) throw IllegalStateException("injected rollback failure")
            view.selectNextRail(target)
        }

        val result = committer.commit(listOf(
            first.selection(first.stem, first.second),
            second.selection(second.stem, second.second)
        ))

        assertTrue(result is TurnoutCommitResult.Failed && !result.rollbackSucceeded)
        result as TurnoutCommitResult.Failed
        assertEquals(1, result.rollbackFailures.size)
        assertSame(first.first.viewAB, first.stem.viewAB.nextActiveRail)
        assertEquals(null, first.second.viewBA.nextActiveRail)
        assertSame(second.second.viewAB, second.stem.viewAB.nextActiveRail)
    }

    private fun turnout(): Fixture = turnout(world(), 0.0)

    private fun turnout(world: CoreWorld, offset: Double): Fixture {
        val stem = rail(world, Vector3d(offset - 10.0, 0.0, 0.0), Vector3d(offset, 0.0, 0.0))
        val first = rail(world, Vector3d(offset, 0.0, 0.0), Vector3d(offset + 10.0, 0.0, -5.0))
        val second = rail(world, Vector3d(offset, 0.0, 0.0), Vector3d(offset + 10.0, 0.0, 5.0))
        CoreRail.connect(stem, first)
        CoreRail.connect(stem, second)
        return Fixture(world, stem, first, second)
    }

    private fun world(): CoreWorld = createTestWorld().also(worlds::add)

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail = CoreRail(
        world,
        straightCurve(from, to),
        mutableListOf(),
        mutableListOf()
    )

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, length, Curve.SegmentType.LINE_END)
        ))
    }

    private data class Fixture(
        val world: CoreWorld,
        val stem: CoreRail,
        val first: CoreRail,
        val second: CoreRail
    ) {
        fun selection(from: CoreRail, to: CoreRail): ResolvedTurnoutSelection {
            val fromPort = RailEndpointRef(
                from.id,
                if (from === stem) RailEndpoint.B else RailEndpoint.A
            )
            val toPort = RailEndpointRef(
                to.id,
                if (to === stem) RailEndpoint.B else RailEndpoint.A
            )
            return ResolvedTurnoutSelection(
                TurnoutResourceIdentity.fromPorts(listOf(
                    RailEndpointRef(stem.id, RailEndpoint.B),
                    RailEndpointRef(first.id, RailEndpoint.A),
                    RailEndpointRef(second.id, RailEndpoint.A)
                )),
                fromPort,
                toPort,
                10.0
            )
        }
    }
}
