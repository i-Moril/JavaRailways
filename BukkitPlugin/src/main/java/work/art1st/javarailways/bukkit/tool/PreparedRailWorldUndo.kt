package work.art1st.javarailways.bukkit.tool

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.bukkit.extension.rail.RailTemplateSnapshot
import work.art1st.javarailways.bukkit.logic.RailVisualBootstrap
import work.art1st.javarailways.bukkit.persistence.PersistedRailRenderMeta
import work.art1st.javarailways.bukkit.persistence.RailRenderMetaStore
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import java.util.UUID

/** Complete before/after undo values prepared away from the Bukkit owner commit. */
internal class PreparedRailWorldUndo(
    val before: RailWorldUndoSnapshot,
    val expectedAfter: RailWorldUndoSnapshot,
    val metadataRemovals: Set<UUID>,
    val metadataAdditions: Map<UUID, RailInfrastructureMetadata>,
    val renderAdditionsByRail: Map<UUID, PersistedRailRenderMeta>,
    private val preparedRenderSave: RailRenderMetaStore.PreparedSave? = null
) : AutoCloseable {
    fun publishRenderSidecar() {
        preparedRenderSave?.commitPrimary()
    }

    fun rollbackRenderSidecar() {
        preparedRenderSave?.rollbackPrimary()
    }

    override fun close() {
        preparedRenderSave?.close()
    }
}

/** Active branch is transient runtime state and is not part of a structural RailWand undo value. */
internal fun normalizeRailWandUndoRails(
    rails: List<CoreRail.SavedRailV1>
): List<CoreRail.SavedRailV1> = rails.map { saved ->
    saved.copy(activeConnectionAtA = null, activeConnectionAtB = null)
}

/**
 * Off-thread builder for one structural RailWand undo record.
 *
 * The Core rail lists come from the exact prepared topology roots. Other immutable values are
 * copied once while the worker prepares the edit. Mutators below update only the edit delta; the
 * final complete expected snapshot is materialized before returning to the owner thread.
 */
