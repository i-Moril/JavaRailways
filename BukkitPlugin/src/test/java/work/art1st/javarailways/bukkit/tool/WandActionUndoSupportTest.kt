package work.art1st.javarailways.bukkit.tool

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.bukkit.extension.rail.RailDecorationLayout
import work.art1st.javarailways.bukkit.extension.rail.RailTemplateSnapshot
import work.art1st.javarailways.bukkit.logic.RailVisualBootstrap
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.SavedRailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
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
            operatingDirections = emptyMap(),
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
        val emptyWorld = RailWorldUndoSnapshot(
            emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), emptyList()
        )
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
        val currentDestination = OperationalDestination("changed", newRail, 4.0)
        val targetDestination = currentDestination.copy(railId = oldRail, arcLengthInAbMeters = 2.0)
        val unchangedDestination = OperationalDestination("unchanged", unchangedRail, 1.0)
        val currentWayPoint = WayPoint("standalone", newRail, 4.0)
        val targetWayPoint = currentWayPoint.copy(railId = oldRail, arcLength = 2.0)
        val unchangedWayPoint = WayPoint("untouched", unchangedRail, 1.0)

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

    @Test
    fun `prepared world undo materializes complete expected state before owner commit`() {
        val removedRail = UUID.randomUUID()
        val destination = OperationalDestination("removed", removedRail, 3.0)
        val standAloneWayPoint = WayPoint("standalone", removedRail, 4.0)
        val before = RailWorldUndoSnapshot(
            rails = emptyList(),
            renderMetas = emptyList(),
            railMetadata = mapOf(removedRail to RailInfrastructureMetadata.EMPTY),
            operatingDirections = mapOf(removedRail to ViewDirection.AB),
            destinations = listOf(destination),
            wayPoints = listOf(destination.toWayPoint(), standAloneWayPoint)
        )

        val prepared = PreparedRailWorldUndoBuilder.fromSnapshots(before, emptyList())
            .also {
                it.removeRails(setOf(removedRail))
                it.removeInfrastructureOnRails(setOf(removedRail))
            }
            .build()

        assertEquals(before, prepared.before)
        assertEquals(emptyMap<UUID, RailInfrastructureMetadata>(), prepared.expectedAfter.railMetadata)
        assertEquals(emptyMap<UUID, ViewDirection>(), prepared.expectedAfter.operatingDirections)
        assertEquals(emptyList<OperationalDestination>(), prepared.expectedAfter.destinations)
        assertEquals(emptyList<WayPoint>(), prepared.expectedAfter.wayPoints)
        assertEquals(setOf(removedRail), prepared.metadataRemovals)
    }

    @Test
    fun `prepared replacement keeps source metadata before removing the old rail`() {
        val oldRail = UUID.randomUUID()
        val replacementRail = UUID.randomUUID()
        val sourceMetadata = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(designSpeedMetersPerSecond = 30.0, gradeLockedAtA = true),
            speedLimits = SpeedLimitMetadata(listOf(
                SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE),
                SpeedLimitEntry(20.0, SpeedLimitType.CURVE)
            ))
        )
        val before = RailWorldUndoSnapshot(
            rails = emptyList(),
            renderMetas = emptyList(),
            railMetadata = mapOf(oldRail to sourceMetadata),
            operatingDirections = mapOf(oldRail to ViewDirection.AB),
            destinations = emptyList(),
            wayPoints = emptyList()
        )
        val builder = PreparedRailWorldUndoBuilder.fromSnapshots(before, emptyList())
        builder.migrateMappedRailReplacement(mapOf(oldRail to replacementRail))
        val replacementMetadata = builder.metadataFor(oldRail).copy(speedLimits = SpeedLimitMetadata(listOf(
            SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE),
            SpeedLimitEntry(15.0, SpeedLimitType.CURVE)
        )))

        builder.putMetadata(replacementRail, replacementMetadata)
        builder.removeRails(setOf(oldRail))
        val prepared = builder.build()

        assertEquals(sourceMetadata.authoring, replacementMetadata.authoring)
        assertEquals(sourceMetadata.curveSafety, replacementMetadata.curveSafety)
        assertEquals(30.0, prepared.expectedAfter.railMetadata.getValue(replacementRail).authoring?.designSpeedMetersPerSecond)
        assertEquals(15.0, prepared.expectedAfter.railMetadata.getValue(replacementRail).speedLimits.effectiveLimitMetersPerSecond())
        assertTrue(prepared.expectedAfter.railMetadata.getValue(replacementRail).authoring?.gradeLockedAtA == true)
        assertEquals(mapOf(replacementRail to ViewDirection.AB), prepared.expectedAfter.operatingDirections)
    }

    @Test
    fun `prepared render metadata preserves assembly-wide decoration spacing`() {
        val template = RailTemplateSnapshot("test", 3.0, emptyList())
        val first = RailVisualBootstrap.preparePersistedMeta(
            UUID.randomUUID(), 5.0, template, assemblyIndex = 0, assemblyLengths = listOf(5.0, 7.0)
        )
        val second = RailVisualBootstrap.preparePersistedMeta(
            UUID.randomUUID(), 7.0, template, assemblyIndex = 1, assemblyLengths = listOf(5.0, 7.0)
        )
        val assembledArcs = first.segments.flatMap { it.pointArcs } +
            second.segments.flatMap { segment -> segment.pointArcs.map { it + 5.0 } }

        assertEquals(RailDecorationLayout.endpointInclusiveArcs(12.0, 3.0), assembledArcs)
    }

    @Test
    fun `prepared render metadata preserves repeat lengths below one tenth metre`() {
        val template = RailTemplateSnapshot("dense", 0.05, emptyList())
        val standalone = RailVisualBootstrap.preparePersistedMeta(UUID.randomUUID(), 0.3, template)
        val standaloneArcs = standalone.segments.flatMap { it.pointArcs }
        assertEquals(RailDecorationLayout.endpointInclusiveArcs(0.3, 0.05), standaloneArcs)
        assertTrue(standaloneArcs.size > 4)

        val lengths = listOf(0.1, 0.2)
        val first = RailVisualBootstrap.preparePersistedMeta(UUID.randomUUID(), lengths[0], template, 0, lengths)
        val second = RailVisualBootstrap.preparePersistedMeta(UUID.randomUUID(), lengths[1], template, 1, lengths)
        val assembledArcs = first.segments.flatMap { it.pointArcs } +
            second.segments.flatMap { segment -> segment.pointArcs.map { it + lengths[0] } }
        val expected = RailDecorationLayout.endpointInclusiveArcs(lengths.sum(), 0.05)
        assertEquals(expected.size, assembledArcs.size)
        expected.zip(assembledArcs).forEach { (a, b) -> assertEquals(a, b, 1.0e-12) }
    }

    @Test
    fun `structural undo snapshots ignore transient active branch without reordering connections`() {
        val railId = UUID.randomUUID()
        val firstBranch = SavedRailEndpointRef(UUID.randomUUID().toString(), RailEndpoint.A)
        val secondBranch = SavedRailEndpointRef(UUID.randomUUID().toString(), RailEndpoint.B)
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0)
        )).save() as PrimeCurveImpl.SavedPrimeCurveV1
        fun saved(active: SavedRailEndpointRef?) = CoreRail.SavedRailV1(
            id = railId.toString(), curveAB = curve,
            connectionsAtA = emptyList(), connectionsAtB = listOf(secondBranch, firstBranch),
            joinedEndpointsAtA = emptyList(), joinedEndpointsAtB = listOf(firstBranch, secondBranch),
            activeConnectionAtA = null, activeConnectionAtB = active
        )
        fun snapshot(rail: CoreRail.SavedRailV1) = RailWorldUndoSnapshot(
            listOf(rail), emptyList(), emptyMap(), emptyMap(), emptyList(), emptyList()
        )

        val prepared = PreparedRailWorldUndoBuilder.fromSnapshots(
            snapshot(saved(firstBranch)),
            listOf(saved(secondBranch))
        ).build()

        assertEquals(null, prepared.before.rails.single().activeConnectionAtB)
        assertEquals(null, prepared.expectedAfter.rails.single().activeConnectionAtB)
        assertEquals(listOf(secondBranch, firstBranch), prepared.before.rails.single().connectionsAtB)
        assertEquals(listOf(firstBranch, secondBranch), prepared.before.rails.single().joinedEndpointsAtB)
        assertEquals(prepared.before.rails, prepared.expectedAfter.rails)
    }
    @Test
    fun `undo rebases only changed entries and rejects conflicts on those entries`() {
        val changed = UUID.randomUUID()
        val unrelated = UUID.randomUUID()
        fun snapshot(directions: Map<UUID, ViewDirection>) = RailWorldUndoSnapshot(
            emptyList(), emptyList(), emptyMap(), directions, emptyList(), emptyList())
        val before = snapshot(mapOf(changed to ViewDirection.AB))
        val after = snapshot(mapOf(changed to ViewDirection.BA))
        val current = snapshot(mapOf(changed to ViewDirection.BA, unrelated to ViewDirection.AB))
        val restored = requireNotNull(rebaseRailWorldUndo(current, after, before))
        assertEquals(mapOf(changed to ViewDirection.AB, unrelated to ViewDirection.AB), restored.operatingDirections)
        assertEquals(null, rebaseRailWorldUndo(snapshot(mapOf(unrelated to ViewDirection.AB)), after, before))
    }

}
