package work.art1st.javarailways.decorations.integration.simpledisplays

import org.bukkit.plugin.Plugin
import simpledisplays.api.DisplayService
import simpledisplays.api.DisplayTemplate
import simpledisplays.api.TextDraw
import java.lang.reflect.Modifier
import java.util.logging.Level
import work.art1st.javarailways.decorations.display.DisplayResources

/** The only eagerly linked API boundary; instantiated reflectively after the plugin presence check. */
internal class SimpleDisplaysBinding(host: Plugin, owner: Plugin) : SimpleDisplaysConnection {
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

    override fun close() {
        service = null
        cache.close()
    }
}
