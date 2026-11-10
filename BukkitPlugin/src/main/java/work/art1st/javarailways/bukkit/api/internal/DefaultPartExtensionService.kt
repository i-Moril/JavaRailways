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

/** Owner registry and package validation boundary. Never invokes plugin code under the registry lock. */
class DefaultPartExtensionService(
    private val reportDiagnostic: (String) -> Unit = {},
    private val reportFailure: (String, Throwable) -> Unit = { _, _ -> }
) : PartExtensionService, AutoCloseable {
    private val lock = Any()
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
            override fun close() { retire(entry) }
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

    fun unregisterOwner(owner: Plugin) {
        synchronized(lock) {
            entries.values.filter { it.owner === owner }.toList().forEach(::retireLocked)
        }
    }

    private fun retire(entry: Entry) = synchronized(lock) { retireLocked(entry) }
    private fun retireLocked(entry: Entry) {
        if (entries[entry.typeId] === entry) { entries.remove(entry.typeId); generation++ }
        entry.provider = null
        entry.owner = null
    }

    override fun close() = synchronized(lock) {
        closed = true
        generation++
        entries.values.toList().forEach(::retireLocked)
        packages = null
    }

    private class Entry(val typeId: String, @Volatile var owner: Plugin?, @Volatile var provider: PartExtensionProvider?)

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
    fun onPluginDisable(event: PluginDisableEvent) { service.unregisterOwner(event.plugin) }
}
