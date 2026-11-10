package work.art1st.javarailways.core.di

import org.koin.core.scope.Scope
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.IdentityHashMap

/**
 * 为 [CoreWorld] 提供运行时绑定能力（例如 Koin 子 scope），持久化解码路径不依赖此接口。
 */
fun interface WorldParentContext {
    fun attachWorld(world: CoreWorld)

    /** Releases this context's runtime references after the world has stopped. */
    fun detachWorld(world: CoreWorld) = Unit
}

/**
 * 在 Koin 父 [Scope] 下为每个 [CoreWorld] 创建子 scope 并 [declare] 该 world。
 */
class KoinWorldParentContext(private val parentScope: Scope) : WorldParentContext {
    private val scopes = IdentityHashMap<CoreWorld, Scope>()

    @Synchronized
    override fun attachWorld(world: CoreWorld) {
        check(!scopes.containsKey(world)) { "World is already attached to this context" }
        scopes[world] = createJavaRailwaysWorldScope(parentScope, world)
    }

    @Synchronized
    override fun detachWorld(world: CoreWorld) {
        val scope = scopes[world] ?: return
        releaseJavaRailwaysWorldScope(scope)
        scopes.remove(world)
    }
}
