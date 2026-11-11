package work.art1st.javarailways.basic.api.route

/**
 * Detached candidate traversal tagged with a caller-owned [routeId] and plan [revision].
 * [revision] is a non-negative mission/plan revision, not a topology revision or physics epoch;
 * [routeId] must be nonblank and is interpreted within the caller's world and lifecycle.
 *
 * Contains only immutable values and performs no I/O or world lookup, so it may cross threads.
 * Construction does not validate physical connectivity, acquire resources or grant authority.
 * Admission must bind the traversal to the intended world/topology and revalidate it there.
 * This runtime planning value does not define a persistence format or registered lifecycle.
 *
 * @property routeId Nonblank route identity within the caller's world and lifecycle.
 * @property revision Non-negative caller-owned plan revision, not a topology revision or epoch.
 * @property expandedRoute Immutable candidate traversal; it is not a resource grant.
 */
data class PlannedRoute(
    val routeId: String,
    val revision: Long,
    val expandedRoute: ExpandedRoute
) {
    init {
        require(routeId.isNotBlank())
        require(revision >= 0L)
    }
}
