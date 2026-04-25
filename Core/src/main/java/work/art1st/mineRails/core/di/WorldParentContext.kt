package work.art1st.mineRails.core.di

import org.koin.core.scope.Scope
import work.art1st.mineRails.core.physics.CoreWorld

/**
 * 为 [CoreWorld] 提供运行时绑定能力（例如 Koin 子 scope），持久化解码路径不依赖此接口。
 */
fun interface WorldParentContext {
    fun attachWorld(world: CoreWorld)
}

/**
 * 在 Koin 父 [Scope] 下为每个 [CoreWorld] 创建子 scope 并 [declare] 该 world。
 */
class KoinWorldParentContext(private val parentScope: Scope) : WorldParentContext {
    override fun attachWorld(world: CoreWorld) {
        val childScope = parentScope.getKoin().getOrCreateScope("world_$world", mineRailsRootScopeQualifier)
        childScope.declare(world)
    }
}
