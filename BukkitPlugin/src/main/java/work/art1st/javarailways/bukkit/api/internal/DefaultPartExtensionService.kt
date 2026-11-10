package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.model.extension.*
import work.art1st.javarailways.bukkit.config.model.*
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import work.art1st.javarailways.core.api.util.DestructionSteps

/** Owner registry and package validation boundary. Never invokes plugin code under the registry lock. */
class DefaultPartExtensionService(
    private val reportDiagnostic: (String) -> Unit = {},
    private val reportFailure: (String, Throwable) -> Unit = { _, _ -> }
) : PartExtensionService, AutoCloseable {
    private val lock = Any()
    private val workerSequence = AtomicInteger()
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { action ->
        Thread(action, "JavaRailways-part-prepare-${workerSequence.incrementAndGet()}").apply { isDaemon = true }
    }
    private var shutdown: CompletionStage<Void>? = null
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false
    private var generation = 0L

    internal fun generation(): Long = synchronized(lock) { generation }
    private var packages: (() -> List<LoadedTrainPackage>)? = null

    internal fun bindPackages(supplier: () -> List<LoadedTrainPackage>) = synchronized(lock) {
        check(!closed && packages == null) { "Part extension package supplier is already bound or service is closed" }
        packages = supplier
    }

    override fun register(owner: Plugin, provider: PartExtensionProvider): PartExtensionRegistration {
        require(owner.isEnabled) { "Part extension owner must be enabled" }
        val typeId = provider.typeId
        requirePartExtensionTypeId(typeId)
        val entry = Entry(typeId, owner, provider)
        val supplier = synchronized(lock) {
            check(!closed) { "Part extension service is closed" }
            require(typeId !in entries) { "Part extension type '$typeId' is already registered" }
            entries[typeId] = entry
            generation++
            packages
        }
        val failures = TreeMap<String, String>()
        try {
            for (pkg in supplier?.invoke().orEmpty()) {
                try { validateEntry(entry, pkg) } catch (failure: Exception) {
                    failures[pkg.manifest.name] = failure.message ?: failure.javaClass.simpleName
                    reportFailure("Part extension '$typeId' validation failed for loaded package '${pkg.manifest.name}'", failure)
                }
            }
        } catch (failure: Throwable) {
            retire(entry)
            throw failure
        }
        val capturedFailures: Map<String, String> = Collections.unmodifiableMap(failures)
        return object : PartExtensionRegistration {
            override val existingPackageFailures = capturedFailures
            override fun closeAsync(): CompletionStage<Void> = retire(entry)
        }
    }

    internal fun validatePackage(pkg: LoadedTrainPackage) {
        val captured = synchronized(lock) { if (closed) emptyMap() else entries.toMap() }
        for ((typeId, context) in contexts(pkg)) {
            val entry = captured[typeId]
            if (entry == null) reportDiagnostic("Package '${pkg.manifest.name}' extension '$typeId' has no registered provider at validation; preserving configuration, raw resources and model subtree")
            else validateContext(entry, context)
        }
    }

    private fun validateEntry(entry: Entry, pkg: LoadedTrainPackage) {
        val context = contexts(pkg)[entry.typeId] ?: return
        validateContext(entry, context)
    }

    private fun validateContext(entry: Entry, context: PartExtensionPackage) {
        // Acquire the callback only after preparing detached values. Retirement clears the entry;
        // a callback already acquired may finish, but later validations cannot acquire dead owners.
        val acquired = synchronized(lock) {
            if (closed || entries[entry.typeId] !== entry) null else entry.owner?.let { owner ->
                entry.provider?.let { provider -> owner to provider }
            }
        } ?: return
        if (!acquired.first.isEnabled || entry.owner !== acquired.first || entry.provider !== acquired.second) return
        acquired.second.validate(context)
    }

    internal fun createAttachment(
        typeId: String,
        context: PartExtensionRenderContext,
        retireVisuals: () -> CompletionStage<Void>
    ): PartExtensionAttachment? = synchronized(lock) {
        if (closed) return null
        val entry = entries[typeId] ?: return null
        if (entry.provider !is PartExtensionRenderer) return null
        PartExtensionAttachment(context, workers,
            acquireRenderer = {
                val captured = synchronized(lock) {
                    if (entries[typeId] !== entry || closed) null else entry.owner?.let { it to entry.provider }
                }
                if (captured == null || !captured.first.isEnabled || entry.owner !== captured.first || entry.provider !== captured.second) null
                else captured.second as? PartExtensionRenderer
            },
            retireVisuals = retireVisuals,
            detach = { attachment -> synchronized(lock) { entry.attachments.remove(attachment) } },
            report = reportFailure
        ).also { entry.attachments.add(it) }
    }

    internal fun reportRuntimeFailure(subject: String, failure: Throwable) = reportFailure(subject, failure)

    fun unregisterOwner(owner: Plugin): CompletionStage<Void> {
        val owned = synchronized(lock) { entries.values.filter { it.owner === owner } }
        val steps = DestructionSteps()
        owned.forEach { entry -> steps.await { retire(entry) } }
        return steps.finish()
    }

    private fun retire(entry: Entry): CompletionStage<Void> {
        val attachments = synchronized(lock) {
            entry.cleanup?.let { return it }
            entry.cleanup = CompletableFuture()
            if (entries[entry.typeId] === entry) { entries.remove(entry.typeId); generation++ }
            entry.provider = null
            entry.owner = null
            entry.attachments.toList().also { entry.attachments.clear() }
        }
        val steps = DestructionSteps()
        attachments.forEach { attachment -> steps.await { attachment.closeAsync() } }
        steps.finish().whenComplete { _, failure ->
            if (failure == null) entry.cleanup!!.complete(null) else entry.cleanup!!.completeExceptionally(failure)
        }
        return entry.cleanup!!
    }

    fun closeAsync(): CompletionStage<Void> {
        val owned = synchronized(lock) {
            shutdown?.let { return it }
            shutdown = CompletableFuture<Void>()
            closed = true
            generation++
            packages = null
            entries.values.toList()
        }
        val steps = DestructionSteps()
        owned.forEach { entry -> steps.await { retire(entry) } }
        workers.shutdownNow()
        steps.finish().whenComplete { _, failure ->
            val completion = shutdown as CompletableFuture<Void>
            if (failure == null) completion.complete(null) else completion.completeExceptionally(failure)
        }
        return shutdown!!
    }

    override fun close() {
        closeAsync().whenComplete { _, failure -> if (failure != null) reportFailure("Part extension shutdown cleanup failed", failure) }
    }

    private class Entry(val typeId: String, @Volatile var owner: Plugin?, @Volatile var provider: PartExtensionProvider?) {
        val attachments = mutableSetOf<PartExtensionAttachment>()
        var cleanup: CompletableFuture<Void>? = null
    }

    private fun contexts(pkg: LoadedTrainPackage): Map<String, PartExtensionPackage> {
        val definitions = linkedMapOf<String, MutableList<PartExtensionPart>>()
        val active = Collections.newSetFromMap(IdentityHashMap<PartDefinition, Boolean>())
        fun visit(part: PartDefinition) {
            require(active.add(part)) { "Cyclic extension part reference at '${part.name}'" }
            try {
                part.extension?.let {
                    definitions.getOrPut(it.typeId) { mutableListOf() } += PartExtensionPart(part.name, it.schemaVersion, it.config)
                }
                part.parts.forEach { entry -> entry.inline?.let(::visit) }
            } finally { active.remove(part) }
        }
        // Validate each package's own declared and inline definitions only. Named imports are
        // validated in their source package; unused native reference graphs remain outside this boundary.
        // Occurrences remain a list; names do not deduplicate distinct inline definitions.
        pkg.partRegistry.getAllNames().sorted().forEach { visit(requireNotNull(pkg.partRegistry[it])) }
        for (car in pkg.cars.values) {
            val roots = car.bodyParts + car.bogies.flatMap { it.parts + it.wheelParts }
            roots.forEach { it.inline?.let(::visit) }
        }
        return (definitions.keys + pkg.extensions.keys).associateWith { typeId ->
            PartExtensionPackage(pkg.manifest.name, definitions[typeId].orEmpty(), pkg.extensions[typeId])
        }
    }
}

class PartExtensionOwnerLifecycleListener(private val service: DefaultPartExtensionService) : Listener {
    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        val subject = "Part extension cleanup failed for disabled plugin '${event.plugin.name}'"
        service.unregisterOwner(event.plugin).whenComplete { _, failure ->
            if (failure != null) service.reportRuntimeFailure(subject, failure)
        }
    }
}
