package work.art1st.javarailways.decorations.integration.simpledisplays

import org.bukkit.plugin.Plugin
import simpledisplays.api.DisplayService
import simpledisplays.api.DisplayContext
import kotlinx.serialization.json.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
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
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderSession
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderInput
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionText

/** The only eagerly linked API boundary; instantiated reflectively after the plugin presence check. */
internal class SimpleDisplaysBinding(host: Plugin, owner: Plugin) : SimpleDisplaysConnection {
    private val lifecycle = Any()
    private val sessions = mutableSetOf<Session>()
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
        check(DisplayTemplate::class.java.getMethod("draw", DisplayContext::class.java).returnType == List::class.java)
        check(AutoCloseable::class.java.isAssignableFrom(DisplayTemplate::class.java)) { "DisplayTemplate must support close" }
        val close = DisplayTemplate::class.java.getMethod("close")
        check(close.returnType == Void.TYPE && close.declaringClass == DisplayTemplate::class.java) { "Unexpected DisplayTemplate.close contract" }
        check(TextDraw::class.java.getMethod("getText").returnType.name == "net.kyori.adventure.text.Component")
        check(TextDraw::class.java.getMethod("getKey").returnType == String::class.java) { "SimpleDisplays must support stable draw keys" }
        check(TextDraw::class.java.getMethod("getZIndex").returnType == Int::class.javaPrimitiveType) { "SimpleDisplays must support fixed z-index" }
        for (method in listOf("getScaleX", "getScaleY")) {
            check(TextDraw::class.java.getMethod(method).returnType == Double::class.javaPrimitiveType) { "SimpleDisplays must support independent text scaling" }
        }
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

    override fun openTemplate(context: PartExtensionRenderContext, config: DisplayPartConfig,
        templateId: String, template: DisplayTemplateSource): PartExtensionRenderSession? {
        val subject = "package '${context.packageName}' snapshot '${context.packageHash}' part '${context.part.name}' display template '$templateId' file '${template.file}'"
        try {
            val compiler = synchronized(lifecycle) { service } ?: return null
            val compiled = compiler.compile(template.source)
            val session = Session(subject, config, compiled)
            val accepted = synchronized(lifecycle) {
                if (service === compiler) { sessions.add(session); true } else false
            }
            if (!accepted) { session.close(); return null }
            return session
        } catch (failure: Exception) {
            throw IllegalArgumentException("$subject failed to open display session: ${failure.message ?: failure.javaClass.simpleName}", failure)
        }
    }

    private inner class Session(
        private val subject: String,
        private val config: DisplayPartConfig,
        compiled: DisplayTemplate,
    ) : PartExtensionRenderSession {
        private val state = Any()
        private var template: DisplayTemplate? = compiled
        private var disposed = false
        private var active = false
        private var closeStarted = false
        private val cleanup = CompletableFuture<Void>()

        override fun draw(input: PartExtensionRenderInput): PartExtensionFrame {
            val compiled = synchronized(state) {
                if (disposed) return PartExtensionFrame.EMPTY
                check(!active) { "$subject draw must be serialized" }
                active = true
                checkNotNull(template)
            }
            var primary: Throwable? = null
            val frame = try {
                require(compiled.width.isFinite() && compiled.width > 0 && compiled.height.isFinite() && compiled.height > 0) { "Invalid logical canvas dimensions" }
                val unit = minOf(config.width / compiled.width, config.height / compiled.height)
                require(unit.isFinite() && unit > 0) { "Invalid canvas fitting scale" }
                val data = withDisplayInstallation(input.data, config).entries.associate { it.key to jsonValue(it.value, 0) }
                val draws = compiled.draw(DisplayContext(data = data, elapsedTicks = input.elapsedTicks))
                require(draws.size <= 1024) { "Display frame exceeds 1024 text nodes" }
                PartExtensionFrame(draws.map { draw ->
                    require(draw.x.isFinite() && draw.y.isFinite() && draw.size.isFinite() && draw.size > 0) { "Invalid logical text geometry" }
                    require(draw.scaleX.isFinite() && draw.scaleX > 0 && draw.scaleY.isFinite() && draw.scaleY > 0) { "Invalid logical text scaling" }
                    val x = (draw.x - compiled.width / 2) * unit
                    val y = (compiled.height / 2 - draw.y - draw.size) * unit
                    val scale = draw.size * unit * 40.0 / 9.0
                    val scaleX = scale * draw.scaleX
                    val scaleY = scale * draw.scaleY
                    require(x.isFinite() && y.isFinite() && scale.isFinite() && scale > 0 &&
                        scaleX.isFinite() && scaleX > 0 && scaleY.isFinite() && scaleY > 0) { "Invalid derived physical text geometry" }
                    require(draw.zIndex in 0..63) { "Invalid logical text z-index" }
                    // Absolute logical layers remain fixed when nodes are reordered, added or removed.
                    val z = draw.zIndex / 63.0 * minOf(config.width, config.height) * 0.01
                    PartExtensionText(draw.text, ModelPose(x, y, z, 0.0, 0.0, 0.0, 1.0), ModelScale(scaleX, scaleY, scale), key = draw.key)
                })
            } catch (failure: Throwable) {
                primary = failure
                if (failure !is Exception) throw failure
                throw IllegalArgumentException("$subject failed to draw display frame: ${failure.message ?: failure.javaClass.simpleName}", failure)
            } finally {
                val release = synchronized(state) {
                    active = false
                    claimClose()
                }
                if (release != null) {
                    try { finishClose(release) } catch (failure: Throwable) {
                        if (primary == null) throw failure
                        if (primary !== failure) primary.addSuppressed(failure)
                    }
                }
            }
            return synchronized(state) { if (disposed) PartExtensionFrame.EMPTY else frame }
        }

        /** Retirement never waits for an active guest. Its finally owns deferred cleanup. */
        fun retire(): Boolean {
            val (wasActive, release) = synchronized(state) {
                disposed = true
                active to claimClose()
            }
            release?.let(::finishClose)
            return wasActive
        }

        override fun close() {
            val wasActive = retire()
            if (!wasActive) {
                // Another idle closer may already own the external callback. Host callers are workers.
                try { cleanup.join() } catch (failure: CompletionException) { throw (failure.cause ?: failure) }
            }
        }

        private fun claimClose(): DisplayTemplate? {
            if (!disposed || active || closeStarted) return null
            closeStarted = true
            return template.also { template = null }
        }

        private fun finishClose(release: DisplayTemplate) {
            try {
                release.close()
                cleanup.complete(null)
            } catch (failure: Throwable) {
                cleanup.completeExceptionally(failure)
                throw failure
            } finally {
                synchronized(lifecycle) { sessions.remove(this) }
            }
        }
    }

    private fun jsonValue(value: JsonElement, depth: Int): Any? {
        require(depth <= 32) { "Display data exceeds maximum nesting depth" }
        return when (value) {
            JsonNull -> null
            is JsonObject -> value.entries.associate { it.key to jsonValue(it.value, depth + 1) }
            is JsonArray -> value.map { jsonValue(it, depth + 1) }
            is JsonPrimitive -> when {
                value.isString -> value.content
                value.booleanOrNull != null -> value.booleanOrNull
                else -> requireNotNull(value.doubleOrNull) { "Unsupported display data primitive" }.also {
                    require(it.isFinite()) { "Nonfinite display data number" }
                }
            }
        }
    }

    override fun close() {
        val retired = synchronized(lifecycle) {
            service = null
            sessions.toList()
        }
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        retired.forEach { attempt { it.retire() } }
        attempt { cache.close() }
        failure?.let { throw it }
    }
}
