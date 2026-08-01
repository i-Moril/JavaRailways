package work.art1st.javarailways.bukkit.http

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One-time, short-lived browser login grants issued only by an in-game command. */
class WebUiLoginTokens(
    private val lifetimeMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(lifetimeMillis in 1_000L..10 * 60_000L)
    }

    data class Grant(
        val playerId: UUID,
        val playerName: String,
        val worldName: String,
        val expiresAtEpochMillis: Long
    )

    private val random = SecureRandom()
    private val grants = ConcurrentHashMap<String, Grant>()

    fun issue(playerId: UUID, playerName: String, worldName: String): Pair<String, Grant> {
        val now = clock()
        grants.entries.removeIf { it.value.expiresAtEpochMillis <= now }
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val grant = Grant(playerId, playerName, worldName, Math.addExact(now, lifetimeMillis))
        grants[hash(token)] = grant
        return token to grant
    }

    /** Atomically consumes a token. Replays and expired tokens fail. */
    fun consume(token: String): Grant? {
        if (token.length !in 32..128) return null
        val grant = grants.remove(hash(token)) ?: return null
        return grant.takeIf { it.expiresAtEpochMillis > clock() }
    }

    private fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
