package work.art1st.javarailways.simplesignals.signalpack

import kotlinx.serialization.json.JsonElement
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import java.io.OutputStream

data class SignalLogicOutput(val speedLimitMetresPerSecond: Double, val modelVariant: String)

class SignalLogicException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** A serial, explicitly-owned GraalJS engine for one immutable prepared catalog. */
class SignalLogicRuntime(catalog: SignalPackCatalog) : AutoCloseable {
    private val engine = Engine.newBuilder()
        .out(DISCARD_OUTPUT)
        .err(DISCARD_OUTPUT)
        .build()
    private val sources: Map<String, Source>
    private var closed = false

    init {
        try {
            sources = catalog.logics.values.associate { logic -> logic.id to source(logic) }
            // Reject syntax errors and a missing entry point before this candidate can be installed.
            for ((logicId, source) in sources) {
                withTemporaryContext { context -> compile(context, logicId, source) }
            }
        } catch (error: Exception) {
            engine.close()
            throw ruleFailure("cannot prepare signal logic runtime", error)
        }
    }

    @Synchronized
    fun evaluate(logicId: String, input: JsonElement): SignalLogicOutput {
        checkOpen()
        val source = sources[logicId] ?: throw SignalLogicException("unknown signal logic '$logicId'")
        val output = OutputBridge(logicId)
        try {
            // A fresh realm per evaluation prevents closure and globalThis state from crossing devices/calls.
            // The shared Engine/Source still lets Graal reuse parsed/compiled code.
            withTemporaryContext { context ->
                val invoke = context.eval("js", INVOKE_FACTORY)
                val evaluator = compile(context, logicId, source)
                invoke.execute(evaluator, input.toString(), output)
            }
        } catch (error: Exception) {
            throw ruleFailure("signal logic '$logicId' failed", error)
        }
        return output.finish()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        engine.close()
    }

    private fun source(logic: SignalPackLogic): Source {
        val wrapped = """
            (() => {
              "use strict";
              ${logic.source}
              if (typeof evaluate !== "function") {
                throw new Error("logic must define function evaluate(input, output)");
              }
              return evaluate;
            })()
        """.trimIndent()
        return Source.newBuilder("js", wrapped, logic.file.toString()).cached(true).build()
    }

    private fun compile(context: Context, logicId: String, source: Source): Value {
        val evaluator = context.eval(source)
        if (!evaluator.canExecute()) throw SignalLogicException("signal logic '$logicId' evaluate is not executable")
        return evaluator
    }

    private fun context(): Context = Context.newBuilder("js")
        .engine(engine)
        .allowHostAccess(HOST_ACCESS)
        .allowHostClassLookup { false }
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowIO(IOAccess.NONE)
        .allowNativeAccess(false)
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .allowEnvironmentAccess(EnvironmentAccess.NONE)
        .out(DISCARD_OUTPUT)
        .err(DISCARD_OUTPUT)
        .option("js.ecmascript-version", "2023")
        .option("js.unhandled-rejections", "throw")
        .build()

    private inline fun <T> withTemporaryContext(operation: (Context) -> T): T {
        val context = context()
        return try {
            operation(context)
        } finally {
            // Async work is outside the synchronous rule contract. Force-close the disposable realm
            // so pending guest jobs are cancelled instead of making ordinary Context.close() wait.
            context.close(true)
        }
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("signal logic runtime is closed")
    }

    internal class OutputBridge(private val logicId: String) {
        private var speed: Double? = null
        private var variant: String? = null

        @HostAccess.Export
        fun setSpeedLimit(metresPerSecond: Double) {
            if (speed != null) throw SignalLogicException("signal logic '$logicId' called setSpeedLimit more than once")
            if (!metresPerSecond.isFinite() || metresPerSecond < 0.0) {
                throw SignalLogicException("signal logic '$logicId' supplied an invalid speed limit")
            }
            speed = metresPerSecond
        }

        @HostAccess.Export
        fun setModelVariant(name: String) {
            if (variant != null) throw SignalLogicException("signal logic '$logicId' called setModelVariant more than once")
            if (name.isBlank()) throw SignalLogicException("signal logic '$logicId' supplied a blank model variant")
            variant = name
        }

        fun finish(): SignalLogicOutput {
            val completeSpeed = speed ?: throw SignalLogicException("signal logic '$logicId' did not call setSpeedLimit")
            val completeVariant = variant ?: throw SignalLogicException("signal logic '$logicId' did not call setModelVariant")
            return SignalLogicOutput(completeSpeed, completeVariant)
        }
    }

    private companion object {
        private val HOST_ACCESS = HostAccess.newBuilder(HostAccess.NONE)
            .allowAccessAnnotatedBy(HostAccess.Export::class.java)
            .build()
        private val DISCARD_OUTPUT: OutputStream = OutputStream.nullOutputStream()
        private const val INACTIVE_OUTPUT_MESSAGE = "signal output is no longer active"

        private const val INVOKE_FACTORY = """
            "use strict";
            (() => {
              const freeze = Object.freeze;
              const isFrozen = Object.isFrozen;
              const keys = Object.keys;
              const parse = JSON.parse;
              Object.defineProperty(globalThis, "Date", { value: undefined, writable: false, configurable: false });
              Object.defineProperty(globalThis, "performance", { value: undefined, writable: false, configurable: false });
              Object.defineProperty(globalThis, "crypto", { value: undefined, writable: false, configurable: false });
              Object.defineProperty(globalThis, "Promise", { value: undefined, writable: false, configurable: false });
              Object.defineProperty(globalThis, "Java", { value: undefined, writable: false, configurable: false });
              Object.defineProperty(globalThis, "Polyglot", { value: undefined, writable: false, configurable: false });
              const noConsoleOutput = () => undefined;
              Object.defineProperty(globalThis, "console", {
                value: freeze({
                  log: noConsoleOutput,
                  info: noConsoleOutput,
                  warn: noConsoleOutput,
                  error: noConsoleOutput,
                  debug: noConsoleOutput
                }),
                writable: false,
                configurable: false
              });
              Object.defineProperty(Math, "random", { value: undefined, writable: false, configurable: false });
              function deepFreeze(value) {
                if (value !== null && typeof value === "object" && !isFrozen(value)) {
                  freeze(value);
                  for (const key of keys(value)) deepFreeze(value[key]);
                }
                return value;
              }
              return (evaluator, json, output) => {
                const input = deepFreeze(parse(json));
                let active = true;
                const facade = freeze({
                  setSpeedLimit(value) {
                    if (!active) throw new Error("signal output is no longer active");
                    output.setSpeedLimit(value);
                  },
                  setModelVariant(value) {
                    if (!active) throw new Error("signal output is no longer active");
                    output.setModelVariant(value);
                  }
                });
                let result;
                try {
                  result = evaluator(input, facade);
                } finally {
                  active = false;
                }
                if (result !== null && (typeof result === "object" || typeof result === "function") &&
                    typeof result.then === "function") {
                  throw new Error("Promise/async signal rule results are not allowed");
                }
              };
            })()
        """

        private fun ruleFailure(message: String, error: Exception): SignalLogicException {
            if (error is SignalLogicException) return error
            val detail = when (error) {
                is PolyglotException -> error.message
                else -> error.message
            } ?: error::class.java.simpleName
            if (detail.contains(INACTIVE_OUTPUT_MESSAGE)) {
                return SignalLogicException("$message: Promise/async signal rule results are not allowed", error)
            }
            return SignalLogicException("$message: $detail", error)
        }
    }
}
