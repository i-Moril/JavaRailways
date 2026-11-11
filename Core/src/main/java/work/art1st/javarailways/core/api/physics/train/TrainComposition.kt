package work.art1st.javarailways.core.api.physics.train

/** One permanent unit's canonical car order, independent of the current train's operating end. */
@ConsistentCopyVisibility
data class TrainUnitComposition private constructor(val unitId: String, val carIds: List<String>) {
    constructor(unitId: String, carIds: Collection<String>) : this(unitId, java.util.List.copyOf(carIds))

    init {
        require(unitId.isNotBlank()) { "Unit identity must not be blank" }
        require(carIds.isNotEmpty() && carIds.all { it.isNotBlank() } && carIds.distinct().size == carIds.size) {
            "Unit cars must be nonempty, unique stable identities"
        }
    }

    fun isReversedIn(physicalCarIds: List<String>): Boolean {
        val start = physicalCarIds.indexOfFirst { it in carIds }
        require(start >= 0 && start + carIds.size <= physicalCarIds.size) { "Unit '$unitId' is not complete in the formation" }
        val actual = physicalCarIds.subList(start, start + carIds.size)
        require(actual == carIds || actual == carIds.asReversed()) { "Unit '$unitId' is not contiguous in the formation" }
        return actual != carIds
    }
}

/** Immutable partition of the stable physical car order; whole-unit reversal retains its identity. */
@ConsistentCopyVisibility
data class TrainComposition private constructor(val units: List<TrainUnitComposition>) {
    /** Returns only complete units, ordered as they occur in the supplied physical formation. */
    fun unitsFor(physicalCarIds: List<String>): List<TrainUnitComposition> {
        require(physicalCarIds.distinct().size == physicalCarIds.size) { "Physical car identities must be unique" }
        val indices = physicalCarIds.withIndex().associate { it.value to it.index }
        return java.util.List.copyOf(units.mapNotNull { unit ->
            val positions = unit.carIds.map { indices[it] ?: return@mapNotNull null }
            val start = positions.minOrNull() ?: return@mapNotNull null
            if (start + unit.carIds.size > physicalCarIds.size) return@mapNotNull null
            val actual = physicalCarIds.subList(start, start + unit.carIds.size)
            if (actual != unit.carIds && actual != unit.carIds.asReversed()) return@mapNotNull null
            start to unit
        }.sortedBy { it.first }.map { it.second })
    }

    /** Frame publication validates this already-frozen partition without copying it each epoch. */
    internal fun requireMatches(physicalCarIds: List<String>) {
        require(units.sumOf { it.carIds.size } == physicalCarIds.size) { "Composition does not cover the physical formation" }
        var start = 0
        for (unit in units) {
            val canonical = unit.carIds
            require(canonical.indices.all { physicalCarIds[start + it] == canonical[it] } ||
                canonical.indices.all { physicalCarIds[start + it] == canonical[canonical.lastIndex - it] }) {
                "Composition units do not match the stable physical formation"
            }
            start += canonical.size
        }
    }

    /** Prepares both partitions, retaining complete unit identities and canonical fragment order. */
    fun splitAt(physicalCarIds: List<String>, index: Int, separatedTrainId: String): TrainCompositionSplit {
        require(index > 0 && index < physicalCarIds.size) { "Split must retain cars on both sides" }
        requireMatches(physicalCarIds)
        val left = physicalCarIds.take(index)
        val right = physicalCarIds.drop(index)
        val leftIds = left.toSet()
        val rightIds = right.toSet()
        val retained = mutableListOf<TrainUnitComposition>()
        val separated = mutableListOf<TrainUnitComposition>()
        for (unit in units) {
            val leftCars = unit.carIds.filter(leftIds::contains)
            val rightCars = unit.carIds.filter(rightIds::contains)
            when {
                rightCars.isEmpty() -> retained += unit
                leftCars.isEmpty() -> separated += unit
                else -> {
                    retained += TrainUnitComposition(unit.unitId, leftCars)
                    separated += TrainUnitComposition(separatedTrainId, rightCars)
                }
            }
        }
        val all = retained + separated
        require(all.map { it.unitId }.distinct().size == all.size) { "Split result unit identities collide" }
        return TrainCompositionSplit(create(retained, left), create(separated, right))
    }

    companion object {
        fun single(carIds: List<String>, unitId: String): TrainComposition =
            create(listOf(TrainUnitComposition(unitId, carIds)), carIds)

        fun create(units: Collection<TrainUnitComposition>, physicalCarIds: List<String>): TrainComposition {
            val captured = java.util.List.copyOf(units)
            require((captured.isNotEmpty() || physicalCarIds.isEmpty()) && captured.map { it.unitId }.distinct().size == captured.size) {
                "Composition must contain unique unit identities"
            }
            require(captured.flatMap { it.carIds }.let { all ->
                all.distinct().size == all.size && all.toSet() == physicalCarIds.toSet() && all.size == physicalCarIds.size
            }) { "Composition must partition every physical car exactly once" }
            val value = TrainComposition(captured)
            val ordered = value.unitsFor(physicalCarIds)
            require(ordered.size == captured.size) { "Composition units must be complete and contiguous" }
            return TrainComposition(ordered)
        }
    }
}

data class TrainCompositionSplit(val retained: TrainComposition, val separated: TrainComposition)
