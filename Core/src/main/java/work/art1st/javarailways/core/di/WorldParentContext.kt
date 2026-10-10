package work.art1st.javarailways.core.di

import org.koin.core.scope.Scope
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one runtime attachment created for a [CoreWorld]. Implementations must make [close]
 * idempotent so multiple failure-isolation paths can safely converge on the same attachment.
 */
fun interface WorldContextRegistration : AutoCloseable {
    override fun close()
}

/**
 * 为 [CoreWorld] 提供运行时绑定能力（例如 Koin 子 scope），持久化解码路径不依赖此接口。
 */
fun interface WorldParentContext {
    fun attachWorld(world: CoreWorld): WorldContextRegistration
}

/**
 * 在 Koin 父 [Scope] 下为每个 [CoreWorld] 创建子 scope 并 [declare] 该 world。
 */
class KoinWorldParentContext(private val parentScope: Scope) : WorldParentContext {
    override fun attachWorld(world: CoreWorld): WorldContextRegistration {
        val koin = parentScope.getKoin()
        val childScope = koin.createScope("world_${world.runtimeId}", javaRailwaysRootScopeQualifier)
        try {
            childScope.linkTo(parentScope)
            childScope.declare(world)
        } catch (failure: Throwable) {
            try {
                childScope.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        return KoinWorldContextRegistration(childScope)
    }
}

private class KoinWorldContextRegistration(
    private val childScope: Scope
) : WorldContextRegistration {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) childScope.close()
    }
}
