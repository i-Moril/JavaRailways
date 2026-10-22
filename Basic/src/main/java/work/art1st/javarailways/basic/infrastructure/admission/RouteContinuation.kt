package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot

/** Exact occurrence-preserving append shared by ordinary and held-output admission. */
internal fun isAppendOnlyContinuation(
    committed: RouteSnapshot,
    requested: RouteSnapshot
): Boolean = requested.routeId == committed.routeId &&
        requested.revision > committed.revision &&
        requested.segments.size > committed.segments.size &&
        requested.segments.subList(0, committed.segments.size) == committed.segments
