package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionResult
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

/** The tool owns its text catalog, like RailWand and RailObjectWand. */
internal object TurnoutWandI18n {
    private val english = load("en_US")
    private val chinese = load("zh_CN")

    fun component(sender: CommandSender, key: String, vararg arguments: Any?): Component =
        Component.text(format((sender as? Player)?.locale() ?: Locale.US, key, *arguments))

    fun format(locale: Locale, key: String, vararg arguments: Any?): String {
        val catalog = if (locale.language.equals("zh", true)) chinese else english
        val template = catalog.getProperty(key) ?: english.getProperty(key) ?: key
        return if (arguments.isEmpty()) template else String.format(locale, template, *arguments)
    }

    fun result(locale: Locale, result: ManualTurnoutSelectionResult): String {
        val summary = format(locale, "result.${result.status.name.lowercase(Locale.ROOT)}")
        val blockers = result.blockingTrainIds.ifEmpty {
            result.blockers.map { "${it.owner.ownerId}@${it.owner.revision}" }
        }
        if (blockers.isEmpty()) return summary
        val shown = blockers.take(3).joinToString(", ") { it.take(80) }
        return "$summary ($shown${if (blockers.size > 3) ", …" else ""})"
    }

    private fun load(locale: String): Properties = Properties().also { properties ->
        val path = "/i18n/turnout_wand_$locale.properties"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing TurnoutWand translation resource: $path" }
        stream.use { properties.load(InputStreamReader(it, StandardCharsets.UTF_8)) }
    }
}
