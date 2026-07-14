package work.art1st.mineRails.basic.api.ato.constraint

import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile

/** Owner-scoped constraint storage. Each [replace] is atomic. */
class ConstraintManager {
    private val lock = Any()
    private val constraints = LinkedHashMap<ConstraintOwner, List<AtoConstraint>>()

    fun replace(owner: ConstraintOwner, values: Collection<AtoConstraint>) {
        val copy = values.toList()
        require(copy.map { it.key }.toSet().size == copy.size) {
            "duplicate constraint key for owner $owner"
        }
        synchronized(lock) {
            if (copy.isEmpty()) constraints.remove(owner) else constraints[owner] = copy
        }
    }

    fun remove(owner: ConstraintOwner) {
        synchronized(lock) { constraints.remove(owner) }
    }

    fun permittedSpeedMetersPerSecond(
        currentSpeedMetersPerSecond: Double,
        maximumSpeedMetersPerSecond: Double,
        profile: AtoSafetyProfile
    ): Double {
        require(currentSpeedMetersPerSecond >= 0.0 && currentSpeedMetersPerSecond.isFinite())
        require(maximumSpeedMetersPerSecond > 0.0 && maximumSpeedMetersPerSecond.isFinite())
        return synchronized(lock) {
            var limit = maximumSpeedMetersPerSecond
            constraints.values.forEach { ownerConstraints ->
                ownerConstraints.forEach { constraint ->
                    limit = minOf(
                        limit,
                        constraint.permittedSpeedMetersPerSecond(currentSpeedMetersPerSecond, profile)
                    )
                }
            }
            limit
        }
    }

    fun snapshot(): Map<ConstraintOwner, List<AtoConstraint>> = synchronized(lock) {
        constraints.mapValues { (_, values) -> values.toList() }
    }

}
