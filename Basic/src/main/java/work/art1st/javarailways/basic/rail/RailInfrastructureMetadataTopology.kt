package work.art1st.javarailways.basic.rail

import java.util.UUID

/** Keeps Basic-owned authoring lineage synchronized with Core rail topology replacements. */
object RailInfrastructureMetadataTopology {
    private data class AssemblyPart(
        val railId: UUID,
        val metadata: RailInfrastructureMetadata,
        val sourceRole: RailAssemblyRole
    )

    fun split(
        store: RailInfrastructureMetadataStore,
        sourceRailId: UUID,
        beforeRailId: UUID,
        afterRailId: UUID,
        operationId: String
    ) {
        require(operationId.isNotBlank())
        require(setOf(sourceRailId, beforeRailId, afterRailId).size == 3)
        val snapshot = store.snapshot()
        val source = snapshot[sourceRailId] ?: RailInfrastructureMetadata.EMPTY
        val parent = source.authoring?.createdBySplit
        val sourceRole = source.authoring?.assembly?.role
        val before = source.copy(
            authoring = source.authoring?.copy(gradeLockedAtB = false)
        ).withSplit(RailSplitMetadata(operationId, 0, parent, sourceRole))
        val after = source.copy(
            authoring = source.authoring?.copy(gradeLockedAtA = false)
        ).withSplit(RailSplitMetadata(operationId, 1, parent, sourceRole))
        val additions = linkedMapOf<UUID, RailInfrastructureMetadata>()

        val sourceAssembly = source.authoring?.assembly
        if (sourceAssembly == null) {
            dissolveParallelGroups(
                snapshot,
                setOfNotNull(source.authoring?.geometry?.parallel?.groupId),
                additions
            )
            additions[beforeRailId] = before.withoutAssemblyOrParallel()
            additions[afterRailId] = after.withoutAssemblyOrParallel()
        } else {
            // A split affects only the selected physical lane. Detach the authoring relationship
            // instead of creating matching topology nodes on companion lanes. Each existing lane
            // remains a complete lane-local assembly; the selected lane becomes left/right groups.
            val assemblyMembers = snapshot.entries
                .filter { it.value.authoring?.assembly?.groupId == sourceAssembly.groupId }
            require(assemblyMembers.size == sourceAssembly.laneCount * sourceAssembly.segmentCount) {
                "Incomplete rail assembly ${sourceAssembly.groupId}"
            }
            val parallelGroupIds = assemblyMembers.mapNotNullTo(linkedSetOf()) {
                it.value.authoring?.geometry?.parallel?.groupId
            }
            dissolveParallelGroups(snapshot, parallelGroupIds, additions)

            val lanes = assemblyMembers.groupBy { requireNotNull(it.value.authoring?.assembly).laneIndex }
            require(lanes.keys == (0 until sourceAssembly.laneCount).toSet()) {
                "Incomplete rail assembly lanes ${sourceAssembly.groupId}"
            }
            lanes.toSortedMap().forEach { (laneIndex, entries) ->
                val ordered = entries.sortedBy { requireNotNull(it.value.authoring?.assembly).segmentIndex }
                require(ordered.size == sourceAssembly.segmentCount)
                if (laneIndex != sourceAssembly.laneIndex) {
                    assignAssembly(
                        ordered.map { (id, metadata) ->
                            AssemblyPart(id, metadata.withoutParallel(), requireNotNull(metadata.authoring?.assembly).role)
                        },
                        sourceAssembly.kind,
                        additions
                    )
                    return@forEach
                }

                val expanded = ordered.flatMap { (id, metadata) ->
                    if (id != sourceRailId) {
                        listOf(AssemblyPart(id, metadata.withoutParallel(), requireNotNull(metadata.authoring?.assembly).role))
                    } else {
                        listOf(
                            AssemblyPart(beforeRailId, before.withoutParallel(), sourceRole ?: RailAssemblyRole.CURVE),
                            AssemblyPart(afterRailId, after.withoutParallel(), sourceRole ?: RailAssemblyRole.CURVE)
                        )
                    }
                }
                val beforeIndex = expanded.indexOfFirst { it.railId == beforeRailId }
                val afterIndex = expanded.indexOfFirst { it.railId == afterRailId }
                require(beforeIndex >= 0 && afterIndex == beforeIndex + 1)
                assignAssembly(expanded.subList(0, beforeIndex + 1), sourceAssembly.kind, additions)
                assignAssembly(expanded.subList(afterIndex, expanded.size), sourceAssembly.kind, additions)
            }
        }

        store.replace(
            removals = setOf(sourceRailId),
            additions = additions.filterKeys { it != sourceRailId }
        )
    }

