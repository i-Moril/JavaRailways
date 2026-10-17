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
        if (details) {
            body += text("signal.diagnostic.self", diagnostic?.selfOccupied?.let { text(if (it) "signal.diagnostic.occupied" else "signal.diagnostic.clear") } ?: text("none"),
                diagnostic?.selfLengthMeters ?: text("none"), diagnostic?.scanDistanceMeters ?: text("none"))
            body += diagnostic?.forward?.map { text("signal.diagnostic.forward", it.branchPath,
                text("signal.diagnostic.selection.${it.selection}"),
                it.routeReady?.let { ready -> text(if (ready) "signal.diagnostic.ready" else "signal.diagnostic.not_ready") } ?: text("none"), it.distanceMeters,
                if (!it.hasDownstreamSignal) text("none") else it.speedLimitMetersPerSecond?.let { speed -> "$speed m/s" }
                    ?: text("signal.diagnostic.unlimited")) }.orEmpty().ifEmpty { listOf(text("signal.diagnostic.forward.unknown")) }
            body += diagnostic?.terminations?.map { text("signal.diagnostic.end", it.branchPath, it.distanceMeters,
                text("signal.diagnostic.reason.${it.reason}")) }
                .orEmpty().ifEmpty { listOf(text("signal.diagnostic.end.unknown")) }
            body += text("signal.diagnostic.stop.help")
        }
        val refresh = text("signal.diagnostic.refresh") to { show(details) }
        if (details) {
            page(SignalWandDialog(name, body, listOf(refresh, text("back") to { show() })))
            return
        }
        val configuration = backend.deviceConfiguration(capture, device)
        body += text("signal.device.configuration", configuration.signalType?.let { id -> backend.availableSignalTypes()
            ?.singleOrNull { it.signalType == id }?.displayName ?: id } ?: text("signal.model.only"), configuration.modelId ?: text("none"))
        val options = mutableListOf(refresh, text("signal.diagnostic.more") to { show(true) },
            text("signal.placement.edit") to placement,
            text(if (configuration.signalType == null) "signal.device.model.change" else "signal.device.type.change") to { types(configuration.signalType == null) })
        if (device.kind != PlacedSignalKind.ATTACHED) options += text("signal.rebind") to rebind
        if (device.kind == PlacedSignalKind.INDICATOR) options += text("signal.indicator.edit") to indicator
        else if (device.kind != PlacedSignalKind.ATTACHED) options += text("signal.device.attached") to attached
        page(SignalWandDialog(name, body, options, text("cancel") to { reply(text("signal.editor.closed")) }))
    }

    private fun types(modelOnly: Boolean) {
        val options = if (modelOnly) backend.availableModelIds().orEmpty().map { it to it }
            else backend.availableSignalTypes().orEmpty().map { it.signalType to it.displayName }
        page(SignalWandDialog(text("signal.device.type.change"), listOf(text("signal.types.validation")), options.map { (id, label) -> label to {
            page(SignalWandDialog(text("signal.device.type.confirm"), listOf(label, text("signal.device.preserve")), listOf(
                text("apply") to { reply(backend.changeType(capture, device, if (modelOnly) null else id,
                    if (modelOnly) id else null, active).text.render(locale)) }, text("cancel") to { show() })))
        } }, text("cancel") to { show() }))
    }
}
