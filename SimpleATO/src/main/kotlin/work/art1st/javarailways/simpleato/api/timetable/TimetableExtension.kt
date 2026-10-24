package work.art1st.javarailways.simpleato.api.timetable

import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.core.api.util.annotation.MainThread
import java.util.concurrent.ConcurrentHashMap
import work.art1st.javarailways.core.api.physics.World

/**
 * Serializable reference to a condition or action implementation registered by a plugin.
 * Parameters and all nested JSON backing collections are immutable once used in a validated,
 * installed or captured timetable. Providers must treat them as read-only; editing creates a new
 * call rather than mutating maps retained by an existing call.
 */
data class TimetableExtensionCall(
    val typeId: String,
    val version: Int = 1,
    val parameters: JsonObject = JsonObject(emptyMap())
) {
    init {
        require(TYPE_ID.matches(typeId)) {
            "extension typeId must be namespaced (namespace:name): $typeId"
        }
        require(version > 0) { "extension version must be positive" }
    }

    private companion object {
        val TYPE_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}

/** Serializable boolean expression. Predicate leaves are supplied by registered plugins. */
sealed interface TimetableCondition {
    data class Predicate(val call: TimetableExtensionCall) : TimetableCondition
    data class All(val conditions: List<TimetableCondition>) : TimetableCondition {
        init { require(conditions.isNotEmpty()) }
    }
    data class Any(val conditions: List<TimetableCondition>) : TimetableCondition {
        init { require(conditions.isNotEmpty()) }
    }
    data class Not(val condition: TimetableCondition) : TimetableCondition
}

enum class TimetableActivationFalseBehavior {
    SKIP_CYCLE,
    WAIT_UNTIL_TRUE
}

sealed interface TimetableServiceActivation {
    data object Scheduled : TimetableServiceActivation
    data object Manual : TimetableServiceActivation
    data class Conditional(
        val condition: TimetableCondition,
        val falseBehavior: TimetableActivationFalseBehavior
    ) : TimetableServiceActivation
}

/** Stable, read-only train data exposed to extension providers. */
class TimetableTrainContext(
    val trainId: String,
    val modelPackageId: String? = null,
    tags: Set<String> = emptySet(),
    capabilities: Set<String> = emptySet(),
    val carCount: Int? = null
) {
    val tags: Set<String> = tags.toSet()
    val capabilities: Set<String> = capabilities.toSet()

    init {
        require(trainId.isNotBlank())
        require(modelPackageId == null || modelPackageId.isNotBlank())
        require(carCount == null || carCount > 0)
    }
}

/** One immutable observation passed to a condition or action provider on the Dispatcher thread. */
data class TimetableRuntimeContext(
    val timetableId: String,
    val serviceId: String,
    val cycleIndex: Long,
    val timetableElapsedTicks: Long,
    val scheduledTick: Long?,
    val latenessTicks: Long,
    val currentWayPointId: String?,
    val train: TimetableTrainContext?
) {
    init {
        require(timetableId.isNotBlank() && serviceId.isNotBlank())
        require(cycleIndex >= 0L)
        require(timetableElapsedTicks >= 0L)
        require(scheduledTick == null || scheduledTick >= 0L)
        require(latenessTicks >= 0L)
    }
}

sealed interface TimetableConditionResult {
    data object True : TimetableConditionResult
    data object False : TimetableConditionResult
    data class Unavailable(val reason: String) : TimetableConditionResult {
        init { require(reason.isNotBlank()) }
    }
    data class Error(val message: String) : TimetableConditionResult {
        init { require(message.isNotBlank()) }
    }
}

/** Metadata is deliberately JSON so a future WebUI can render plugin-defined forms without plugin JS. */
data class TimetableExtensionDescriptor(
    val title: String,
    val description: String,
    val parameterSchema: JsonObject = JsonObject(emptyMap())
) {
    init {
        require(title.isNotBlank())
        require(description.isNotBlank())
    }
}

enum class TimetableConditionScope {
    SERVICE_ACTIVATION,
    RUNTIME_BRANCH
}

interface TimetableConditionProvider {
    val typeId: String
    val version: Int
    val descriptor: TimetableExtensionDescriptor
    val supportedScopes: Set<TimetableConditionScope>
        get() = TimetableConditionScope.entries.toSet()

    /** Return user-facing validation errors. This must not perform blocking I/O. */
    fun validate(parameters: JsonObject): List<String> = emptyList()

    /** Must be deterministic for the supplied immutable context and must not block. */
    @MainThread
    fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext): TimetableConditionResult
}

enum class TimetableExtensionActionState {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

data class TimetableExtensionActionSnapshot(
    val state: TimetableExtensionActionState,
    val message: String? = null
) {
    init {
        require(message == null || message.isNotBlank())
        require(state == TimetableExtensionActionState.FAILED || message == null) {
            "only failed extension actions may carry a message"
        }
    }
}

/** Main-thread ticket for asynchronous plugin actions. Cancellation must be idempotent. */
interface TimetableExtensionActionTicket {
    @MainThread
    fun snapshot(): TimetableExtensionActionSnapshot

