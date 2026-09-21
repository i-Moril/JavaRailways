package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureResource
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource

internal val TAIL_RELEASE_ENTRY_ORDER: Comparator<ResolvedTailReleaseEntry> =
    Comparator { first, second ->
        val boundaryOrder = first.tailClearRouteMeters.compareTo(second.tailClearRouteMeters)
        if (boundaryOrder != 0) boundaryOrder else compareResources(first.resource, second.resource)
    }

private fun compareResources(
    first: InfrastructureResource,
    second: InfrastructureResource
): Int = when {
    first is RailIntervalResource && second is RailIntervalResource -> {
        first.railId.compareTo(second.railId).takeIf { it != 0 }
            ?: first.startAbMeters.compareTo(second.startAbMeters).takeIf { it != 0 }
            ?: first.endAbMeters.compareTo(second.endAbMeters)
    }
    first is RailIntervalResource -> -1
    second is RailIntervalResource -> 1
    first is StableInfrastructureResource && second is StableInfrastructureResource -> {
        first.typeId.compareTo(second.typeId).takeIf { it != 0 }
            ?: first.resourceId.compareTo(second.resourceId)
    }
    else -> error("unknown infrastructure resource kind")
}

/**
 * One claimed resource in route-tail release order.
 *
 * [tailClearRouteMeters] is the fully compiled route coordinate that the train tail must reach
 * before [resource] is eligible for release. It is lifecycle metadata for the admission's single
 * footprint, not another footprint or an operational safety-margin policy. The transitional
 * compiler currently uses the final claimed/conflict extent on the frozen route; a future explicit
 * clearance-margin source must be folded into this value before the entry is created.
 */
internal data class ResolvedTailReleaseEntry(
    val resource: InfrastructureResource,
    val tailClearRouteMeters: Double
) {
    init {
        require(tailClearRouteMeters.isFinite() && tailClearRouteMeters >= 0.0) {
            "tail-clear route boundary must be finite and non-negative"
        }
    }
}

internal sealed interface TailReleaseInspectionResult {
    /** Eligible resources in queue order. The queue itself remains unchanged. */
    data class Eligible(val resources: List<InfrastructureResource>) :
        TailReleaseInspectionResult

    /** Invalid tail progress fails this inspection closed; no resource is eligible. */
    data class Rejected(val detail: String) : TailReleaseInspectionResult {
        init {
            require(detail.isNotBlank())
        }
    }
}

/**
 * Immutable route-ordered release queue for one compiled claim.
 *
 * Inspection reads only the queue head and its consecutive eligible successors. It never removes
 * entries: the future grant owner must atomically shrink the registry claim first, then replace its
 * retained queue with the remaining suffix. Tail-progress monotonicity belongs to that stateful
 * owner rather than this pure helper.
 */
internal class CompiledTailReleaseQueue(entries: Collection<ResolvedTailReleaseEntry>) {
    val entries: List<ResolvedTailReleaseEntry> = java.util.List.copyOf(entries)

    init {
        require(this.entries.isNotEmpty()) { "tail release queue requires at least one entry" }
        require(this.entries.map(ResolvedTailReleaseEntry::resource).distinct().size ==
            this.entries.size
        ) {
            "tail release queue contains duplicate resources"
        }
        require(this.entries.zipWithNext().all { (first, second) ->
            TAIL_RELEASE_ENTRY_ORDER.compare(first, second) <= 0
        }) {
            "tail release queue must use deterministic compiled tail-clear order"
        }
    }

    fun inspectEligiblePrefix(tailRouteProgressMeters: Double): TailReleaseInspectionResult {
        if (!tailRouteProgressMeters.isFinite() || tailRouteProgressMeters < 0.0) {
            return TailReleaseInspectionResult.Rejected(
                "tail route progress must be finite and non-negative: $tailRouteProgressMeters"
            )
        }

        val eligible = ArrayList<InfrastructureResource>()
        for (entry in entries) {
            if (tailRouteProgressMeters < entry.tailClearRouteMeters) break
            eligible += entry.resource
        }
        return TailReleaseInspectionResult.Eligible(java.util.List.copyOf(eligible))
    }
}
