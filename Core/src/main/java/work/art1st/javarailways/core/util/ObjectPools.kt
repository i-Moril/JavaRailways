package work.art1st.javarailways.core.util

import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.ArrayDeque

/**
 * Per-thread object pools for the physics hot path.
 * Intended for short-lived objects that are acquired and released within a single tick.
 */
object ObjectPools {

    // --- Vector3d ---

    fun acquireVector3d(): Vector3d = vecPool.get().let { pool ->
        if (pool.isNotEmpty()) pool.pop()!!.zero() else Vector3d()
    }

    fun release(v: Vector3d) { vecPool.get().push(v) }

    inline fun <R> withVector3d(block: (Vector3d) -> R): R {
        val v = acquireVector3d()
        return try { block(v) } finally { release(v) }
    }

    inline fun <R> withTwoVector3d(block: (Vector3d, Vector3d) -> R): R {
        val a = acquireVector3d(); val b = acquireVector3d()
        return try { block(a, b) } finally { release(b); release(a) }
    }

    // --- Quaterniond ---

    fun acquireQuaterniond(): Quaterniond = quatPool.get().let { pool ->
        if (pool.isNotEmpty()) pool.pop()!!.identity() else Quaterniond()
    }

    fun release(q: Quaterniond) { quatPool.get().push(q) }

    // --- internal ---
    private val vecPool = ThreadLocal.withInitial { ArrayDeque<Vector3d>(64) }
    private val quatPool = ThreadLocal.withInitial { ArrayDeque<Quaterniond>(8) }
}
