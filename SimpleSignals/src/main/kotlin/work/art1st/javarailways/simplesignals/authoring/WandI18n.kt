package work.art1st.javarailways.simplesignals.authoring

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

/** The addon owns its catalog. IDs and arbitrary diagnostic messages are never translated. */
internal object WandI18n {
    private val english = load("en_US")
    private val chinese = load("zh_CN")

    fun format(sender: CommandSender, key: String, vararg arguments: Any?): String =
        format((sender as? Player)?.locale() ?: Locale.US, key, *arguments)

    fun format(locale: Locale, key: String, vararg arguments: Any?): String {
        val catalog = if (locale.language.equals("zh", ignoreCase = true)) chinese else english
        val template = catalog.getProperty(key) ?: english.getProperty(key) ?: key
        val values = arguments.map { if (it is WandText) it.render(locale) else it }.toTypedArray()
        return if (values.isEmpty()) template else String.format(locale, template, *values)
    }

    private fun load(locale: String): Properties = Properties().also { properties ->
        val path = "/i18n/wands_$locale.properties"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing SimpleSignals catalog: $path" }
        stream.use { properties.load(InputStreamReader(it, StandardCharsets.UTF_8)) }
    }
}

/** Value-only feedback can cross workers or live in a geometry cache; resolve on the viewer owner. */
internal data class WandText(val key: String?, val arguments: List<Any?>) {
    fun render(locale: Locale): String = if (key == null) arguments.single().toString()
        else WandI18n.format(locale, key, *arguments.toTypedArray())

    companion object {
        fun key(key: String, vararg arguments: Any?): WandText = WandText(key, arguments.toList())
        fun diagnostic(value: String): WandText = WandText(null, listOf(value))
    }
}
