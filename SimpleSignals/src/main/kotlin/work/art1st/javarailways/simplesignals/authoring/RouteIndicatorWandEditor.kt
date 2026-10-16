package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.simplesignals.presentation.ModelPlacement

/** One callback-local mapping draft; the handler owns threading and continuation validity. */
internal class RouteIndicatorWandEditor(
    private val backend: SignalWandBackend,
    private val capture: SignalWandCapture,
    private val page: (String, List<Pair<String, () -> Unit>>) -> Unit,
    private val reply: (String) -> Unit,
    private val active: () -> Boolean
) {
    private val cancel = "Cancel" to { reply("Route indicator editing cancelled") }

    fun selectDevice() {
        val devices = backend.indicatorDevices(capture)
        require(devices.isNotEmpty()) { "This world has no route indicator to edit" }
        page("Select route indicator", devices.mapIndexed { index, device -> sectionAuthoringName(device.indicatorId, "Indicator ${index + 1}") to {
            require(backend.current(capture)) { "Signal selection expired; select again" }
            val binding = RouteIndicatorSignalWandBinding(capture.worldId, device.entryBoundaryId,
                device.indicatorId, device.revision)
            val entries = (backend.indicatorEntries(capture) + device.entryBoundaryId).distinct().sorted()
            page("Select indicator entry (keep model position)", entries.mapIndexed { entryIndex, entry -> sectionAuthoringName(entry, "Entrance ${entryIndex + 1}") to {
                configure(binding, entry, null)
            } } + cancel)
        } } + cancel)
    }

    fun configure(binding: RouteIndicatorSignalWandBinding, entry: String, placement: ModelPlacement?) {
        val options = backend.indicatorOptions(capture, binding, entry)
        page("Choose explicit no-route appearance", options.variants.map { variant -> variant to {
            mappingPage(binding, entry, placement, options, variant, options.mappings)
        } } + cancel)
    }

    private fun mappingPage(binding: RouteIndicatorSignalWandBinding, entry: String, placement: ModelPlacement?,
        options: RouteIndicatorWandOptions, noRoute: String, mappings: Map<String, String>) {
        require(backend.current(capture)) { "Signal selection expired; select again" }
        val missing = options.routeIds.count { it !in mappings }
        val routes = options.routeIds.mapIndexed { index, route ->
            "${sectionAuthoringName(route, "Route ${index + 1}")}: ${mappings[route] ?: "UNMAPPED (uses no-route appearance)"}" to {
                val variants = options.variants.map { variant -> variant to {
                    mappingPage(binding, entry, placement, options, noRoute, mappings + (route to variant))
                } }
                page("Appearance for ${sectionAuthoringName(route, "route ${index + 1}")}", listOf("Unmapped" to {
                    mappingPage(binding, entry, placement, options, noRoute, mappings - route)
                }) + variants + cancel)
            }
        }
        page("Route mappings: $missing missing | no-route: $noRoute", routes + listOf(
            "Review and save ($missing unmapped)" to {
                page("Confirm indicator at ${sectionAuthoringName(entry, "selected entrance")}?", listOf(
                    (if (placement == null) "Save mapping / entry (keep model position)" else "Create placed indicator") to {
                        reply(backend.saveIndicator(capture, binding, entry, noRoute, mappings, placement, active).message)
                    }, cancel))
            }, cancel))
    }
}