    @MainThread
    fun cancel()
}

sealed interface TimetableExtensionActionStartResult {
    data class Accepted(val ticket: TimetableExtensionActionTicket) : TimetableExtensionActionStartResult
    data class Rejected(val reason: String) : TimetableExtensionActionStartResult {
        init { require(reason.isNotBlank()) }
    }
}

/** Source-world provider capable of capturing detached condition definitions for simulation. */
fun interface TimetableConditionSimulationFactory {
    /**
     * Called in the source World's stable owner boundary before worker submission. The returned
     * preparation may retain immutable definitions, but no live World, provider state or callbacks.
     */
    fun prepareSimulation(): TimetableConditionSimulationPreparation
}

interface TimetableActionProvider {
    val typeId: String
    val version: Int
    val descriptor: TimetableExtensionDescriptor

    fun validate(parameters: JsonObject): List<String> = emptyList()

    /** Called exactly once when the Dispatcher reaches the action. */
    @MainThread
    fun start(
        parameters: JsonObject,
        context: TimetableRuntimeContext
    ): TimetableExtensionActionStartResult
}

/** Captures detached definitions used to construct a fresh action provider in each target World. */
fun interface TimetableActionSimulationFactory {
    /** Captures immutable inputs under the source World owner; must not retain live mutable state. */
    fun prepareSimulation(): TimetableActionSimulationPreparation
}

/** Captured definitions create a new provider for every target World. They must not retain live state. */
fun interface TimetableConditionSimulationPreparation {
    /** Creates a fresh provider bound to [world] while its target owner is installing domain state. */
    fun create(world: World): TimetableConditionProvider
}

/** Detached action definitions, reusable across targets without sharing provider runtime state. */
fun interface TimetableActionSimulationPreparation {
    /** Creates a fresh provider for [world] under its target owner; never returns a live provider. */
    fun create(world: World): TimetableActionProvider
}

/** Captured extension preparations; every target receives a new registry and new provider instances. */
class TimetableSimulationExtensions internal constructor(
    private val conditions: List<TimetableConditionSimulationPreparation>,
    private val actions: List<TimetableActionSimulationPreparation>
) {
    /** Installs into the target [world] under its owner before physics starts. */
    fun create(world: World): TimetableExtensionRegistry =
        TimetableExtensionRegistry().also { registry ->
            conditions.forEach { registry.registerCondition(it.create(world)) }
            actions.forEach { registry.registerAction(it.create(world)) }
        }
}

/** Thread-safe registry. Duplicate type/version registrations fail instead of silently replacing plugins. */
class TimetableExtensionRegistry(registerBuiltIns: Boolean = true) {
    private data class Key(val typeId: String, val version: Int)

    private val conditions = ConcurrentHashMap<Key, TimetableConditionProvider>()
    private val actions = ConcurrentHashMap<Key, TimetableActionProvider>()
    private val builtInConditions: Map<Key, TimetableConditionProvider>

    init {
        if (registerBuiltIns) registerJavaRailwaysConditions()
        builtInConditions = conditions.toMap()
    }

    fun registerCondition(provider: TimetableConditionProvider) {
        val key = checkedKey(provider.typeId, provider.version)
        check(conditions.putIfAbsent(key, provider) == null) {
            "timetable condition already registered: ${provider.typeId}@${provider.version}"
        }
    }

    fun unregisterCondition(typeId: String, version: Int = 1) {
        conditions.remove(checkedKey(typeId, version))
    }

    fun condition(typeId: String, version: Int = 1): TimetableConditionProvider? =
        conditions[checkedKey(typeId, version)]

    fun conditionProviders(): List<TimetableConditionProvider> =
        conditions.entries.sortedWith(compareBy({ it.key.typeId }, { it.key.version })).map { it.value }

    fun registerAction(provider: TimetableActionProvider) {
        val key = checkedKey(provider.typeId, provider.version)
        check(actions.putIfAbsent(key, provider) == null) {
            "timetable action already registered: ${provider.typeId}@${provider.version}"
        }
    }

    fun unregisterAction(typeId: String, version: Int = 1) {
        actions.remove(checkedKey(typeId, version))
    }

    fun action(typeId: String, version: Int = 1): TimetableActionProvider? =
        actions[checkedKey(typeId, version)]

    fun actionProviders(): List<TimetableActionProvider> =
        actions.entries.sortedWith(compareBy({ it.key.typeId }, { it.key.version })).map { it.value }

