package work.art1st.javarailways.decorations.display

import work.art1st.javarailways.decorations.api.display.DisplayStyleSelection
import work.art1st.javarailways.decorations.api.display.DisplayStyleService
import java.util.Collections
import java.util.UUID

/** No guest, platform, or external callback is invoked while holding [lock]. */
class RuntimeDisplayStyles(private val onChanged: () -> Unit = {}) : DisplayStyleService, AutoCloseable {
    private data class Key(val worldId: UUID, val unitId: String, val category: String)
    internal class Binding internal constructor(
        val worldId: UUID?, val epoch: Long, val category: String,
        val catalog: Set<String>, val packageIdentity: String, val defaultTemplate: String?,
    ) {
        @Volatile var revision: Long = 0L
            internal set
        internal var unitId: String? = null
    }
    private val lock = Any()
    private val epochs = mutableMapOf<UUID, Long>()
    private val bindings = mutableSetOf<Binding>()
    private val overrides = mutableMapOf<Key, String>()
    private var serial = 0L
    private var closed = false

    internal fun register(worldId: UUID?, category: String, catalog: Set<String>, packageIdentity: String, defaultTemplate: String? = null): Binding = synchronized(lock) {
        check(!closed) { "Display style service is closed" }
        Binding(worldId, worldId?.let { epochs[it] ?: 0L } ?: 0L, category,
            Collections.unmodifiableSet(catalog.toSet()), packageIdentity, defaultTemplate).also { bindings += it }
    }

    internal fun bind(binding: Binding, unitId: String?) {
        val changed = synchronized(lock) {
            if (closed || binding !in bindings || binding.worldId == null ||
                binding.epoch != (epochs[binding.worldId] ?: 0L)) return@synchronized false
            if (binding.unitId == unitId) return@synchronized false
            binding.unitId = unitId
            binding.revision = ++serial
            true
        }
        if (changed) onChanged()
    }

    internal fun unbind(binding: Binding) {
        synchronized(lock) {
            if (bindings.remove(binding)) {
                binding.unitId = null
                binding.revision = ++serial
            }
        }
    }

    internal fun overrideFor(binding: Binding): String? = synchronized(lock) {
        if (closed || binding !in bindings) return@synchronized null
        val world = binding.worldId ?: return@synchronized null
        val unit = binding.unitId ?: return@synchronized null
        overrides[Key(world, unit, binding.category)]
    }

    internal fun isActive(binding: Binding): Boolean = synchronized(lock) { !closed && binding in bindings }

    override fun unitIds(worldId: UUID): Set<String> = synchronized(lock) {
        Collections.unmodifiableSet((bindings.filter { it.worldId == worldId }.mapNotNull { it.unitId } +
            overrides.keys.filter { it.worldId == worldId }.map { it.unitId }).toSortedSet())
    }

    override fun categories(worldId: UUID, unitId: String): Set<String> = synchronized(lock) {
        Collections.unmodifiableSet((bindings.filter { it.worldId == worldId && it.unitId == unitId }.map { it.category } +
            overrides.keys.filter { it.worldId == worldId && it.unitId == unitId }.map { it.category }).toSortedSet())
    }

    override fun inspect(worldId: UUID, unitId: String, category: String): DisplayStyleSelection = synchronized(lock) {
        selection(Key(worldId, unitId, category))
    }

    override fun set(worldId: UUID, unitId: String, category: String, templateId: String): DisplayStyleSelection {
        requireDisplayIdentifier(templateId, "Display template ID")
        var changed = false
        val result = synchronized(lock) {
            check(!closed) { "Display style service is closed" }
            val key = Key(worldId, unitId, category)
            val matching = matching(key)
            require(matching.isNotEmpty()) { "No bound display for unit '$unitId' category '$category'" }
            val missing = matching.filter { templateId !in it.catalog }
            require(missing.isEmpty()) {
                "Template '$templateId' is absent from captured package catalogs: ${missing.map { it.packageIdentity }.distinct().sorted()}"
            }
            if (overrides[key] != templateId) {
                val previous = overrides[key]
                overrides[key] = templateId
                val revision = ++serial
                matching.filter { (previous ?: it.defaultTemplate) != templateId }.forEach { it.revision = revision }
                changed = true
            }
            selection(key)
        }
        if (changed) onChanged()
        return result
    }

    override fun reset(worldId: UUID, unitId: String, category: String): DisplayStyleSelection {
        var changed = false
        val result = synchronized(lock) {
            check(!closed) { "Display style service is closed" }
            val key = Key(worldId, unitId, category)
            val previous = overrides.remove(key)
            if (previous != null) {
                val revision = ++serial
                matching(key).filter { it.defaultTemplate != previous }.forEach { it.revision = revision }
                changed = true
            }
            selection(key)
        }
        if (changed) onChanged()
        return result
    }

    fun clearWorld(worldId: UUID) {
        val changed = synchronized(lock) {
            if (closed) return@synchronized false
            epochs[worldId] = (epochs[worldId] ?: 0L) + 1L
            val removed = bindings.filter { it.worldId == worldId }
            val revision = ++serial
            removed.forEach { it.unitId = null; it.revision = revision }
            bindings.removeAll(removed.toSet())
            val hadOverrides = overrides.keys.removeAll { it.worldId == worldId }
            removed.isNotEmpty() || hadOverrides
        }
        if (changed) onChanged()
    }

    override fun close() {
        val changed = synchronized(lock) {
            if (closed) return@synchronized false
            closed = true
            val revision = ++serial
            bindings.forEach { it.unitId = null; it.revision = revision }
            val any = bindings.isNotEmpty() || overrides.isNotEmpty()
            bindings.clear(); overrides.clear(); epochs.clear()
            any
        }
        if (changed) onChanged()
    }

    private fun matching(key: Key) = bindings.filter {
        it.worldId == key.worldId && it.unitId == key.unitId && it.category == key.category
    }

    private fun selection(key: Key): DisplayStyleSelection {
        val matching = matching(key)
        val common = matching.firstOrNull()?.catalog?.toMutableSet() ?: mutableSetOf()
        matching.drop(1).forEach { common.retainAll(it.catalog) }
        return DisplayStyleSelection(key.worldId, key.unitId, key.category, overrides[key], common, matching.size)
    }
}
