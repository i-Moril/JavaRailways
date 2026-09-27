package work.art1st.javarailways.bukkit.tool

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.basic.curve.Anchor
import java.util.UUID

class WandActionUndoSupportTest {
    @Test
    fun `every recorded rail wand action kind has an undo implementation`() {
        val worldId = UUID.randomUUID()
        val beforeAnchor = Anchor(
            id = "A1",
            position = Vector3d(1.0, 2.0, 3.0),
            tangent = Vector3d(1.0, 0.0, 0.0),
            worldId = worldId.toString()
        )
        val afterAnchor = beforeAnchor.withPosition(Vector3d(2.0, 2.0, 3.0))
        val emptyWorld = RailWorldUndoSnapshot(
            rails = emptyList(),
            renderMetas = emptyList(),
            railMetadata = emptyMap(),
            destinations = emptyList(),
            wayPoints = emptyList()
        )
        val actions = listOf(
            WandAction.CreateAnchor(worldId, beforeAnchor),
            WandAction.DeleteAnchor(worldId, beforeAnchor),
            WandAction.EditAnchors(worldId, listOf(beforeAnchor), listOf(afterAnchor)),
            WandAction.RestoreRailWorld(worldId, emptyWorld, emptyWorld),
            WandAction.RestoreRailMetadata(worldId, emptyMap(), emptyMap()),
            WandAction.RestoreDecorations(worldId, emptySet(), emptyList(), emptyList())
        )

        actions.forEach { action -> assertTrue(wandActionSupportsUndo(action), action::class.simpleName) }
        assertTrue(wandActionSupportsUndo(WandAction.MultiAction(actions)))
        assertFalse(wandActionSupportsUndo(WandAction.MultiAction(emptyList())))
    }

    @Test
    fun `redo support audit exposes every currently unsupported action kind`() {
        val worldId = UUID.randomUUID()
        val anchor = Anchor(
            id = "A1",
            position = Vector3d(1.0, 2.0, 3.0),
            tangent = Vector3d(1.0, 0.0, 0.0),
            worldId = worldId.toString()
        )
        val edited = anchor.withPosition(Vector3d(2.0, 2.0, 3.0))
        val emptyWorld = RailWorldUndoSnapshot(emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
        val unsupported = listOf(
            WandAction.CreateAnchor(worldId, anchor),
            WandAction.EditAnchors(worldId, listOf(anchor), listOf(edited)),
            WandAction.RestoreRailWorld(worldId, emptyWorld, emptyWorld),
            WandAction.RestoreRailMetadata(worldId, emptyMap(), emptyMap()),
            WandAction.RestoreDecorations(worldId, emptySet(), emptyList(), emptyList())
        )

        unsupported.forEach { action -> assertFalse(wandActionSupportsRedo(action), action::class.simpleName) }
        assertTrue(wandActionSupportsRedo(WandAction.DeleteAnchor(worldId, anchor)))
        assertFalse(wandActionSupportsRedo(WandAction.MultiAction(unsupported)))
        assertFalse(wandActionSupportsRedo(WandAction.MultiAction(emptyList())))
    }

    @Test
    fun `infrastructure restore plan changes only migrated records`() {
        val oldRail = UUID.randomUUID()
        val newRail = UUID.randomUUID()
        val unchangedRail = UUID.randomUUID()
        val currentDestination = OperationalDestination("changed", "Changed", newRail, 4.0)
        val targetDestination = currentDestination.copy(railId = oldRail, arcLengthInAbMeters = 2.0)
        val unchangedDestination = OperationalDestination("unchanged", "Unchanged", unchangedRail, 1.0)
        val currentWayPoint = WayPoint("standalone", "Standalone", newRail, 4.0)
        val targetWayPoint = currentWayPoint.copy(railId = oldRail, arcLength = 2.0)
        val unchangedWayPoint = WayPoint("untouched", "Untouched", unchangedRail, 1.0)

        val plan = planOperationalInfrastructureRestore(
            currentDestinations = listOf(currentDestination, unchangedDestination),
            currentWayPoints = listOf(
                currentDestination.toWayPoint(),
                unchangedDestination.toWayPoint(),
                currentWayPoint,
                unchangedWayPoint
            ),
            targetDestinations = listOf(targetDestination, unchangedDestination),
            targetWayPoints = listOf(
                targetDestination.toWayPoint(),
                unchangedDestination.toWayPoint(),
                targetWayPoint,
                unchangedWayPoint
            )
        )

        assertEquals(setOf("changed"), plan.destinationIdsToRemove)
        assertEquals(setOf("standalone"), plan.wayPointIdsToRemove)
        assertEquals(listOf(targetDestination), plan.destinationsToRestore)
        assertEquals(listOf(targetWayPoint), plan.wayPointsToRestore)
    }
}
