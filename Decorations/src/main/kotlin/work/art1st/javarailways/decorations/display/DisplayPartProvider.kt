package work.art1st.javarailways.decorations.display

import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionPackage
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionProvider

/** Validates existing metadata capability. Runtime mounting/rendering is deliberately a later API. */
class DisplayPartProvider : PartExtensionProvider {
    override val typeId: String = TYPE_ID

    override fun validate(context: PartExtensionPackage) {
        context.parts.forEach { part ->
            val subject = "package '${context.packageName}' part '${part.name}' $TYPE_ID"
            require(part.schemaVersion == 1) { "$subject unsupported schema-version ${part.schemaVersion}; expected 1" }
            DisplayPartConfig.parse(part.config, subject)
        }
        context.resources?.let { resources ->
            require(resources.schemaVersion == 1) { "package '${context.packageName}' $TYPE_ID resources unsupported schema-version ${resources.schemaVersion}; expected 1" }
            DisplayResourceParser.parse(resources.files, context.packageName)
        }
    }

    companion object { const val TYPE_ID = "decorations:display" }
}