    fun mergeEligibilityError(
        store: RailInfrastructureMetadataStore,
        beforeRailId: UUID,
        afterRailId: UUID
    ): String? {
        val before = store[beforeRailId] ?: return "前段缺少基础设施元数据"
        val after = store[afterRailId] ?: return "后段缺少基础设施元数据"
        val beforeSplit = before.authoring?.createdBySplit ?: return "前段不是由分割创建"
        val afterSplit = after.authoring?.createdBySplit ?: return "后段不是由分割创建"
        if (beforeSplit.operationId != afterSplit.operationId ||
            beforeSplit.segmentIndex != 0 || afterSplit.segmentIndex != 1
        ) return "两段轨道不是同一次分割产生的相邻段"
        if (beforeSplit.parent != afterSplit.parent ||
            beforeSplit.sourceAssemblyRole != afterSplit.sourceAssemblyRole
        ) return "分割谱系不一致"
        if (before.withoutTopologyState(beforeSplit.parent).withoutDerivedSpeed() !=
            after.withoutTopologyState(afterSplit.parent).withoutDerivedSpeed()) {
            return "两段轨道元数据不一致"
        }
        return null
    }

    private fun RailInfrastructureMetadata.withoutDerivedSpeed(): RailInfrastructureMetadata = copy(
        speedLimits = if (authoring?.curveSpeedLimitAutomatic == true) {
            SpeedLimitMetadata(speedLimits.entries.filterNot { it.type == SpeedLimitType.CURVE })
        } else speedLimits,
        curveSafety = CurveSafetyMetadata()
    )

    fun merge(
        store: RailInfrastructureMetadataStore,
        beforeRailId: UUID,
        afterRailId: UUID,
        mergedRailId: UUID
    ) {
        val error = mergeEligibilityError(store, beforeRailId, afterRailId)
        require(error == null) { error ?: "Rails cannot be merged" }
        val snapshot = store.snapshot()
        val before = store.getValue(beforeRailId)
        val after = store.getValue(afterRailId)
        val beforeSplit = requireNotNull(before.authoring?.createdBySplit)
        val parent = beforeSplit.parent
        val merged = before.copy(
            authoring = before.authoring?.let { authoring ->
                authoring.copy(
                    assembly = null,
                    createdBySplit = parent,
                    gradeLockedAtA = authoring.gradeLockedAtA,
                    gradeLockedAtB = after.authoring?.gradeLockedAtB == true,
                    geometry = authoring.geometry.copy(parallel = null)
                )
            }
        )
        val left = assemblyParts(snapshot, beforeRailId, before)
        val right = assemblyParts(snapshot, afterRailId, after)
        require(left.last().railId == beforeRailId) { "前段不是左侧逻辑曲线的末段" }
        require(right.first().railId == afterRailId) { "后段不是右侧逻辑曲线的首段" }
        val combined = buildList {
            addAll(left.dropLast(1))
            add(AssemblyPart(mergedRailId, merged, beforeSplit.sourceAssemblyRole ?: RailAssemblyRole.CURVE))
            addAll(right.drop(1))
        }
        val kind = before.authoring?.assembly?.kind ?: after.authoring?.assembly?.kind ?: RailAssemblyKind.FIVE_SEGMENT_CURVE
        val additions = linkedMapOf<UUID, RailInfrastructureMetadata>()
        assignAssembly(combined, kind, additions)
        store.replace(
            removals = setOf(beforeRailId, afterRailId),
            additions = additions.filterKeys { it != beforeRailId && it != afterRailId }
        )
    }