@OptIn(Heavy::class)
internal class PreparedRailWorldUndoBuilder private constructor(
    before: RailWorldUndoSnapshot,
    targetRails: List<CoreRail.SavedRailV1>,
    private val prepareRenderSave: ((List<PersistedRailRenderMeta>) -> RailRenderMetaStore.PreparedSave)?
) {
    private val before = before.copy(rails = normalizeRailWandUndoRails(before.rails))
    private val currentCurves = this.before.rails.associate { UUID.fromString(it.id) to it.curveAB.load(Unit) }
    private val targetRails = normalizeRailWandUndoRails(targetRails)
    private val targetCurves = this.targetRails.associate { UUID.fromString(it.id) to it.curveAB.load(Unit) }
    private val renderByRail = before.renderMetas.associateByTo(linkedMapOf(), PersistedRailRenderMeta::railId)
    private val metadata = before.railMetadata.toMutableMap()
    private val directions = before.operatingDirections.toMutableMap()
    private val destinations = before.destinations.associateByTo(linkedMapOf(), OperationalDestination::id)
    private val wayPoints = before.wayPoints.associateByTo(linkedMapOf(), WayPoint::id)
    private val templatesToPrepare = linkedMapOf<UUID, RailTemplateSnapshot>()

    fun putMetadata(railId: UUID, value: RailInfrastructureMetadata) {
        metadata[railId] = value
    }

    fun metadataFor(railId: UUID): RailInfrastructureMetadata =
        metadata[railId] ?: RailInfrastructureMetadata.EMPTY

    fun metadataSnapshot(): Map<UUID, RailInfrastructureMetadata> = metadata.toMap()

    fun replaceMetadataSnapshot(replacement: Map<UUID, RailInfrastructureMetadata>) {
        metadata.clear()
        metadata.putAll(replacement)
    }

    fun inheritRender(targetSourceRailIds: Map<UUID, UUID>) {
        targetSourceRailIds.forEach { (targetId, sourceId) ->
            renderByRail[sourceId]?.templateSnapshot?.let { templatesToPrepare[targetId] = it }
        }
    }

    fun renderTemplateFor(railId: UUID): RailTemplateSnapshot? = renderByRail[railId]?.templateSnapshot

    fun removeRails(railIds: Collection<UUID>) {
        railIds.forEach { railId ->
            metadata.remove(railId)
            directions.remove(railId)
            renderByRail.remove(railId)
            templatesToPrepare.remove(railId)
        }
    }

    fun prepareRender(railId: UUID, template: RailTemplateSnapshot) {
        require(railId in targetCurves) { "render target rail is absent: $railId" }
        templatesToPrepare[railId] = template
    }

    fun migrateDirections(targetSourceRailIds: Map<UUID, UUID>, removedRailIds: Collection<UUID>) {
        val replacements = targetSourceRailIds.mapNotNull { (targetId, sourceId) ->
            directions[sourceId]?.let { targetId to it }
        }.toMap()
        removedRailIds.forEach(directions::remove)
        directions.putAll(replacements)
    }

    /** Matches the production replacement migration by nearest point and tangent orientation. */
    fun migrateMappedInfrastructure(oldToNew: Map<UUID, UUID>) {
        if (oldToNew.isEmpty()) return
        destinations.replaceAll { _, value ->
            val replacementId = oldToNew[value.railId] ?: return@replaceAll value
            val oldCurve = requireNotNull(currentCurves[value.railId])
            val replacement = requireNotNull(targetCurves[replacementId])
            val oldPoint = oldCurve.getPointAt(value.arcLengthInAbMeters)
            val replacementPoint = replacement.getNearestPoint(oldPoint.position)
            val direction = value.approachDirection?.let { oldDirection ->
                if (oldPoint.tangent.dot(replacementPoint.tangent) >= 0.0) oldDirection else oldDirection.reversed()
            }
            value.copy(
                railId = replacementId,
                arcLengthInAbMeters = replacementPoint.arcLength,
                approachDirection = direction
            )
        }
        val destinationIds = destinations.keys
        wayPoints.replaceAll { id, value ->
            if (id in destinationIds) {
                destinations.getValue(id).toWayPoint()
            } else {
                val replacementId = oldToNew[value.railId] ?: return@replaceAll value
                val oldCurve = requireNotNull(currentCurves[value.railId])
                val replacement = requireNotNull(targetCurves[replacementId])
                val replacementPoint = replacement.getNearestPoint(oldCurve.getPointAt(value.arcLength).position)
                value.copy(railId = replacementId, arcLength = replacementPoint.arcLength)
            }
        }
    }

    /** Migrates the side-state shared by one-to-one structural rail replacements. */
    fun migrateMappedRailReplacement(oldToNew: Map<UUID, UUID>) {
        val newToOld = oldToNew.entries.associate { (oldId, newId) -> newId to oldId }
        inheritRender(newToOld)
        migrateDirections(newToOld, oldToNew.keys)
        migrateMappedInfrastructure(oldToNew)
    }

    fun migrateSplit(
        oldRailId: UUID,
        beforeRailId: UUID,
        afterRailId: UUID,
        splitArcLength: Double
    ) {
        destinations.replaceAll { _, value ->
            if (value.railId != oldRailId) value else if (value.arcLengthInAbMeters <= splitArcLength) {
                value.copy(railId = beforeRailId)
            } else {
                value.copy(railId = afterRailId, arcLengthInAbMeters = value.arcLengthInAbMeters - splitArcLength)
            }
        }
        wayPoints.replaceAll { id, value ->
            if (id in destinations) destinations.getValue(id).toWayPoint()
            else if (value.railId != oldRailId) value
            else if (value.arcLength <= splitArcLength) value.copy(railId = beforeRailId)
            else value.copy(railId = afterRailId, arcLength = value.arcLength - splitArcLength)
        }
    }

    fun migrateMerge(beforeRailId: UUID, afterRailId: UUID, mergedRailId: UUID, beforeLength: Double) {
        destinations.replaceAll { _, value ->
            when (value.railId) {
                beforeRailId -> value.copy(railId = mergedRailId)
                afterRailId -> value.copy(
                    railId = mergedRailId,
                    arcLengthInAbMeters = beforeLength + value.arcLengthInAbMeters
                )
                else -> value
            }
        }
        wayPoints.replaceAll { id, value ->
            if (id in destinations) destinations.getValue(id).toWayPoint() else when (value.railId) {
                beforeRailId -> value.copy(railId = mergedRailId)
                afterRailId -> value.copy(railId = mergedRailId, arcLength = beforeLength + value.arcLength)
                else -> value
            }
        }
    }

    fun removeInfrastructureOnRails(railIds: Set<UUID>) {
        val destinationIds = destinations.values.filter { it.railId in railIds }.mapTo(hashSetOf(), OperationalDestination::id)
        destinationIds.forEach(destinations::remove)
        wayPoints.entries.removeIf { (id, value) -> id in destinationIds || value.railId in railIds }
    }

    fun build(): PreparedRailWorldUndo {
        val indexedAssemblyLengths = linkedMapOf<Pair<String, Int>, MutableList<Pair<Int, Double>>>()
        metadata.forEach { (railId, railMetadata) ->
            railMetadata.authoring?.assembly?.let { assembly ->
                val curve = targetCurves[railId] ?: return@let
                indexedAssemblyLengths.computeIfAbsent(assembly.groupId to assembly.laneIndex) { mutableListOf() }
                    .add(assembly.segmentIndex to curve.totalArcLength)
            }
        }
        val assemblyLengthsByLane = indexedAssemblyLengths.mapValues { (_, indexedLengths) ->
            indexedLengths.sortedBy(Pair<Int, Double>::first).map(Pair<Int, Double>::second)
        }
        templatesToPrepare.forEach { (railId, template) ->
            val curve = requireNotNull(targetCurves[railId])
            val assembly = metadata[railId]?.authoring?.assembly
            val assemblyLengths = assembly?.let {
                assemblyLengthsByLane[it.groupId to it.laneIndex]
            }.orEmpty()
            renderByRail[railId] = RailVisualBootstrap.preparePersistedMeta(
                railId,
                curve.totalArcLength,
                template,
                assembly?.segmentIndex,
                assemblyLengths
            )
        }
        val after = RailWorldUndoSnapshot(
            rails = targetRails,
            renderMetas = renderByRail.values.sortedBy { it.railId.toString() },
            railMetadata = metadata.toSortedMap(compareBy(UUID::toString)),
            operatingDirections = directions.toSortedMap(compareBy(UUID::toString)),
            destinations = destinations.values.sortedBy(OperationalDestination::id),
            wayPoints = wayPoints.values.sortedBy(WayPoint::id)
        )
        val metadataIds = before.railMetadata.keys + after.railMetadata.keys
        val changedMetadata = metadataIds.filterTo(linkedSetOf()) {
            before.railMetadata[it] != after.railMetadata[it]
        }
        val beforeRender = before.renderMetas.associateBy(PersistedRailRenderMeta::railId)
        val afterRender = after.renderMetas.associateBy(PersistedRailRenderMeta::railId)
        val changedRender = (beforeRender.keys + afterRender.keys).filterTo(linkedSetOf()) {
            beforeRender[it] != afterRender[it]
        }
        return PreparedRailWorldUndo(
            before = before,
            expectedAfter = after,
            metadataRemovals = changedMetadata,
            metadataAdditions = after.railMetadata.filterKeys(changedMetadata::contains),
            renderAdditionsByRail = changedRender.mapNotNull { railId ->
                afterRender[railId]?.let { railId to it }
            }.toMap(),
            preparedRenderSave = if (changedRender.isEmpty()) null else {
                prepareRenderSave?.invoke(after.renderMetas)
            }
        )
    }

    companion object {
        fun fromSnapshots(
            before: RailWorldUndoSnapshot,
            targetRails: List<CoreRail.SavedRailV1>
        ): PreparedRailWorldUndoBuilder = PreparedRailWorldUndoBuilder(before, targetRails, null)

        fun capture(
            worldId: UUID,
            worldName: String,
            coreWorld: CoreWorld,
            editPlan: RailWandTopologyEditPlan
        ): PreparedRailWorldUndoBuilder {
            check(editPlan.isCurrent(coreWorld)) { "RailWand undo preparation is stale" }
            val before = RailWorldUndoSnapshot(
                rails = editPlan.baseRailSnapshot,
                renderMetas = RailRenderMetaStore.load(worldId, worldName).sortedBy { it.railId.toString() },
                railMetadata = coreWorld.basicWorldData.railMetadata.snapshot(),
                operatingDirections = coreWorld.basicWorldData.operatingDirections.entries()
                    .toSortedMap(compareBy(UUID::toString)),
                destinations = coreWorld.simpleAtoWorldData.destinations.all(),
                wayPoints = coreWorld.basicWorldData.wayPoints.all()
            )
            check(editPlan.isCurrent(coreWorld)) { "RailWand undo preparation became stale" }
            return PreparedRailWorldUndoBuilder(before, editPlan.targetRailSnapshot) { records ->
                RailRenderMetaStore.prepareSave(worldId, worldName, before.renderMetas, records)
            }
        }
    }
}

