package work.art1st.javarailways.decorations.display

import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPackage
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderer
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderSession

/** Validates resources and opens an owned dynamic template session on the host-owned worker. */
class DisplayPartProvider(
    private val openTemplate: (PartExtensionRenderContext, DisplayPartConfig, String, DisplayTemplateSource) -> PartExtensionRenderSession?,
    private val validateTemplates: (String, DisplayResources) -> Unit = { _, _ -> },
) : PartExtensionRenderer {
    override val typeId: String = TYPE_ID

    override fun validate(context: PartExtensionPackage) {
        context.parts.forEach { part ->
            val subject = "package '${context.packageName}' part '${part.name}' $TYPE_ID"
            require(part.schemaVersion == 1) { "$subject unsupported schema-version ${part.schemaVersion}; expected 1" }
            DisplayPartConfig.parse(part.config, subject)
        }
        context.resources?.let { resources ->
            require(resources.schemaVersion == 1) { "package '${context.packageName}' $TYPE_ID resources unsupported schema-version ${resources.schemaVersion}; expected 1" }
            val displays = DisplayResourceParser.parse(resources.files, context.packageName)
            validateTemplates(context.packageName, displays)
        }
    }

    override fun open(context: PartExtensionRenderContext): PartExtensionRenderSession? {
        val subject = "package '${context.packageName}' snapshot '${context.packageHash}' part '${context.part.name}' $TYPE_ID"
        require(context.part.schemaVersion == 1) { "$subject unsupported schema-version ${context.part.schemaVersion}; expected 1" }
        val config = DisplayPartConfig.parse(context.part.config, subject)
        val captured = context.resources ?: return null
        require(captured.schemaVersion == 1) { "$subject resources unsupported schema-version ${captured.schemaVersion}; expected 1" }
        val displays = DisplayResourceParser.parse(captured.files, context.packageName)
        val id = displays.defaults[config.category] ?: return null
        return openTemplate(context, config, id, displays.templates.getValue(id))
    }

    companion object { const val TYPE_ID = "decorations:display" }
}
