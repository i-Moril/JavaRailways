package work.art1st.mineRails.bukkit.behavior

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import work.art1st.mineRails.bukkit.config.model.TrainBehaviorScript

interface TrainBehaviorBindings {
    fun action(name: String): Boolean
    fun sound(key: String, volume: Double = 1.0, pitch: Double = 1.0): Boolean
    fun log(message: String)
}

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
        context.getBindings("js").putMember("__mineRailsTrain", TrainBehaviorApi(bindings))
        context.eval("js", PRELUDE)
        val source = Source.newBuilder("js", script.source, script.fileName).build()
        context.eval(source)
    }

    fun call(method: String, args: List<Any?> = emptyList()): TrainBehaviorCallResult {
        val function = context.getBindings("js").getMember(method)
            ?: return TrainBehaviorCallResult.Failure("行为方法不存在: $method")
        if (!function.canExecute()) {
            return TrainBehaviorCallResult.Failure("行为成员不可执行: $method")
        }
        return try {
            val value = function.execute(*args.toTypedArray())
            TrainBehaviorCallResult.Success(method, value.toKotlinValue())
        } catch (e: PolyglotException) {
            TrainBehaviorCallResult.Failure(formatPolyglotException(method, e))
        } catch (e: Exception) {
            TrainBehaviorCallResult.Failure("行为执行失败 $method: ${e.message ?: e::class.java.simpleName}")
        }
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
    }

    companion object {
        private const val PRELUDE = """
            function action(name) {
              return __mineRailsTrain.action(String(name));
            }
            function sound(key, volume, pitch) {
              return __mineRailsTrain.sound(
                String(key),
                volume === undefined ? 1.0 : Number(volume),
                pitch === undefined ? 1.0 : Number(pitch)
              );
            }
            function log(message) {
              return __mineRailsTrain.log(String(message));
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
