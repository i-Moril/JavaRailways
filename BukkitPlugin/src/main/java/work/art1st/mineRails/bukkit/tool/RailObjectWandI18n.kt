package work.art1st.mineRails.bukkit.tool

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

/** Locale-aware RailObjectWand text catalog; keys stay stable when wording changes. */
internal object RailObjectWandI18n {
    private const val BASE_PATH = "/i18n/rail_object_wand"
    private val english = load("${BASE_PATH}_en_US.properties")
    private val simplifiedChinese = load("${BASE_PATH}_zh_CN.properties")

    fun resolver(player: Player): (String) -> String = { key -> translate(player.locale(), key) }

    fun component(player: Player, key: String, vararg arguments: Any?): Component =
        Component.text(format(player.locale(), key, *arguments))

    fun format(player: Player, key: String, vararg arguments: Any?): String =
        format(player.locale(), key, *arguments)

    internal fun format(locale: Locale, key: String, vararg arguments: Any?): String {
        val template = translate(locale, key)
        return if (arguments.isEmpty()) template else String.format(locale, template, *arguments)
    }

    internal fun translate(locale: Locale, key: String): String {
        val primary = if (locale.language.equals("zh", ignoreCase = true)) simplifiedChinese else english
        return primary.getProperty(key) ?: english.getProperty(key) ?: key
    }

    private fun load(path: String): Properties = Properties().also { properties ->
        val stream = RailObjectWandI18n::class.java.getResourceAsStream(path)
            ?: error("Missing RailObjectWand translation resource: $path")
        stream.use { properties.load(InputStreamReader(it, StandardCharsets.UTF_8)) }
    }
}
