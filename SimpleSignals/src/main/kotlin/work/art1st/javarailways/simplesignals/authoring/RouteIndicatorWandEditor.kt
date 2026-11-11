package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import java.util.Locale

/** One callback-local mapping draft; the handler owns threading and continuation validity. */
internal class RouteIndicatorWandEditor(
    private val backend: SignalWandBackend,
    private val capture: SignalWandCapture,
    private val page: (String, List<Pair<String, () -> Unit>>) -> Unit,
    private val reply: (String) -> Unit,
    private val active: () -> Boolean,
    private val locale: Locale = Locale.US,
    private val saved: (SignalWandOutcome) -> Unit = { reply(it.text.render(locale)) }
) {
    private fun text(key: String, vararg arguments: Any?): String = WandI18n.format(locale, key, *arguments)
    private val cancel = text("editor.cancel") to { reply(text("editor.indicator.cancelled")) }


    fun configure(binding: RouteIndicatorSignalWandBinding, entry: String, placement: ModelPlacement?) {
        val options = backend.indicatorOptions(capture, binding, entry)
        page(text("editor.indicator.no_route"), options.variants.map { variant -> variant to {
            mappingPage(binding, entry, placement, options, variant, options.mappings)
        } } + cancel)
    }

    private fun mappingPage(binding: RouteIndicatorSignalWandBinding, entry: String, placement: ModelPlacement?,
        options: RouteIndicatorWandOptions, noRoute: String, mappings: Map<String, String>) {
        require(backend.current(capture)) { text("editor.selection.expired") }
        val missing = options.routeIds.count { it !in mappings }
        val routes = options.routeIds.mapIndexed { index, route ->
            text("editor.indicator.mapping", options.routeLabels[route] ?: authoringName(route, "editor.name.route"), index + 1,
                mappings[route] ?: text("editor.indicator.unmapped_hint")) to {
                val variants = options.variants.map { variant -> variant to {
                    mappingPage(binding, entry, placement, options, noRoute, mappings + (route to variant))
                } }
                page(text("editor.indicator.appearance", options.routeLabels[route] ?: authoringName(route, "editor.name.route"), index + 1),
                    listOf(text("editor.indicator.unmapped") to {
                    mappingPage(binding, entry, placement, options, noRoute, mappings - route)
                }) + variants + cancel)
            }
        }
        page(text("editor.indicator.mappings", missing, noRoute), routes + listOf(
            text("editor.indicator.review", missing) to {
                page(text("editor.indicator.confirm", backend.boundaryLabel(capture, entry)), listOf(
                    text(if (placement == null) "editor.indicator.save" else "editor.indicator.create") to {
                        saved(backend.saveIndicator(capture, binding, entry, noRoute, mappings, placement, active))
                    }, cancel))
            }, cancel))
    }
}