    /**
     * Captures definitions in the source World's stable owner boundary before worker submission.
     * Preparations must retain no live mutable state; missing domain factories fail immediately.
     */
    fun prepareSimulation(): TimetableSimulationExtensions {
        val preparedConditions = conditionProviders().mapNotNull { provider ->
            if (builtInConditions[Key(provider.typeId, provider.version)] === provider) return@mapNotNull null
            require(provider is TimetableConditionSimulationFactory) {
                "simulation preparation unavailable for condition ${provider.typeId}@${provider.version}"
            }
            provider.prepareSimulation()
        }
        val preparedActions = actionProviders().map { provider ->
            require(provider is TimetableActionSimulationFactory) {
                "simulation preparation unavailable for action ${provider.typeId}@${provider.version}"
            }
            provider.prepareSimulation()
        }
        return TimetableSimulationExtensions(preparedConditions, preparedActions)
    }

    fun validateCondition(
        condition: TimetableCondition,
        scope: TimetableConditionScope? = null
    ): List<String> = when (condition) {
        is TimetableCondition.Predicate -> validateConditionCall(condition.call, scope)
        is TimetableCondition.All -> condition.conditions.flatMap { validateCondition(it, scope) }
        is TimetableCondition.Any -> condition.conditions.flatMap { validateCondition(it, scope) }
        is TimetableCondition.Not -> validateCondition(condition.condition, scope)
    }

    @MainThread
    fun evaluate(
        condition: TimetableCondition,
        context: TimetableRuntimeContext,
        scope: TimetableConditionScope? = null
    ): TimetableConditionResult = when (condition) {
        is TimetableCondition.Predicate -> evaluateCall(condition.call, context, scope)
        is TimetableCondition.Not -> when (
            val result = evaluate(condition.condition, context, scope)
        ) {
            TimetableConditionResult.True -> TimetableConditionResult.False
            TimetableConditionResult.False -> TimetableConditionResult.True
            is TimetableConditionResult.Unavailable -> result
            is TimetableConditionResult.Error -> result
        }
        is TimetableCondition.All -> evaluateAll(condition.conditions, context, scope)
        is TimetableCondition.Any -> evaluateAny(condition.conditions, context, scope)
    }

    private fun validateConditionCall(
        call: TimetableExtensionCall,
        scope: TimetableConditionScope?
    ): List<String> {
        val provider = condition(call.typeId, call.version)
            ?: return listOf("condition provider is not registered: ${call.typeId}@${call.version}")
        if (scope != null && scope !in provider.supportedScopes) {
            return listOf("condition ${call.typeId}@${call.version} does not support $scope")
        }
        return runCatching { provider.validate(call.parameters) }.getOrElse {
            listOf("condition provider ${call.typeId}@${call.version} validation failed: ${it.message}")
        }
    }

    private fun evaluateCall(
        call: TimetableExtensionCall,
        context: TimetableRuntimeContext,
        scope: TimetableConditionScope?
    ): TimetableConditionResult {
        val provider = condition(call.typeId, call.version)
            ?: return TimetableConditionResult.Unavailable(
                "condition provider is not registered: ${call.typeId}@${call.version}"
            )
        if (scope != null && scope !in provider.supportedScopes) {
            return TimetableConditionResult.Error(
                "condition ${call.typeId}@${call.version} does not support $scope"
            )
        }
        return runCatching { provider.evaluate(call.parameters, context) }.getOrElse {
            TimetableConditionResult.Error(
                "condition provider ${call.typeId}@${call.version} failed: " +
                    (it.message ?: it::class.java.simpleName)
            )
        }
    }

    private fun evaluateAll(
        values: List<TimetableCondition>,
        context: TimetableRuntimeContext,
        scope: TimetableConditionScope?
    ): TimetableConditionResult {
        var deferred: TimetableConditionResult? = null
        for (condition in values) {
            when (val result = evaluate(condition, context, scope)) {
                TimetableConditionResult.False -> return result
                TimetableConditionResult.True -> Unit
                is TimetableConditionResult.Error -> return result
                is TimetableConditionResult.Unavailable -> if (deferred == null) deferred = result
            }
        }
        return deferred ?: TimetableConditionResult.True
    }

    private fun evaluateAny(
        values: List<TimetableCondition>,
        context: TimetableRuntimeContext,
        scope: TimetableConditionScope?
    ): TimetableConditionResult {
        var unavailable: TimetableConditionResult.Unavailable? = null
        for (condition in values) {
            when (val result = evaluate(condition, context, scope)) {
                TimetableConditionResult.True -> return result
                TimetableConditionResult.False -> Unit
                is TimetableConditionResult.Error -> return result
                is TimetableConditionResult.Unavailable -> if (unavailable == null) unavailable = result
            }
        }
        return unavailable ?: TimetableConditionResult.False
    }

    private fun checkedKey(typeId: String, version: Int): Key {
        TimetableExtensionCall(typeId, version)
        return Key(typeId, version)
    }
}
