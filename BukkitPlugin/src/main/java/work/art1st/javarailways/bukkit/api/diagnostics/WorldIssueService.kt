package work.art1st.javarailways.bukkit.api.diagnostics

import java.time.Instant
import java.util.UUID

enum class WorldIssueKind { WORLD, TRAIN, PROTECTION_REGION, BLOCK, SIGNAL }

data class WorldIssueInput(val kind: WorldIssueKind, val objectId: String, val code: String,
    val message: String, val details: String? = null)

data class WorldIssue(val source: String, val kind: WorldIssueKind, val objectId: String, val code: String,
    val message: String, val details: String?, val firstSeen: Instant, val lastSeen: Instant)

/** Thread-safe current content faults only (invalid or unavailable trains, signals, tracks, etc.).
 * Execution exceptions belong in the disk error log, not here. An exception may also make an object
 * unavailable; report that current object state and resolve it when the object recovers.
 * Rejected edits/reloads that retain healthy content must not create issues.
 * Producers own recovery and removal; this service never changes railway state.
 * World owners clear entries on unload, add-ons clear their source on disable. No history is persisted.
 */
class WorldIssueService {
    private data class Key(val source: String, val kind: WorldIssueKind, val objectId: String, val code: String)
    private val worlds = linkedMapOf<UUID, LinkedHashMap<Key, WorldIssue>>()

    @Synchronized fun report(worldId: UUID, source: String, issue: WorldIssueInput) {
        require(source.isNotBlank() && issue.objectId.isNotBlank() && issue.code.isNotBlank() && issue.message.isNotBlank())
        val entries = worlds.getOrPut(worldId, ::linkedMapOf)
        val key = Key(source, issue.kind, issue.objectId, issue.code)
        val now = Instant.now()
        entries[key] = WorldIssue(source, issue.kind, issue.objectId, issue.code, issue.message,
            issue.details, entries[key]?.firstSeen ?: now, now)
    }

    @Synchronized fun resolve(worldId: UUID, source: String, kind: WorldIssueKind, objectId: String, code: String) {
        worlds[worldId]?.remove(Key(source, kind, objectId, code))
        if (worlds[worldId]?.isEmpty() == true) worlds.remove(worldId)
    }

    /** Atomically replaces only this source's current issues and retains firstSeen for surviving keys. */
    @Synchronized fun replace(worldId: UUID, source: String, issues: Collection<WorldIssueInput>) {
        require(source.isNotBlank())
        val inputs = issues.toList()
        inputs.forEach { require(it.objectId.isNotBlank() && it.code.isNotBlank() && it.message.isNotBlank()) }
        val retained = inputs.mapTo(hashSetOf()) { Key(source, it.kind, it.objectId, it.code) }
        worlds[worldId]?.keys?.removeIf { it.source == source && it !in retained }
        inputs.forEach { report(worldId, source, it) }
        if (worlds[worldId]?.isEmpty() == true) worlds.remove(worldId)
    }

    @Synchronized fun snapshot(worldId: UUID): List<WorldIssue> = java.util.List.copyOf(
        worlds[worldId]?.values.orEmpty().sortedWith(compareBy({ it.kind }, { it.objectId }, { it.source }, { it.code })))

    @Synchronized fun clearWorld(worldId: UUID) { worlds.remove(worldId) }
    @Synchronized fun clearSource(source: String) {
        worlds.values.forEach { entries -> entries.keys.removeIf { it.source == source } }
        worlds.entries.removeIf { it.value.isEmpty() }
    }
}