private fun ViewDirection.reversed(): ViewDirection = when (this) {
    ViewDirection.AB -> ViewDirection.BA
    ViewDirection.BA -> ViewDirection.AB
}

/** Reverses only this action's delta, retaining unrelated edits made since the action. */
internal fun rebaseRailWorldUndo(
    current: RailWorldUndoSnapshot,
    expected: RailWorldUndoSnapshot,
    target: RailWorldUndoSnapshot
): RailWorldUndoSnapshot? {
    fun <K, V> merge(now: Map<K, V>, after: Map<K, V>, before: Map<K, V>): Map<K, V>? {
        val changed = (after.keys + before.keys).filter { after[it] != before[it] }
        if (changed.any { now[it] != after[it] }) return null
        return now.toMutableMap().apply {
            changed.forEach { key ->
                if (key in before) put(key, before.getValue(key)) else remove(key)
            }
        }
    }
    val rails = merge(current.rails.associateBy { it.id }, expected.rails.associateBy { it.id },
        target.rails.associateBy { it.id }) ?: return null
    val render = merge(current.renderMetas.associateBy { it.railId }, expected.renderMetas.associateBy { it.railId },
        target.renderMetas.associateBy { it.railId }) ?: return null
    val metadata = merge(current.railMetadata, expected.railMetadata, target.railMetadata) ?: return null
    val directions = merge(current.operatingDirections, expected.operatingDirections, target.operatingDirections) ?: return null
    val destinations = merge(current.destinations.associateBy { it.id }, expected.destinations.associateBy { it.id },
        target.destinations.associateBy { it.id }) ?: return null
    val wayPoints = merge(current.wayPoints.associateBy { it.id }, expected.wayPoints.associateBy { it.id },
        target.wayPoints.associateBy { it.id }) ?: return null
    return RailWorldUndoSnapshot(rails.values.sortedBy { it.id }, render.values.sortedBy { it.railId.toString() },
        metadata, directions, destinations.values.sortedBy { it.id }, wayPoints.values.sortedBy { it.id })
}
