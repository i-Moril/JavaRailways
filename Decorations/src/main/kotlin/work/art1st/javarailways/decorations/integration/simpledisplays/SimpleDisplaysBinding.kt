package work.art1st.javarailways.decorations.integration.simpledisplays

import org.bukkit.plugin.Plugin
import simpledisplays.api.DisplayService
import simpledisplays.api.DisplayTemplate
import simpledisplays.api.TextDraw
import java.lang.reflect.Modifier
import java.util.logging.Level
import work.art1st.javarailways.decorations.display.DisplayResources
import work.art1st.javarailways.decorations.display.DisplayPartConfig
import work.art1st.javarailways.decorations.display.DisplayTemplateSource
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionFrame
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionText

/** The only eagerly linked API boundary; instantiated reflectively after the plugin presence check. */
internal class SimpleDisplaysBinding(host: Plugin, owner: Plugin) : SimpleDisplaysConnection {
    private val lifecycle = Any()
    @Volatile
    internal var service: DisplayService? = null
        private set
    override val serviceType: Class<*> get() = DisplayService::class.java
    override val provider: Any? get() = service
    private val cache = CompiledTemplateCache(
        { source -> checkNotNull(service) { "SimpleDisplays connection is retired" }.compile(source) },
        { failure -> host.logger.log(Level.WARNING, "Failed to close compiled SimpleDisplays templates", failure) },
    )

    init {
        // Reject incompatible API shape before retaining a service. No XML compilation or entities here.
        val compile = DisplayService::class.java.getMethod("compile", String::class.java, Map::class.java)
        check(compile.returnType == DisplayTemplate::class.java) { "Unexpected DisplayService.compile return type" }
        check(DisplayTemplate::class.java.getMethod("getWidth").returnType == Double::class.javaPrimitiveType)
        check(DisplayTemplate::class.java.getMethod("getHeight").returnType == Double::class.javaPrimitiveType)
        check(DisplayTemplate::class.java.getMethod("draw", Map::class.java, Long::class.javaPrimitiveType).returnType == List::class.java)
        check(AutoCloseable::class.java.isAssignableFrom(DisplayTemplate::class.java)) { "DisplayTemplate must support close" }
        val close = DisplayTemplate::class.java.getMethod("close")
        check(close.returnType == Void.TYPE && close.declaringClass == DisplayTemplate::class.java) { "Unexpected DisplayTemplate.close contract" }
        check(TextDraw::class.java.getMethod("getText").returnType.name == "net.kyori.adventure.text.Component")
        listOf("getX", "getY", "getSize").forEach { method ->
            check(TextDraw::class.java.getMethod(method).returnType == Double::class.javaPrimitiveType) { "Unexpected TextDraw.$method return type" }
        }
        val registration = checkNotNull(host.server.servicesManager.getRegistration(DisplayService::class.java)) {
            "SimpleDisplays did not register DisplayService"
        }
        check(registration.plugin === owner && owner.isEnabled) { "DisplayService belongs to a different or disabled plugin" }
        val implementation = registration.provider
        check(!Modifier.isAbstract(implementation.javaClass.getMethod("compile", String::class.java, Map::class.java).modifiers)) {
            "DisplayService provider does not implement compile"
        }
        service = implementation
    }

    override fun validateTemplates(packageName: String, resources: DisplayResources) {
        resources.templates.forEach { (id, template) ->
            try { cache.validate(template.source) } catch (failure: Exception) {
                throw IllegalArgumentException("package '$packageName' display template '$id' file '${template.file}' failed to compile: ${failure.message ?: failure.javaClass.simpleName}", failure)
            }
        }
    }

    override fun prepareTemplate(context: PartExtensionRenderContext, config: DisplayPartConfig,
        templateId: String, template: DisplayTemplateSource): PartExtensionFrame {
        val subject = "package '${context.packageName}' snapshot '${context.packageHash}' part '${context.part.name}' display template '$templateId' file '${template.file}'"
        try {
            val compiler = synchronized(lifecycle) { checkNotNull(service) { "SimpleDisplays connection is retired" } }
            // A fresh private template cannot be evicted by concurrent validation or used by another draw.
            val compiled = compiler.compile(template.source)
            var primary: Throwable? = null
            val frame = try {
                require(compiled.width.isFinite() && compiled.width > 0 && compiled.height.isFinite() && compiled.height > 0) { "Invalid logical canvas dimensions" }
                val unit = minOf(config.width / compiled.width, config.height / compiled.height)
                require(unit.isFinite() && unit > 0) { "Invalid canvas fitting scale" }
                val draws = compiled.draw(emptyMap(), 0L)
                require(draws.size <= 1024) { "Static display frame exceeds 1024 text nodes" }
                PartExtensionFrame(draws.map { draw ->
                    require(draw.x.isFinite() && draw.y.isFinite() && draw.size.isFinite() && draw.size > 0) { "Invalid logical text geometry" }
                    val x = (draw.x - compiled.width / 2) * unit
                    val y = (compiled.height / 2 - draw.y - draw.size) * unit
                    val scale = draw.size * unit * 40.0 / 9.0
                    require(x.isFinite() && y.isFinite() && scale.isFinite() && scale > 0) { "Invalid derived physical text geometry" }
                    PartExtensionText(draw.text, ModelPose(x, y, 0.0, 0.0, 0.0, 0.0, 1.0), ModelScale(scale, scale, scale))
                })
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try { compiled.close() } catch (cleanup: Throwable) {
                    if (primary == null) throw cleanup
                    if (primary !== cleanup) primary.addSuppressed(cleanup)
                }
            }
            // Linearize completed detachment with retirement. Already detached frames may outlive SimpleDisplays.
            return synchronized(lifecycle) {
                check(service === compiler) { "SimpleDisplays connection retired during static preparation" }
                frame
            }
        } catch (failure: Exception) {
            throw IllegalArgumentException("$subject failed to prepare static frame: ${failure.message ?: failure.javaClass.simpleName}", failure)
        }
    }

    override fun close() {
        synchronized(lifecycle) { service = null }
        cache.close()
    }
}
