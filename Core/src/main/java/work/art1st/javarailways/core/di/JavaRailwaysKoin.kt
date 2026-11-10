package work.art1st.javarailways.core.di

import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

private val runtimeScopeId = UUID.randomUUID().toString()
internal val javaRailwaysRootScopeQualifier = named("javarailways_root_$runtimeScopeId")

private object JavaRailwaysRootScopeMarker

private val javaRailwaysKoinModule = module {
    scope(javaRailwaysRootScopeQualifier) {
        scoped { JavaRailwaysRootScopeMarker }
    }
}

private val parentScopeLock = Any()
private var parentScopeInstance: Scope? = null
private var moduleOwner: Koin? = null
private val worldScopes = Collections.newSetFromMap(IdentityHashMap<Scope, Boolean>())

/** Starts Koin if necessary; a shared host receives only this runtime's module. */
fun ensureJavaRailwaysKoinStarted() {
    synchronized(parentScopeLock) {
        val koin = GlobalContext.getOrNull() ?: startKoin {}.koin
        if (moduleOwner !== koin) {
            check(worldScopes.isEmpty() && parentScopeInstance == null) {
                "Koin host changed while JavaRailways scopes were active"
            }
            koin.loadModules(listOf(javaRailwaysKoinModule))
            moduleOwner = koin
        }
    }
}

/** The parent scope owned by this JavaRailways runtime. */
fun javaRailwaysParentScope(): Scope {
    synchronized(parentScopeLock) {
        parentScopeInstance?.let { return it }
        ensureJavaRailwaysKoinStarted()
        return moduleOwner!!.createScope("javarailways_parent_$runtimeScopeId", javaRailwaysRootScopeQualifier)
            .also { parentScopeInstance = it }
    }
}

internal fun createJavaRailwaysWorldScope(parent: Scope, world: CoreWorld): Scope = synchronized(parentScopeLock) {
    parent.getKoin().createScope("world_${world.runtimeId}", javaRailwaysRootScopeQualifier).also { scope ->
        try {
            scope.declare(world)
            worldScopes.add(scope)
        } catch (failure: Throwable) {
            scope.close()
            throw failure
        }
    }
}

internal fun releaseJavaRailwaysWorldScope(scope: Scope) {
    synchronized(parentScopeLock) {
        scope.close()
        worldScopes.remove(scope)
    }
}

/**
 * Releases only JavaRailways-owned definitions/scopes, after every world has detached.
 * The shared global Koin host is deliberately left running. Repeated release is safe.
 */
fun releaseJavaRailwaysKoinRuntime() {
    synchronized(parentScopeLock) {
        check(worldScopes.isEmpty()) { "Cannot release JavaRailways Koin runtime while worlds are attached" }
        parentScopeInstance?.close()
        parentScopeInstance = null
        moduleOwner?.unloadModules(listOf(javaRailwaysKoinModule))
        moduleOwner = null
    }
}
