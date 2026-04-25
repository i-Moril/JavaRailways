package work.art1st.mineRails.core.di

import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module

internal val mineRailsRootScopeQualifier = named("mineRails_root")

private object MineRailsRootScopeMarker

private val mineRailsKoinModule = module {
    scope(mineRailsRootScopeQualifier) {
        scoped { MineRailsRootScopeMarker }
    }
}

private val parentScopeLock = Any()
private var parentScopeInstance: Scope? = null

@Volatile
private var mineRailsModuleLoaded = false

/**
 * 启动 Koin（若尚未启动）并加载 MineRails 所需的 scope 定义；若宿主已启动 Koin，则仅追加模块。
 */
fun ensureMineRailsKoinStarted() {
    synchronized(parentScopeLock) {
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(mineRailsKoinModule)
            }
            mineRailsModuleLoaded = true
        } else if (!mineRailsModuleLoaded) {
            loadKoinModules(mineRailsKoinModule)
            mineRailsModuleLoaded = true
        }
    }
}

/**
 * 所有 CoreWorld 实例共享的父 [Scope]，用于在其下创建 `world_*` 子 scope。
 */
fun mineRailsParentScope(): Scope {
    synchronized(parentScopeLock) {
        parentScopeInstance?.let { return it }
        ensureMineRailsKoinStarted()
        val koin = GlobalContext.get()
        val scope = koin.createScope("mineRails_parent", mineRailsRootScopeQualifier)
        parentScopeInstance = scope
        return scope
    }
}
