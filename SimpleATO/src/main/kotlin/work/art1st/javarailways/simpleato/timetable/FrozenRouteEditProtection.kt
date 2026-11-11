package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.infrastructure.PhysicalRouteDependencyCompilation
import work.art1st.javarailways.basic.infrastructure.PhysicalRouteDependencyCompiler
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState

/**
 * Keeps the complete frozen route protected from infrastructure edits without claiming it.
 *
 * Replacements use one registry owner with a strictly newer revision, so append and reversal
 * install the new complete dependency set atomically rather than removing the old set first.
 */
internal class FrozenRouteEditProtection(
    private val ownerId: String,
    private val dependencies: InfrastructureDependencyService
) {
    private data class Installed(
        val owner: InfrastructureOwner,
        val routes: List<RouteSnapshot>
    )

    private var installed: Installed? = null

    fun protect(topology: TopologyState, route: RouteSnapshot): FrozenRouteProtectionResult =
        replace(topology, listOf(route))

    /**
     * Protects both sides of a reversal while Basic still retains the old grant.
     *
     * A retry keeps this union installed. The caller may shrink to [replacement] with [protect]
     * only after Basic has committed the reversal rebase.
     */
    fun protectReversalTransition(
        topology: TopologyState,
        retained: RouteSnapshot,
        replacement: RouteSnapshot
    ): FrozenRouteProtectionResult = replace(topology, listOf(retained, replacement).distinct())

    private fun replace(
        topology: TopologyState,
        routes: List<RouteSnapshot>
    ): FrozenRouteProtectionResult {
        require(routes.isNotEmpty()) { "frozen-route dependency requires at least one route" }
        val current = installed
        if (current?.routes == routes) return FrozenRouteProtectionResult.Protected(changed = false)

        val compiled = when (val result = PhysicalRouteDependencyCompiler.compile(topology, routes)) {
            is PhysicalRouteDependencyCompilation.Compiled -> result.dependencies
            PhysicalRouteDependencyCompilation.NoDependencies -> {
                return FrozenRouteProtectionResult.Rejected("frozen route has no infrastructure dependencies")
            }
            is PhysicalRouteDependencyCompilation.Rejected -> {
                return FrozenRouteProtectionResult.Rejected(result.detail)
            }
        }
        val revision = try {
            if (current == null) 0L else Math.addExact(current.owner.revision, 1L)
        } catch (_: ArithmeticException) {
            return FrozenRouteProtectionResult.Rejected(
                "frozen-route dependency revision cannot advance beyond ${current?.owner?.revision}"
            )
        }
        val owner = InfrastructureOwner(ownerId, revision)
        val mutation = dependencies.set(
            InfrastructureDependencyRequest(owner, compiled)
        )
        if (!mutation.successful) {
            return FrozenRouteProtectionResult.Rejected(
                buildString {
                    append("infrastructure dependency ${mutation.status}")
                    if (mutation.detail.isNotBlank()) append(": ${mutation.detail}")
                }
            )
        }
        installed = Installed(owner, routes)
        return FrozenRouteProtectionResult.Protected(changed = true)
    }

    fun release(): FrozenRouteProtectionResult {
        val current = installed ?: return FrozenRouteProtectionResult.Protected(changed = false)
        val mutation = dependencies.remove(current.owner)
        if (!mutation.successful) {
            return FrozenRouteProtectionResult.Rejected(
                buildString {
                    append("infrastructure dependency ${mutation.status}")
                    if (mutation.detail.isNotBlank()) append(": ${mutation.detail}")
                }
            )
        }
        installed = null
        return FrozenRouteProtectionResult.Protected(changed = true)
    }
}

internal sealed interface FrozenRouteProtectionResult {
    data class Protected(val changed: Boolean) : FrozenRouteProtectionResult

    data class Rejected(val detail: String) : FrozenRouteProtectionResult {
        init {
            require(detail.isNotBlank())
        }
    }
}
