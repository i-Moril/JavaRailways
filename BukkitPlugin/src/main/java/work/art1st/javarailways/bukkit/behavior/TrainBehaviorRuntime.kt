package work.art1st.javarailways.bukkit.behavior

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import work.art1st.javarailways.bukkit.config.model.TrainBehaviorScript

class TrainBehaviorScriptException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface TrainBehaviorBindings {
    fun action(name: String): Boolean
    fun sound(key: String, volume: Double = 1.0, pitch: Double = 1.0): Boolean
    fun log(message: String)
    fun operatingEnd(): String = "head"
    fun hasAnimation(name: String, scene: String?): Boolean
}

data class TrainBehaviorActionDefinition(
    val name: String,
    val displayName: String,
    val executable: Boolean = true,
    val requiresControl: Boolean = false
)

sealed class TrainBehaviorCallResult {
    data class Success(val method: String, val returnValue: Any? = null) : TrainBehaviorCallResult()
    data class Failure(val message: String) : TrainBehaviorCallResult()
}

class TrainBehaviorRuntime(
    private val script: TrainBehaviorScript,
    bindings: TrainBehaviorBindings
) : AutoCloseable {
    private val context: Context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.EXPLICIT)
        .allowHostClassLookup { false }
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowIO(IOAccess.NONE)
        .option("js.ecmascript-version", "2023")
        .build()

    init {
        try {
            context.getBindings("js").putMember("__javaRailwaysTrain", TrainBehaviorApi(bindings))
            context.eval("js", PRELUDE)
            val source = Source.newBuilder("js", script.source, script.fileName).build()
            context.eval(source)
        } catch (failure: Exception) {
            try { context.close(true) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            if (failure is PolyglotException) {
                if (failure.isHostException) throw failure.asHostException()
                throw TrainBehaviorScriptException(formatLoadException(script, failure), failure)
            }
            throw failure
        }
    }

    fun listActions(): List<TrainBehaviorActionDefinition> = evaluateGuest("listActions") {
        val function = context.getBindings("js").getMember("__javaRailwaysListActions")
        if (function == null || !function.canExecute()) return@evaluateGuest emptyList()
        val value = function.execute()
        if (!value.hasArrayElements()) return@evaluateGuest emptyList()
        (0 until value.arraySize).mapNotNull { index ->
            val entry = value.getArrayElement(index)
            val name = entry.getMember("name")?.takeIf { it.isString }?.asString() ?: return@mapNotNull null
            val displayName = entry.getMember("displayName")?.takeIf { it.isString }?.asString() ?: name
            val executable = entry.getMember("executable")?.takeIf { it.isBoolean }?.asBoolean() ?: true
            val requiresControl = entry.getMember("requiresControl")?.takeIf { it.isBoolean }?.asBoolean() ?: false
            TrainBehaviorActionDefinition(name, displayName, executable, requiresControl)
        }
    }

    fun runDefinedAction(name: String): TrainBehaviorCallResult = call("__javaRailwaysRunAction", listOf(name))

    fun callOptional(method: String, args: List<Any?>): TrainBehaviorCallResult = evaluateGuest(method) {
        if (!context.getBindings("js").hasMember(method)) TrainBehaviorCallResult.Success(method)
        else call(method, args)
    }

    fun call(method: String, args: List<Any?> = emptyList()): TrainBehaviorCallResult = evaluateGuest(method) {
        val function = context.getBindings("js").getMember(method)
            ?: return@evaluateGuest TrainBehaviorCallResult.Failure("行为方法不存在: $method")
        if (!function.canExecute()) return@evaluateGuest TrainBehaviorCallResult.Failure("行为成员不可执行: $method")
        val value = function.execute(*args.toTypedArray())
        TrainBehaviorCallResult.Success(method, value.toKotlinValue())
    }

    private inline fun <T> evaluateGuest(method: String, action: () -> T): T = try {
        action()
    } catch (failure: PolyglotException) {
        if (failure.isHostException) throw failure.asHostException()
        throw TrainBehaviorScriptException(formatPolyglotException(method, failure), failure)
    }

    override fun close() {
        context.close(true)
    }

    class TrainBehaviorApi(
        private val bindings: TrainBehaviorBindings
    ) {
        @HostAccess.Export
        fun action(name: String): Boolean = bindings.action(name)

        @HostAccess.Export
        fun sound(key: String, volume: Double, pitch: Double): Boolean = bindings.sound(key, volume, pitch)

        @HostAccess.Export
        fun log(message: String) {
            bindings.log(message)
        }

        @HostAccess.Export
        fun hasAnimation(name: String, scene: String?): Boolean = bindings.hasAnimation(name, scene)

        @HostAccess.Export
        fun operatingEnd(): String = bindings.operatingEnd()
    }

    companion object {
        private const val PRELUDE = """
            function action(name) {
              return __javaRailwaysTrain.action(String(name));
            }
            function sound(key, volume, pitch) {
              return __javaRailwaysTrain.sound(
                String(key),
                volume === undefined ? 1.0 : Number(volume),
                pitch === undefined ? 1.0 : Number(pitch)
              );
            }
            const __javaRailwaysDefinedActions = Object.create(null);
            function log(message) {
              return __javaRailwaysTrain.log(String(message));
            }
            function hasAnimation(name, scene) {
              return __javaRailwaysTrain.hasAnimation(String(name), scene == null ? null : String(scene));
            }
            function operatingEnd() {
              return String(__javaRailwaysTrain.operatingEnd());
            }
            function defineAction(name, displayNameOrOptions, handler) {
              const key = String(name);
              let options = {};
              let fn = handler;
              if (typeof displayNameOrOptions === 'function') {
                fn = displayNameOrOptions;
              } else if (typeof displayNameOrOptions === 'string') {
                options.displayName = displayNameOrOptions;
              } else if (displayNameOrOptions && typeof displayNameOrOptions === 'object') {
                options = displayNameOrOptions;
              }
              if (typeof fn !== 'function') throw new Error('defineAction ' + key + ': handler must be a function');
              __javaRailwaysDefinedActions[key] = {
                name: key,
                displayName: String(options.displayName || options['display-name'] || key),
                executable: options.executable === undefined ? true : Boolean(options.executable),
                requiresControl: Boolean(options.requiresControl || options['requires-control']),
                handler: fn
              };
            }
            function __javaRailwaysListActions() {
              return Object.values(__javaRailwaysDefinedActions).map(function(action) {
                return {
                  name: action.name,
                  displayName: action.displayName,
                  executable: action.executable,
                  requiresControl: action.requiresControl
                };
              });
            }
            function __javaRailwaysRunAction(name) {
              const action = __javaRailwaysDefinedActions[String(name)];
              if (!action) return false;
              return action.handler() !== false;
            }
        """

        fun formatLoadException(script: TrainBehaviorScript, e: Exception): String {
            if (e is PolyglotException) {
                val location = e.sourceLocation
                val prefix = if (location != null) {
                    "${script.fileName}:${location.startLine}:${location.startColumn}"
                } else {
                    script.fileName
                }
                val kind = if (e.isSyntaxError) "JS 语法错误" else "JS 执行错误"
                return "$kind at $prefix: ${e.message}"
            }
            return "行为脚本加载失败 ${script.fileName}: ${e.message ?: e::class.java.simpleName}"
        }

        private fun formatPolyglotException(method: String, e: PolyglotException): String {
            val location = e.sourceLocation
            val suffix = if (location != null) {
                " at ${location.source.name}:${location.startLine}:${location.startColumn}"
            } else {
                ""
            }
            val kind = if (e.isSyntaxError) "JS 语法错误" else "JS 执行错误"
            return "$kind in $method$suffix: ${e.message}"
        }
    }
}

private fun Value.toKotlinValue(): Any? {
    if (isNull) return null
    if (isBoolean) return asBoolean()
    if (fitsInInt()) return asInt()
    if (fitsInLong()) return asLong()
    if (fitsInDouble()) return asDouble()
    if (isString) return asString()
    return toString()
}
