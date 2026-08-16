package work.art1st.javarailways.basic.rail

import java.util.UUID

/** Keeps Basic-owned authoring lineage synchronized with Core rail topology replacements. */
object RailInfrastructureMetadataTopology {
    fun split(
        store: RailInfrastructureMetadataStore,
        sourceRailId: UUID,
        beforeRailId: UUID,
        afterRailId: UUID,
        operationId: String
    ) {
        require(operationId.isNotBlank())
        require(setOf(sourceRailId, beforeRailId, afterRailId).size == 3)
        val source = store.getOrDefault(sourceRailId)
        val parent = source.createdBySplit
        store.replace(
            removals = setOf(sourceRailId),
            additions = mapOf(
                beforeRailId to source.withSplit(RailSplitMetadata(operationId, 0, parent)),
                afterRailId to source.withSplit(RailSplitMetadata(operationId, 1, parent))
            )
        )
    }

    fun mergeEligibilityError(
        store: RailInfrastructureMetadataStore,
        beforeRailId: UUID,
        afterRailId: UUID
    ): String? {
        val before = store[beforeRailId] ?: return "前段缺少基础设施元数据"
        val after = store[afterRailId] ?: return "后段缺少基础设施元数据"
        val beforeSplit = before.createdBySplit ?: return "前段不是由分割创建"
        val afterSplit = after.createdBySplit ?: return "后段不是由分割创建"
        if (beforeSplit.operationId != afterSplit.operationId ||
            beforeSplit.segmentIndex != 0 || afterSplit.segmentIndex != 1
        ) return "两段轨道不是同一次分割产生的相邻段"
        if (beforeSplit.parent != afterSplit.parent) return "分割谱系不一致"
        if (before.withSplit(beforeSplit.parent) != after.withSplit(afterSplit.parent)) {
            return "两段轨道元数据不一致"
        }
        return null
    }

    fun merge(
        store: RailInfrastructureMetadataStore,
        beforeRailId: UUID,
        afterRailId: UUID,
        mergedRailId: UUID
    ) {
        val error = mergeEligibilityError(store, beforeRailId, afterRailId)
        require(error == null) { error ?: "Rails cannot be merged" }
        val before = store.getValue(beforeRailId)
        val parent = requireNotNull(before.createdBySplit).parent
        store.replace(
            removals = setOf(beforeRailId, afterRailId),
            additions = mapOf(mergedRailId to before.withSplit(parent))
        )
    }

    private fun RailInfrastructureMetadata.withSplit(lineage: RailSplitMetadata?): RailInfrastructureMetadata {
        val updatedAuthoring = (authoring ?: RailAuthoringMetadata()).copy(createdBySplit = lineage)
        return copy(authoring = updatedAuthoring)
    }
}
