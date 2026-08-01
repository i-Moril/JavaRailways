package work.art1st.javarailways.core.di

import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module

internal val javaRailwaysRootScopeQualifier = named("javarailways_root")

private object JavaRailwaysRootScopeMarker

private val javaRailwaysKoinModule = module {
    scope(javaRailwaysRootScopeQualifier) {
        scoped { JavaRailwaysRootScopeMarker }
    }
}

private val parentScopeLock = Any()
private var parentScopeInstance: Scope? = null

@Volatile
private var javaRailwaysModuleLoaded = false

/**
 * 启动 Koin（若尚未启动）并加载 JavaRailways 所需的 scope 定义；若宿主已启动 Koin，则仅追加模块。
 */
fun ensureJavaRailwaysKoinStarted() {
    synchronized(parentScopeLock) {
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(javaRailwaysKoinModule)
            }
            javaRailwaysModuleLoaded = true
        } else if (!javaRailwaysModuleLoaded) {
            loadKoinModules(javaRailwaysKoinModule)
            javaRailwaysModuleLoaded = true
        }
    }
}

/**
 * 所有 CoreWorld 实例共享的父 [Scope]，用于在其下创建 `world_*` 子 scope。
 */
fun javaRailwaysParentScope(): Scope {
    synchronized(parentScopeLock) {
        parentScopeInstance?.let { return it }
        ensureJavaRailwaysKoinStarted()
        val koin = GlobalContext.get()
        val scope = koin.createScope("javarailways_parent", javaRailwaysRootScopeQualifier)
        parentScopeInstance = scope
        return scope
    }
}
