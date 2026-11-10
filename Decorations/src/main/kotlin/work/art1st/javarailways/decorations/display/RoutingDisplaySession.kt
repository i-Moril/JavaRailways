package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionAudiencePolicy
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionFrame
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderInput
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderSession

/** Worker-owned private template, with cheap revision reads on host model/control threads. */
internal class RoutingDisplaySession(
    private val styles: RuntimeDisplayStyles,
    private val context: PartExtensionRenderContext,
    private val config: DisplayPartConfig,
    private val resources: DisplayResources,
    private val defaultTemplate: String?,
    override val audiencePolicy: PartExtensionAudiencePolicy,
    private val openTemplate: (PartExtensionRenderContext, DisplayPartConfig, String, DisplayTemplateSource) -> PartExtensionRenderSession?,
) : PartExtensionRenderSession {
    private val binding = styles.register(context.worldId, config.category, resources.templates.keys,
        "${context.packageName}@${context.packageHash}", defaultTemplate)
    override val renderRevision: Long get() = binding.revision
    private var selectedId: String? = null
    private var selectedSession: PartExtensionRenderSession? = null
    private var closed = false

    override fun update(input: PartExtensionRenderInput) {
        if (closed) return
        val unit = (input.data["unit"] as? JsonObject)?.get("id") as? JsonPrimitive
        styles.bind(binding, unit?.takeIf { it.isString && it.content.isNotBlank() }?.content)
    }

    override fun draw(input: PartExtensionRenderInput): PartExtensionFrame {
        if (closed) return PartExtensionFrame.EMPTY
        // Also support direct callers. The host normally supplies this through update while hidden.
        update(input)
        val id = if (styles.isActive(binding)) styles.overrideFor(binding) ?: defaultTemplate else null
        if (id != selectedId) {
            val previous = selectedSession
            selectedSession = null
            selectedId = null
            previous?.close()
        }
        if (id == null) return PartExtensionFrame.EMPTY
        val source = requireNotNull(resources.templates[id]) {
            "package '${context.packageName}' snapshot '${context.packageHash}' part '${context.part.name}' " +
                "unit display override references unknown captured template '$id'"
        }
        var session = selectedSession
        if (session == null) {
            session = openTemplate(context, config, id, source) ?: return PartExtensionFrame.EMPTY
            selectedSession = session
            selectedId = id
        }
        session.update(input)
        return session.draw(input)
    }

    override fun close() {
        if (closed) return
        closed = true
        styles.unbind(binding)
        val previous = selectedSession
        selectedSession = null
        selectedId = null
        previous?.close()
    }
}
