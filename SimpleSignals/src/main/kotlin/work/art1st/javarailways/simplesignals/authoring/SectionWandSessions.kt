package work.art1st.javarailways.simplesignals.authoring

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Player intent only; the backend's captured runtime/publication remains the world authority. */
internal class SectionWandSessions {
    internal class Session(val worldId: UUID, val state: SectionWandState) {
        val active = AtomicBoolean(true)
        val busy = AtomicBoolean(false)
        @Volatile var kind: SectionWandKind = SectionWandKind.PENDING
        @Volatile var refresh: AutoCloseable? = null
        @Volatile var dialogToken: Any? = null
        @Volatile var naming: Boolean = false
        fun close() { active.set(false); refresh?.close() }
    }
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val closed = AtomicBoolean(false)

    fun begin(playerId: UUID, worldId: UUID, state: SectionWandState, permitted: Boolean): Session? {
        if (!permitted || closed.get()) { invalidate(playerId); return null }
        val session = sessions.compute(playerId) { _, old ->
            if (old != null && old.active.get() && old.worldId == worldId && old.state == state) old
            else { old?.close(); Session(worldId, state) }
        }!!
        if (closed.get()) { invalidate(playerId); return null }
        return session.takeIf { it.busy.compareAndSet(false, true) }?.also { it.dialogToken = null }
    }

    fun current(playerId: UUID, session: Session): Boolean =
        !closed.get() && session.active.get() && sessions[playerId] === session

    fun kind(playerId: UUID): SectionWandKind = sessions[playerId]?.kind ?: SectionWandKind.PENDING

    fun invalidate(playerId: UUID) { sessions.remove(playerId)?.close() }
    fun invalidate(playerId: UUID, session: Session) {
        if (sessions.remove(playerId, session)) session.close()
    }
    fun invalidateWorld(worldId: UUID) {
        sessions.entries.forEach { (playerId, session) ->
            if (session.worldId == worldId && sessions.remove(playerId, session)) session.close()
        }
    }
    fun close() { closed.set(true); sessions.keys.forEach(::invalidate) }
}
