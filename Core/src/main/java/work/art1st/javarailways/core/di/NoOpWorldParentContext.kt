package work.art1st.javarailways.core.di

import work.art1st.javarailways.core.physics.CoreWorld

/**
 * 单元测试等场景：不绑定 Koin 子 scope（持久化已不依赖 Scope）。
 */
object NoOpWorldParentContext : WorldParentContext {
    override fun attachWorld(world: CoreWorld) = Unit
}