    private fun assemblyParts(
        snapshot: Map<UUID, RailInfrastructureMetadata>,
        railId: UUID,
        metadata: RailInfrastructureMetadata
    ): List<AssemblyPart> {
        val assembly = metadata.authoring?.assembly ?: return listOf(AssemblyPart(
            railId,
            metadata,
            metadata.authoring?.createdBySplit?.sourceAssemblyRole ?: RailAssemblyRole.CURVE
        ))
        require(assembly.laneCount == 1 && assembly.laneIndex == 0) {
            "Split assembly must be detached from parallel authoring before merge"
        }
        val members = snapshot.entries
            .filter { it.value.authoring?.assembly?.groupId == assembly.groupId }
            .sortedBy { requireNotNull(it.value.authoring?.assembly).segmentIndex }
        require(members.size == assembly.segmentCount) { "Incomplete split assembly ${assembly.groupId}" }
        return members.map { (id, value) ->
            AssemblyPart(id, value, requireNotNull(value.authoring?.assembly).role)
        }
    }

    private fun assignAssembly(
        parts: List<AssemblyPart>,
        kind: RailAssemblyKind,
        additions: MutableMap<UUID, RailInfrastructureMetadata>
    ) {
        require(parts.isNotEmpty())
        val groupId = UUID.randomUUID().toString()
        parts.forEachIndexed { index, part ->
            val assembly = if (parts.size == 1) null else RailAssemblyMetadata(
                groupId = groupId,
                kind = kind,
                laneIndex = 0,
                laneCount = 1,
                segmentIndex = index,
                segmentCount = parts.size,
                role = normalizedRole(part.sourceRole, index, parts.lastIndex)
            )
            additions[part.railId] = part.metadata.withAssembly(assembly).withoutParallel()
        }
    }

    private fun normalizedRole(role: RailAssemblyRole, index: Int, lastIndex: Int): RailAssemblyRole = when {
        role == RailAssemblyRole.LEADING_TANGENT && index == 0 -> RailAssemblyRole.LEADING_TANGENT
        role == RailAssemblyRole.TRAILING_TANGENT && index == lastIndex -> RailAssemblyRole.TRAILING_TANGENT
        else -> RailAssemblyRole.CURVE
    }

    private fun dissolveParallelGroups(
        snapshot: Map<UUID, RailInfrastructureMetadata>,
        groupIds: Set<String>,
        additions: MutableMap<UUID, RailInfrastructureMetadata>
    ) {
        if (groupIds.isEmpty()) return
        snapshot.forEach { (railId, metadata) ->
            if (metadata.authoring?.geometry?.parallel?.groupId in groupIds) {
                additions[railId] = metadata.withoutParallel()
            }
        }
    }

    private fun RailInfrastructureMetadata.withSplit(lineage: RailSplitMetadata?): RailInfrastructureMetadata {
        val updatedAuthoring = (authoring ?: RailAuthoringMetadata()).copy(createdBySplit = lineage)
        return copy(authoring = updatedAuthoring)
    }

    private fun RailInfrastructureMetadata.withAssembly(
        assembly: RailAssemblyMetadata?
    ): RailInfrastructureMetadata = copy(
        authoring = (authoring ?: RailAuthoringMetadata()).copy(assembly = assembly)
    )

    private fun RailInfrastructureMetadata.withoutParallel(): RailInfrastructureMetadata = copy(
        authoring = authoring?.let { it.copy(geometry = it.geometry.copy(parallel = null)) }
    )

    private fun RailInfrastructureMetadata.withoutAssemblyOrParallel(): RailInfrastructureMetadata = copy(
        authoring = (authoring ?: RailAuthoringMetadata()).copy(
            assembly = null,
            geometry = (authoring?.geometry ?: RailGeometryMetadata()).copy(parallel = null)
        )
    )

    private fun RailInfrastructureMetadata.withoutTopologyState(
        lineage: RailSplitMetadata?
    ): RailInfrastructureMetadata = copy(
        authoring = (authoring ?: RailAuthoringMetadata()).copy(
            assembly = null,
            createdBySplit = lineage,
            gradeLockedAtA = false,
            gradeLockedAtB = false,
            geometry = (authoring?.geometry ?: RailGeometryMetadata()).copy(parallel = null)
        )
    )
}
