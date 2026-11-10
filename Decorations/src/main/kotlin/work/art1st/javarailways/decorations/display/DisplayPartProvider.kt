package work.art1st.javarailways.decorations.display

import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPackage
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderer
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderSession
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionAudiencePolicy

/** Validates resources and opens an owned dynamic template session on the host-owned worker. */
class DisplayPartProvider(
    private val openTemplate: (PartExtensionRenderContext, DisplayPartConfig, String, DisplayTemplateSource) -> PartExtensionRenderSession?,
    private val validateTemplates: (String, DisplayResources) -> Unit = { _, _ -> },
    private val interiorAudiencePolicy: PartExtensionAudiencePolicy = PartExtensionAudiencePolicy.UNRESTRICTED,
    private val runtimeStyles: RuntimeDisplayStyles? = null,
) : PartExtensionRenderer {
    override val typeId: String = TYPE_ID

    override fun validate(context: PartExtensionPackage) {
        val configs = context.parts.map { part ->
            val subject = "package '${context.packageName}' part '${part.name}' $TYPE_ID"
            require(part.schemaVersion == 1) { "$subject unsupported schema-version ${part.schemaVersion}; expected 1" }
            subject to DisplayPartConfig.parse(part.config, subject)
        }
        val displays = context.resources?.let { resources ->
            require(resources.schemaVersion == 1) { "package '${context.packageName}' $TYPE_ID resources unsupported schema-version ${resources.schemaVersion}; expected 1" }
            DisplayResourceParser.parse(resources.files, context.packageName)
        }
        configs.forEach { (subject, config) -> selectTemplate(config, displays, subject) }
        displays?.let { validateTemplates(context.packageName, it) }
    }

    override fun open(context: PartExtensionRenderContext): PartExtensionRenderSession? {
        val subject = "package '${context.packageName}' snapshot '${context.packageHash}' part '${context.part.name}' $TYPE_ID"
        require(context.part.schemaVersion == 1) { "$subject unsupported schema-version ${context.part.schemaVersion}; expected 1" }
        val config = DisplayPartConfig.parse(context.part.config, subject)
        val displays = context.resources?.let { captured ->
            require(captured.schemaVersion == 1) { "$subject resources unsupported schema-version ${captured.schemaVersion}; expected 1" }
            DisplayResourceParser.parse(captured.files, context.packageName)
        }
        val id = selectTemplate(config, displays, subject)
        if (runtimeStyles != null && displays != null && displays.templates.isNotEmpty()) {
            return RoutingDisplaySession(runtimeStyles, context, config, displays, id,
                if (config.interior) interiorAudiencePolicy else PartExtensionAudiencePolicy.UNRESTRICTED,
                openTemplate)
        }
        if (id == null) return null
        checkNotNull(displays)
        val session = openTemplate(context, config, id, displays.templates.getValue(id)) ?: return null
        return object : PartExtensionRenderSession by session {
            override val audiencePolicy = if (config.interior) interiorAudiencePolicy else PartExtensionAudiencePolicy.UNRESTRICTED
        }
    }

    private fun selectTemplate(config: DisplayPartConfig, displays: DisplayResources?, subject: String): String? {
        config.template?.let { id ->
            require(displays != null && id in displays.templates) {
                "$subject config.template references unknown template '$id' in captured package resources"
            }
            return id
        }
        return displays?.defaults?.get(config.category)
    }

    companion object { const val TYPE_ID = "decorations:display" }
}
