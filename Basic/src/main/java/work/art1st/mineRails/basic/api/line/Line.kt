package work.art1st.mineRails.basic.api.line

import work.art1st.mineRails.basic.api.station.StationId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Stable, world-local identity for a reusable passenger line. */
data class LineId(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl))
    }
    override fun toString(): String = value
}

/** A reusable ordered station pattern. Berths and physical routes are resolved from live infrastructure. */
@ConsistentCopyVisibility
data class Line private constructor(
    val id: LineId,
    val displayName: String,
    val stationIds: List<StationId>,
    val color: String? = null
) {
    constructor(
        id: LineId,
        displayName: String,
        stationIds: Collection<StationId>,
        color: String? = null
    ) : this(id, displayName, Collections.unmodifiableList(ArrayList(stationIds)), color)

    init {
        require(displayName.isNotBlank() && displayName == displayName.trim())
        require(stationIds.size in 2..128) { "Line must contain between 2 and 128 stations" }
        require(stationIds.distinct().size == stationIds.size) { "Line stations must not repeat" }
        require(color == null || color.matches(Regex("#[0-9a-fA-F]{6}"))) {
            "Line color must be #RRGGBB"
        }
    }
}

data class LineStoreSnapshot(val revision: Long, val lines: List<Line>)
class LineRevisionConflict(val expected: Long, val actual: Long) :
    IllegalStateException("Line revision conflict: expected $expected, actual $actual")

/** Revisioned line repository. Mutations are atomic and suitable for HTTP If-Match protection. */
class LineStore {
    private val lines = ConcurrentHashMap<LineId, Line>()
    private val revision = AtomicLong(0L)
    private val lock = Any()

    operator fun get(id: LineId): Line? = lines[id]
    fun snapshot(): LineStoreSnapshot = synchronized(lock) {
        LineStoreSnapshot(revision.get(), lines.values.sortedBy { it.id.value })
    }

    fun put(line: Line, expectedRevision: Long): Long = synchronized(lock) {
        checkRevision(expectedRevision)
        lines[line.id] = line
        revision.incrementAndGet()
    }

    fun remove(id: LineId, expectedRevision: Long): Pair<Line?, Long> = synchronized(lock) {
        checkRevision(expectedRevision)
        val removed = lines.remove(id)
        removed to if (removed == null) revision.get() else revision.incrementAndGet()
    }

    /** Persistence-only restore into a fresh world. */
    fun restore(values: Collection<Line>, savedRevision: Long) = synchronized(lock) {
        require(savedRevision >= 0L)
        check(lines.isEmpty() && revision.get() == 0L) { "LineStore is not empty" }
        values.forEach { line -> check(lines.put(line.id, line) == null) { "Duplicate line: ${line.id}" } }
        revision.set(savedRevision)
    }

    private fun checkRevision(expected: Long) {
        val actual = revision.get()
        if (expected != actual) throw LineRevisionConflict(expected, actual)
    }
}
