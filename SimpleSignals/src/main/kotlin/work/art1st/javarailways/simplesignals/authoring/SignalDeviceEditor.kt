package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.simplesignals.runtime.SectionSignalState
import java.time.Instant
import java.util.Locale

/** One exact local device. The handler provides owner scheduling and stale-item guards. */
internal class SignalDeviceEditor(
    private val backend: SignalWandBackend,
    private var capture: SignalWandCapture,
    private val device: PlacedSignalChoice,
    private val page: (SignalWandDialog) -> Unit,
    private val reply: (String) -> Unit,
    private val active: () -> Boolean,
    private val placement: () -> Unit,
    private val rebind: () -> Unit,
    private val indicator: () -> Unit,
    private val attached: () -> Unit,
    private val locale: Locale
) {
    private fun text(key: String, vararg values: Any?) = WandI18n.format(locale, key, *values)
    private fun enumText(group: String, value: String): String {
        val key = "signal.diagnostic.$group.$value"
        return text(key).let { if (it == key) value else it }
    }
    private val name get() = authoringName(device.id, "name.signal").render(locale)

    fun show(details: Boolean = false) {
        capture = backend.capture(capture.worldId, null)
        require(device in backend.placedDevices(capture)) { text("editor.selection.expired") }
        val readAt = Instant.now()
        val diagnostic = backend.diagnostics(capture, device)
        val body = mutableListOf<String>()
        when (val state = diagnostic?.state) {
            is SectionSignalState.Available -> body += text("signal.diagnostic.available",
                state.output.speedLimitMetersPerSecond?.let { "$it m/s" } ?: text("signal.diagnostic.unlimited"), state.output.modelVariant)
            is SectionSignalState.Unavailable -> body += text("signal.diagnostic.unavailable", state.reason)
            null -> body += text(if (device.kind == PlacedSignalKind.INDICATOR) "signal.diagnostic.indicator" else "signal.diagnostic.waiting")
        }
        body += text("signal.diagnostic.times", diagnostic?.evaluatedAt ?: text("none"),
            diagnostic?.observedAt ?: text("none"), readAt)
        diagnostic?.stamp?.let { body += text("signal.diagnostic.epoch", it.physicsEpoch) }
        val detailBody = mutableListOf<String>()
        run {
            detailBody += text("signal.diagnostic.self", diagnostic?.selfOccupied?.let { text(if (it) "signal.diagnostic.occupied" else "signal.diagnostic.clear") } ?: text("none"),
                diagnostic?.selfLengthMeters ?: text("none"), diagnostic?.scanDistanceMeters ?: text("none"))
            detailBody += diagnostic?.forward?.map { text("signal.diagnostic.forward", it.branchPath,
                enumText("selection", it.selection),
                it.routeReady?.let { ready -> text(if (ready) "signal.diagnostic.ready" else "signal.diagnostic.not_ready") } ?: text("none"), it.distanceMeters,
                if (!it.hasDownstreamSignal) text("none") else it.speedLimitMetersPerSecond?.let { speed -> "$speed m/s" }
                    ?: text("signal.diagnostic.unlimited")) }.orEmpty().ifEmpty { listOf(text("signal.diagnostic.forward.unknown")) }
            detailBody += diagnostic?.terminations?.map { text("signal.diagnostic.end", it.branchPath, it.distanceMeters,
                enumText("reason", it.reason)) }
                .orEmpty().ifEmpty { listOf(text("signal.diagnostic.end.unknown")) }
            detailBody += text("signal.diagnostic.stop.help")
        }
        val configuration = backend.deviceConfiguration(capture, device)
        body += text("signal.device.configuration", configuration.signalType?.let { id -> backend.availableSignalTypes()
            ?.singleOrNull { it.signalType == id }?.displayName ?: id } ?: text("signal.model.only"), configuration.modelId ?: text("none"))
        // A static client action deliberately keeps this historical report even after the world changes.
        // Summary and details share this read; only Refresh captures another published result.
        val report = (listOf(text("signal.diagnostic.report.source"),
            "worldId=${capture.worldId}", "deviceId=${device.id}; revision=${device.revision}; kind=${device.kind}",
            "signalType=${configuration.signalType}; modelId=${configuration.modelId}",
            "state=${when (diagnostic?.state) { is SectionSignalState.Available -> "Available"; is SectionSignalState.Unavailable -> "Unavailable";
                null -> if (device.kind == PlacedSignalKind.INDICATOR) "Not applicable (indicator has no independent JS)" else "No baseline" }}",
            when (val state = diagnostic?.state) {
                is SectionSignalState.Available -> "JS speedLimitMetersPerSecond=${state.output.speedLimitMetersPerSecond}; modelVariant=${state.output.modelVariant}"
                is SectionSignalState.Unavailable -> "No valid JS output; raw reason=${state.reason}"
                null -> if (device.kind == PlacedSignalKind.INDICATOR) "No independent JS evaluation for a route indicator"
                    else "No JS output snapshot"
            },
            "stamp=${diagnostic?.stamp ?: "unknown"}") + body + detailBody).joinToString("\n")
        fun display(detailed: Boolean) {
            val refresh = text("signal.diagnostic.refresh") to { show(detailed) }
            if (detailed) {
                page(SignalWandDialog(name, body + detailBody, listOf(refresh, text("back") to { display(false) }),
                    clipboardText = report))
                return
            }
            val options = mutableListOf(refresh, text("signal.diagnostic.more") to { display(true) },
                text("signal.placement.edit") to placement,
                text(if (configuration.signalType == null) "signal.device.model.change" else "signal.device.type.change") to { types(configuration.signalType == null) })
            if (device.kind != PlacedSignalKind.ATTACHED) options += text("signal.rebind") to rebind
            if (device.kind == PlacedSignalKind.INDICATOR) options += text("signal.indicator.edit") to indicator
            else if (device.kind != PlacedSignalKind.ATTACHED) options += text("signal.device.attached") to attached
            page(SignalWandDialog(name, body, options, text("cancel") to { reply(text("signal.editor.closed")) }, clipboardText = report))
        }
        display(details)
    }

    private fun types(modelOnly: Boolean) {
        val options = if (modelOnly) backend.availableModelIds().orEmpty().map { it to it }
            else backend.availableSignalTypes().orEmpty().map { it.signalType to it.displayName }
        page(SignalWandDialog(text("signal.device.type.change"), listOf(text(if (options.isEmpty()) {
            if (modelOnly) "signal.model.empty" else "signal.empty"
        } else "signal.types.validation")), options.map { (id, label) -> label to {
            page(SignalWandDialog(text("signal.device.type.confirm"), listOf(label, text("signal.device.preserve")), listOf(
                text("apply") to { reply(backend.changeType(capture, device, if (modelOnly) null else id,
                    if (modelOnly) id else null, active).text.render(locale)) }, text("cancel") to { show() })))
        } }, text("cancel") to { show() }))
    }
}
