package work.art1st.javarailways.bukkit.behavior

import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.bukkit.config.model.TrainActionConfig
import work.art1st.javarailways.bukkit.config.model.TrainBehaviorScript
import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult

/** One car's JS state and accepted effects. All entry points run under the car renderer monitor. */
private class ModelEffectRejectedException(message: String) : RuntimeException(message)

internal class CarBehavior(
    private val script: () -> TrainBehaviorScript?,
    private val actions: () -> Map<String, TrainActionConfig>,
    private val animate: (String, String?) -> java.util.concurrent.CompletableFuture<Unit>,
    private val sound: (String, Double, Double) -> Unit,
    private val schedule: (Double, () -> Unit) -> AutoCloseable,
    private val log: (String) -> Unit,
    private val scriptFailed: (TrainBehaviorScriptException) -> Unit,
    private val hasAnimation: (String, String?) -> Boolean,
    private val closeDuration: (List<String>, List<String>, Map<String, TrainActionConfig>) -> Long,
    private val planningEnvironment: () -> Any
) : AutoCloseable {
    private var runtime: TrainBehaviorRuntime? = null
    private var planSource: TrainBehaviorScript? = null
    private var planEnvironment: Any? = null
    private val doorPlans = mutableMapOf<Triple<DoorSide, Boolean, Boolean>, List<String>>()
    private var loadedScript: TrainBehaviorScript? = null
    private var relativeTail = false
    private var closed = false
    private var initializing = false
    private var collecting: Effects? = null
    private val operations = linkedSetOf<Effects>()
    private val scheduled = linkedSetOf<AutoCloseable>()
    var definitions: List<TrainBehaviorActionDefinition> = emptyList()
        private set

    fun metadata(tail: Boolean): List<TrainBehaviorActionDefinition> = withEnd(tail) {
        try {
            runtimeWithDeclarations()
            definitions
        } catch (failure: TrainBehaviorScriptException) {
            scriptFailed(failure)
            throw failure
        } catch (failure: Exception) {
            throw work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt.halt(
                "Unexpected car behavior metadata host failure", failure)
        }
    }

    fun door(side: DoorSide, opening: Boolean, tail: Boolean, done: (Result<Unit>) -> Unit): TrainModelOperationResult =
        execute(tail, done, requireEffects = true) {
            require(side != DoorSide.NONE) { "door side is NONE" }
            val success = doorPlan(side, opening, tail).map(::action).all { it }
            TrainBehaviorCallResult.Success("door", success)
        }

    /** Isolated declarative planning cannot observe or perturb the persistent behavior Context. */
    fun estimateDoorCloseDurationTicks(side: DoorSide, tail: Boolean): Long {
        check(!closed) { "car behavior is closed" }
        if (side == DoorSide.NONE) return 0L
        return closeDuration(doorPlan(side, true, tail), doorPlan(side, false, tail), actions())
    }

    private fun doorPlan(side: DoorSide, opening: Boolean, tail: Boolean): List<String> {
        val source = script()
        val environment = planningEnvironment()
        if (source != planSource || environment != planEnvironment) {
            doorPlans.clear()
            planSource = source
            planEnvironment = environment
        }
        val key = Triple(side, opening, tail)
        doorPlans[key]?.let { return it }
        val names = if (source == null) {
            val sides = if (side == DoorSide.BOTH) listOf("left", "right") else listOf(side.name.lowercase())
            sides.map { semantic ->
                val physical = if (!tail) semantic else if (semantic == "left") "right" else "left"
                "door-$physical-${if (opening) "open" else "close"}"
            }
        } else {
            val bindings = object : TrainBehaviorBindings {
                private fun prohibited(): Nothing = throw TrainBehaviorScriptException("door planning cannot produce host effects")
                override fun action(name: String): Boolean = prohibited()
                override fun sound(key: String, volume: Double, pitch: Double): Boolean = prohibited()
                override fun log(message: String) = prohibited()
                override fun operatingEnd() = if (tail) "tail" else "head"
                override fun hasAnimation(name: String, scene: String?) = this@CarBehavior.hasAnimation(name, scene)
            }
            TrainBehaviorRuntime(source, bindings).use { planner ->
                val result = planner.call("door", listOf(side.name.lowercase(), if (opening) "open" else "closed"))
                val values = (result as? TrainBehaviorCallResult.Success)?.returnValue as? List<*>
                    ?: throw TrainBehaviorScriptException("door must return an action-name array")
                if (values.any { it !is String }) throw TrainBehaviorScriptException("door action names must be strings")
                values.map { it as String }
            }
        }
        if (names.isEmpty() || names.size > 128 || names.any { it.isBlank() || it.length > 256 })
            throw TrainBehaviorScriptException("door plan must contain 1 to 128 action names")
        names.forEach { name ->
            val config = actions()[name] ?: throw TrainBehaviorScriptException("door action unavailable: $name")
            if (config.animations.isEmpty() && config.sounds.isEmpty())
                throw TrainBehaviorScriptException("door action has no effects: $name")
            config.animations.forEach { animation ->
                if (!animation.at.isFinite() || animation.at < 0 || !hasAnimation(animation.name, animation.scene))
                    throw TrainBehaviorScriptException("door animation unavailable: ${animation.name}")
            }
            if (config.sounds.any { !it.at.isFinite() || it.at < 0 })
                throw TrainBehaviorScriptException("door sound has invalid delay")
        }
        doorPlans[key] = names
        return names
    }

    fun action(name: String, tail: Boolean, done: (Result<Unit>) -> Unit): TrainModelOperationResult =
        execute(tail, done, requireEffects = true) {
            val js = runtimeWithDeclarations()
            if (definitions.any { it.name == name }) requireNotNull(js).runDefinedAction(name)
            else TrainBehaviorCallResult.Success(name, action(name))
        }

    fun call(method: String, args: List<Any?>, tail: Boolean, done: (Result<Unit>) -> Unit, optional: Boolean = false): TrainModelOperationResult =
        execute(tail, done, requireEffects = false) {
            val js = ensureRuntime()
            if (optional) js?.callOptional(method, args) ?: TrainBehaviorCallResult.Success(method)
            else js?.call(method, args) ?: TrainBehaviorCallResult.Failure("car has no behavior.js")
        }

    private fun execute(tail: Boolean, done: (Result<Unit>) -> Unit, requireEffects: Boolean,
                        invoke: () -> TrainBehaviorCallResult): TrainModelOperationResult {
        if (closed) return TrainModelOperationResult.Rejected("car behavior is closed")
        check(collecting == null) { "recursive car behavior operation" }
        val effects = Effects(done)
        operations.add(effects)
        collecting = effects
        val failure = try {
            val result = withEnd(tail, invoke)
            when {
                result is TrainBehaviorCallResult.Failure -> IllegalStateException(result.message)
                result is TrainBehaviorCallResult.Success && result.returnValue == false -> IllegalStateException("behavior rejected operation")
                requireEffects && effects.count == 0 -> IllegalStateException("behavior declared no model effects")
                else -> null
            }
        } catch (failure: TrainBehaviorScriptException) {
            scriptFailed(failure)
            failure
        } catch (failure: Exception) {
            work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt.halt(
                "Unexpected car behavior host failure", failure)
        }
        finally { collecting = null }
        if (failure != null) effects.fail(failure)
        effects.seal()
        return if (failure != null || effects.failure != null) {
            TrainModelOperationResult.Rejected((failure ?: effects.failure)!!.message ?: "car action failed")
        } else TrainModelOperationResult.Accepted
    }

    private fun action(name: String): Boolean {
        if (initializing) throw TrainBehaviorScriptException("model effects are not allowed during behavior initialization")
        if (collecting == null) throw TrainBehaviorScriptException("model effects are not allowed while reading action metadata")
        val config = actions()[name] ?: run {
            collecting?.fail(IllegalStateException("car model action is unavailable: $name"))
            return false
        }
        var applied = false
        config.animations.forEach { animation ->
            applied = effect(animation.at) {
                animate(animation.name, animation.scene)
            } || applied
        }
        config.sounds.forEach { value ->
            applied = effect(value.at) {
                sound(value.key, value.volume, value.pitch)
                java.util.concurrent.CompletableFuture.completedFuture(Unit)
            } || applied
        }
        return applied
    }

    private fun effect(delay: Double, apply: () -> java.util.concurrent.CompletableFuture<Unit>): Boolean {
        if (initializing) throw TrainBehaviorScriptException("model effects are not allowed during behavior initialization")
        val owner = collecting ?: throw TrainBehaviorScriptException("model effects are not allowed while reading action metadata")
        owner.accept()
        val run = {
            if (closed) {
                owner.fail(IllegalStateException("car behavior is closed"))
            } else try {
                apply().whenComplete { _, failure ->
                    if (failure == null) owner.finish()
                    else owner.fail(ModelEffectRejectedException(failure.cause?.message ?: failure.message ?: "model effect failed"))
                }
            } catch (failure: ModelEffectRejectedException) {
                owner.fail(failure)
            } catch (failure: Exception) {
                owner.fail(work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt.halt(
                    "Unexpected car model effect failure", failure))
            }
        }
        if (delay <= 0.0) run() else {
            try {
                lateinit var handle: AutoCloseable
                handle = schedule(delay) {
                    scheduled.remove(handle)
                    run()
                }
                scheduled.add(handle)
            } catch (failure: Exception) {
                owner.fail(failure)
                throw failure
            }
        }
        return owner.failure == null
    }

    private fun runtimeWithDeclarations(): TrainBehaviorRuntime? {
        val previous = runtime
        val current = ensureRuntime()
        if (current != null && current === previous) {
            initializing = true
            try { definitions = current.listActions() } finally { initializing = false }
        }
        return current
    }

    private fun ensureRuntime(): TrainBehaviorRuntime? {
        check(!closed) { "car behavior is closed" }
        val source = script()
        if (source == loadedScript) return runtime
        // Resource reload is distinct from a consist transfer; already accepted physical effects remain.
        runtime?.close()
        runtime = null
        definitions = emptyList()
        loadedScript = null
        if (source == null) return null
        initializing = true
        try {
            val created = TrainBehaviorRuntime(source, object : TrainBehaviorBindings {
                override fun action(name: String) = this@CarBehavior.action(name)
                override fun sound(key: String, volume: Double, pitch: Double) = effect(0.0) {
                    this@CarBehavior.sound(key, volume, pitch)
                    java.util.concurrent.CompletableFuture.completedFuture(Unit)
                }
                override fun log(message: String) = this@CarBehavior.log(message)
                override fun hasAnimation(name: String, scene: String?) = this@CarBehavior.hasAnimation(name, scene)
                override fun operatingEnd() = if (relativeTail) "tail" else "head"
            })
            try {
                definitions = created.listActions()
                runtime = created
                loadedScript = source
            } catch (failure: Exception) {
                try { created.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
            return created
        } finally {
            initializing = false
        }
    }

    private inline fun <T> withEnd(tail: Boolean, call: () -> T): T {
        val previous = relativeTail
        relativeTail = tail
        return try { call() } finally { relativeTail = previous }
    }

    /** Cancel accepted effects and discard script globals while retaining this car owner. */
    fun reset() {
        check(!closed) { "car behavior is closed" }
        clearRuntime("car behavior reset")
    }

    private fun clearRuntime(reason: String) {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (value: Throwable) {
                if (failure == null) failure = value else if (value !== failure) failure.addSuppressed(value)
            }
        }
        operations.toList().forEach { attempt { it.fail(IllegalStateException(reason)) } }
        scheduled.toList().forEach { attempt(it::close) }
        scheduled.clear()
        attempt { runtime?.close() }
        runtime = null
        loadedScript = null
        definitions = emptyList()
        doorPlans.clear()
        planSource = null
        planEnvironment = null
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        clearRuntime("car behavior destroyed")
    }

    private inner class Effects(private val done: (Result<Unit>) -> Unit) {
        var count = 0
        var failure: Exception? = null
        private var pending = 0
        private var sealed = false
        private var terminal = false
        fun accept() { count++; pending++ }
        fun finish() { pending--; complete() }
        fun fail(value: Exception) { if (failure == null) failure = value; complete() }
        fun seal() { sealed = true; complete() }
        private fun complete() {
            if (terminal || (!sealed && !closed) || (failure == null && pending != 0)) return
            terminal = true
            operations.remove(this)
            done(failure?.let { Result.failure(it) } ?: Result.success(Unit))
        }
    }
}
