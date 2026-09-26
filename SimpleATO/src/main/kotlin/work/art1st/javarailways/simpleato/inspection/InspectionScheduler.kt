package work.art1st.javarailways.simpleato.inspection

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.UUID
import kotlin.math.max

/** One occurrence encountered by the controller's existing shared forward perception. */
internal data class InspectionPerceptionOccurrence(
    val routeSegmentIndex: Int,
    val railId: UUID,
    val facing: ViewDirection
) {
    init {
        require(routeSegmentIndex >= 0) { "routeSegmentIndex must be non-negative" }
    }
}

/** Validated immutable declaration index for one exact train and frozen route binding. */
internal class CompiledInspectionBinding private constructor(
    val binding: InspectionDeclarationBinding,
    val maximumDeclaredInspectionDistanceMeters: Double,
    private val routeSegments: List<RouteSegmentIdentity>,
    private val declarationsByOccurrence: Map<RailDirectionKey, Map<Int, List<InspectionDeclaration>>>,
    private val declarationIds: Set<String>
) {
    fun declarationsAt(occurrence: InspectionPerceptionOccurrence): List<InspectionDeclaration> {
        val expected = routeSegments.getOrNull(occurrence.routeSegmentIndex)
            ?: throw IllegalArgumentException(
                "route segment ${occurrence.routeSegmentIndex} is outside the frozen route"
            )
        require(expected.railId == occurrence.railId && expected.facing == occurrence.facing) {
            "perception occurrence ${occurrence.routeSegmentIndex} does not match the frozen route"
        }
        return declarationsByOccurrence[RailDirectionKey(occurrence.railId, occurrence.facing)]
            ?.get(occurrence.routeSegmentIndex)
            .orEmpty()
    }

    companion object {
        fun compile(
            binding: InspectionDeclarationBinding,
            route: RouteSnapshot
        ): CompiledInspectionBinding {
            require(binding.route == FrozenRouteBinding.from(route)) {
                "inspection declarations must bind the supplied frozen route"
            }
            val routeSegments = route.segments.map { RouteSegmentIdentity(it.railId, it.facing) }
            val occurrences = ArrayList<RouteOccurrence>(route.segments.size)
            var routeDistance = 0.0
            route.segments.forEachIndexed { index, segment ->
                val start = routeDistance
                routeDistance += segment.lengthMeters
                occurrences += RouteOccurrence(index, segment.railId, segment.facing, start, routeDistance)
            }
            val cumulativeStarts = occurrences.map(RouteOccurrence::startRouteDistanceMeters).toDoubleArray()

            val mutableIndex = linkedMapOf<RailDirectionKey, MutableMap<Int, MutableList<InspectionDeclaration>>>()
            binding.declarations.forEach { declaration ->
                val matchingOccurrences = matchingOccurrences(declaration, occurrences, cumulativeStarts)
                require(matchingOccurrences.size == 1) {
                    "declaration ${declaration.declarationId} must identify exactly one route occurrence"
                }
                val segmentIndex = matchingOccurrences.single().segmentIndex
                mutableIndex
                    .getOrPut(RailDirectionKey(declaration.railId, declaration.facing), ::linkedMapOf)
                    .getOrPut(segmentIndex, ::arrayListOf)
                    .add(declaration)
            }

            val compiledIndex = mutableIndex.mapValues { (_, bySegment) ->
                bySegment.mapValues { (_, declarations) ->
                    declarations.sortedWith(
                        compareBy(InspectionDeclaration::triggerRouteDistanceMeters)
                            .thenBy(InspectionDeclaration::declarationId)
                    )
                }.toMap()
            }.toMap()
            return CompiledInspectionBinding(
                binding,
                binding.declarations.maxOfOrNull(InspectionDeclaration::inspectionDistanceMeters) ?: 0.0,
                routeSegments,
                compiledIndex,
                binding.declarations.mapTo(hashSetOf(), InspectionDeclaration::declarationId)
            )
        }

        private fun matchingOccurrences(
            declaration: InspectionDeclaration,
            occurrences: List<RouteOccurrence>,
            cumulativeStarts: DoubleArray
        ): List<RouteOccurrence> {
            if (occurrences.isEmpty()) return emptyList()
            val target = declaration.triggerRouteDistanceMeters
            var low = 0
            var high = cumulativeStarts.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (cumulativeStarts[middle] <= target + ROUTE_DISTANCE_EPSILON_METERS) {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            val insertionPoint = low
            val candidates = arrayListOf<RouteOccurrence>()
            var index = insertionPoint - 1
            while (index >= 0 &&
                occurrences[index].endRouteDistanceMeters >= target - ROUTE_DISTANCE_EPSILON_METERS
            ) {
                candidates += occurrences[index]
                index--
            }
            index = insertionPoint
            while (index < occurrences.size &&
                occurrences[index].startRouteDistanceMeters <= target + ROUTE_DISTANCE_EPSILON_METERS
            ) {
                candidates += occurrences[index]
                index++
            }
            return candidates.filter { occurrence ->
                occurrence.railId == declaration.railId && occurrence.facing == declaration.facing
            }
        }
    }

    fun containsDeclaration(declarationId: String): Boolean = declarationId in declarationIds
}

/**
 * Stateful activation filter for one compiled declaration binding.
 *
 * It never calls plugin code. The owner invokes [updatesFor] with occurrences from the already
 * completed shared perception and then dispatches only the returned declarations.
 */
internal class InspectionScheduler(initialBinding: CompiledInspectionBinding) {
    private var binding = initialBinding
    private val activeDeclarationIds = hashSetOf<String>()
    private val dirtyDeclarationIds = hashSetOf<String>()

    val maximumDeclaredInspectionDistanceMeters: Double
        get() = binding.maximumDeclaredInspectionDistanceMeters

    fun replaceBinding(replacement: CompiledInspectionBinding) {
        binding = replacement
        activeDeclarationIds.clear()
        dirtyDeclarationIds.clear()
    }

    fun markDirty(declarationIds: Collection<String>) {
        require(declarationIds.all(binding::containsDeclaration)) {
            "dirty declaration IDs must belong to the current binding"
        }
        dirtyDeclarationIds += declarationIds
    }

    fun updatesFor(
        occurrences: List<InspectionPerceptionOccurrence>,
        currentHeadRouteDistanceMeters: Double,
        dynamicLookaheadMeters: Double
    ): List<InspectionDeclaration> {
        require(currentHeadRouteDistanceMeters.isFinite() &&
            currentHeadRouteDistanceMeters >= 0.0 &&
            currentHeadRouteDistanceMeters <= binding.binding.route.totalLengthMeters
        ) { "currentHeadRouteDistanceMeters must be within the frozen route" }
        require(dynamicLookaheadMeters.isFinite() && dynamicLookaheadMeters >= 0.0) {
            "dynamicLookaheadMeters must be finite and non-negative"
        }
        require(occurrences.zipWithNext().all { (previous, next) ->
            previous.routeSegmentIndex < next.routeSegmentIndex
        }) {
            "shared perception occurrences must follow frozen-route order"
        }

        val visible = linkedMapOf<String, InspectionDeclaration>()
        occurrences.forEach { occurrence ->
            binding.declarationsAt(occurrence).forEach { declaration ->
                val distance = declaration.triggerRouteDistanceMeters - currentHeadRouteDistanceMeters
                val threshold = max(declaration.inspectionDistanceMeters, dynamicLookaheadMeters)
                if (distance >= 0.0 && distance <= threshold) {
                    visible[declaration.declarationId] = declaration
                }
            }
        }

        val updates = visible.values.filter { declaration ->
            declaration.declarationId !in activeDeclarationIds ||
                declaration.declarationId in dirtyDeclarationIds
        }
        activeDeclarationIds.retainAll(visible.keys)
        activeDeclarationIds += visible.keys
        dirtyDeclarationIds.removeAll(updates.mapTo(hashSetOf()) { it.declarationId })
        return updates
    }
}

private data class RailDirectionKey(val railId: UUID, val facing: ViewDirection)

private data class RouteSegmentIdentity(val railId: UUID, val facing: ViewDirection)

private data class RouteOccurrence(
    val segmentIndex: Int,
    val railId: UUID,
    val facing: ViewDirection,
    val startRouteDistanceMeters: Double,
    val endRouteDistanceMeters: Double
)

private const val ROUTE_DISTANCE_EPSILON_METERS = 1.0e-9
