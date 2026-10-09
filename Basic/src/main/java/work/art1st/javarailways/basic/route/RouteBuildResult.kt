package work.art1st.javarailways.basic.route

import work.art1st.javarailways.basic.api.route.ExpandedRoute

sealed class RouteBuildResult {
    data class Success(val route: ExpandedRoute) : RouteBuildResult()
    data class Failure(val reason: String) : RouteBuildResult()
}
