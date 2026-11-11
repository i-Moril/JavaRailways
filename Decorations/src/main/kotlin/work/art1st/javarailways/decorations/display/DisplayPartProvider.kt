package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPackage
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderer
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderSession
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderInput
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionFrame
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionAudiencePolicy

/** Validates resources and opens an owned dynamic template session on the host-owned worker. */
class DisplayPartProvider(
    private val openTemplate: (PartExtensionRenderContext, DisplayPartConfig, String, DisplayTemplateSource) -> PartExtensionRenderSession?,
    private val validateTemplates: (String, DisplayResources) -> Unit = { _, _ -> },
    private val interiorAudiencePolicy: PartExtensionAudiencePolicy = PartExtensionAudiencePolicy.UNRESTRICTED,
    private val runtimeStyles: RuntimeDisplayStyles? = null,
    private val externalStyles: DisplayResources = DisplayResources(),
) : PartExtensionRenderer {
    override val typeId: String = TYPE_ID

    override fun validate(context: PartExtensionPackage) {
        val configs = context.parts.map { part ->
            val subject = "package '${context.packageName}' part '${part.name}' $TYPE_ID"
            require(part.schemaVersion == 1) { "$subject unsupported schema-version ${part.schemaVersion}; expected 1" }
            subject to DisplayPartConfig.parse(part.config, subject)
        }
        val local = context.resources?.let { resources ->
            require(resources.schemaVersion == 1) { "package '${context.packageName}' $TYPE_ID resources unsupported schema-version ${resources.schemaVersion}; expected 1" }
            DisplayResourceParser.parse(resources.files, context.packageName)
        }
        val displays = DisplayStyleBundles.merge(local, externalStyles, "package '${context.packageName}'")
        configs.forEach { (subject, config) -> selectTemplate(config, displays, subject) }
        displays?.let { validateTemplates(context.packageName, it) }
    }

    override fun open(context: PartExtensionRenderContext): PartExtensionRenderSession? {
        val subject = "package '${context.packageName}' snapshot '${context.packageHash}' part '${context.part.name}' $TYPE_ID"
        require(context.part.schemaVersion == 1) { "$subject unsupported schema-version ${context.part.schemaVersion}; expected 1" }
        val config = DisplayPartConfig.parse(context.part.config, subject)
        val local = context.resources?.let { captured ->
            require(captured.schemaVersion == 1) { "$subject resources unsupported schema-version ${captured.schemaVersion}; expected 1" }
            DisplayResourceParser.parse(captured.files, context.packageName)
        }
        val displays = DisplayStyleBundles.merge(local, externalStyles, subject)
        val id = selectTemplate(config, displays, subject)
        val policy = if (config.interior) interiorAudiencePolicy else PartExtensionAudiencePolicy.UNRESTRICTED
        val session = if (runtimeStyles != null && displays != null && displays.templates.isNotEmpty()) {
            RoutingDisplaySession(runtimeStyles, context, config, displays, id, policy,
                openTemplate)
        } else {
            if (id == null) return null
            checkNotNull(displays)
            openTemplate(context, config, id, displays.templates.getValue(id)) ?: return null
        }
        return object : PartExtensionRenderSession by session {
            override val audiencePolicy = policy
            override fun draw(input: PartExtensionRenderInput): PartExtensionFrame {
                if ((input.data["operational"] as? JsonObject)?.get("currentRun") !is JsonObject) {
                    // Keep metadata/style bindings current for direct callers too, without
                    // evaluating XML/JS. Host context invalidation retires the previous output.
                    session.update(input)
                    return PartExtensionFrame.EMPTY
                }
                return session.draw(input)
            }
        }
    }

    private fun selectTemplate(config: DisplayPartConfig, displays: DisplayResources?, subject: String): String? {
        config.template?.let { id ->
            require(displays != null && id in displays.templates) {
                "$subject config.template references unknown template '$id' in captured package resources or enabled external styles"
            }
            return id
        }
        return displays?.defaults?.get(config.category)
    }

    companion object { const val TYPE_ID = "decorations:display" }
}
