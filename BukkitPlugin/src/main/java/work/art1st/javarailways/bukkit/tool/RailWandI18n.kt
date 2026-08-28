package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties

/** Locale-aware RailWand text catalog; source code uses stable semantic keys instead of display text. */
internal object RailWandI18n {
    private const val BASE_PATH = "/i18n/rail_wand"
    private val english = load("${BASE_PATH}_en_US.properties")
    private val simplifiedChinese = load("${BASE_PATH}_zh_CN.properties")

    private const val REFERENCE_PREFIX = "@jr-i18n:"
    private const val ARGUMENT_SEPARATOR = "\u001f"

    fun resolver(player: Player): (String) -> String = { reference -> resolve(player.locale(), reference) }

    internal fun resolve(locale: Locale, reference: String): String {
        val encoded = reference.removePrefix(REFERENCE_PREFIX)
        val parts = encoded.split(ARGUMENT_SEPARATOR)
        return if (parts.size == 1) {
            translate(locale, parts.first())
        } else {
            format(locale, parts.first(), *parts.drop(1).toTypedArray())
        }
    }

    fun reference(key: String, vararg arguments: Any?): String = REFERENCE_PREFIX +
        (listOf(key) + arguments.map { it.toString() }).joinToString(ARGUMENT_SEPARATOR)

    fun resolve(player: Player, reference: String): String = resolver(player)(reference)

    fun component(player: Player, key: String, vararg arguments: Any?): Component =
        Component.text(format(player.locale(), key, *arguments))

    fun format(player: Player, key: String, vararg arguments: Any?): String =
        format(player.locale(), key, *arguments)

    internal fun format(locale: Locale, key: String, vararg arguments: Any?): String {
        val template = translate(locale, key)
        if (arguments.isEmpty()) return template
        val localizedArguments = arguments.map { argument ->
            if (argument is String && argument.startsWith(REFERENCE_PREFIX)) {
                resolve(locale, argument)
            } else {
                argument
            }
        }.toTypedArray()
        return String.format(locale, template, *localizedArguments)
    }

    internal fun translate(locale: Locale, key: String): String {
        val primary = if (locale.language.equals("zh", ignoreCase = true)) simplifiedChinese else english
        return primary.getProperty(key) ?: english.getProperty(key) ?: key
    }

    internal fun englishKeys(): Set<String> = english.stringPropertyNames()

    internal fun simplifiedChineseKeys(): Set<String> = simplifiedChinese.stringPropertyNames()

    private fun load(path: String): Properties = Properties().also { properties ->
        val stream = RailWandI18n::class.java.getResourceAsStream(path)
            ?: error("Missing RailWand translation resource: $path")
        stream.use { properties.load(InputStreamReader(it, StandardCharsets.UTF_8)) }
    }
}
