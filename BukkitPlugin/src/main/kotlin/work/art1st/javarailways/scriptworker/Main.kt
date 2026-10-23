package work.art1st.javarailways.scriptworker

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import java.io.OutputStream

private val json = Json { encodeDefaults = true }

fun executeScript(source: String, timetableId: String): String = ScriptExecution().execute(source, timetableId)

/** One compilation, cancellable before context creation as well as during guest execution. */
internal class ScriptExecution {
    private val lock = Any()
    private var context: Context? = null
    private var cancellationReason: String? = null

    fun cancel(reason: String) {
        val active = synchronized(lock) {
            if (cancellationReason != null) return
            cancellationReason = reason
            context
        }
        // Interrupting the Java thread does not terminate Graal guest loops.
        active?.close(true)
    }

    fun execute(source: String, timetableId: String): String {
        checkNotCancelled()
        val builder = TimetableBuilder(timetableId)
        val hostAccess = HostAccess.newBuilder(HostAccess.NONE)
            .allowAccessAnnotatedBy(HostAccess.Export::class.java)
            .build()
        val guestOutput = LimitedGuestOutput()
        try {
            Context.newBuilder("js")
                .allowHostAccess(hostAccess)
                .allowHostClassLookup { false }
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE)
                .out(guestOutput).err(guestOutput)
                .option("engine.WarnInterpreterOnly", "false")
                .build().use { active ->
                    synchronized(lock) {
                        checkNotCancelled()
                        context = active
                    }
                    try {
                        active.eval(Source.newBuilder("js", source, "timetable.js").buildLiteral())
                        val main = active.getBindings("js").getMember("main")
                        require(main != null && main.canExecute()) { "script must define function main(timetable)" }
                        main.executeVoid(builder)
                        check(!guestOutput.exceeded) { "script printed output exceeds limit" }
                        checkNotCancelled()
                    } finally {
                        synchronized(lock) { context = null }
                    }
                }
            val result = json.encodeToString(builder.build())
            checkNotCancelled()
            return result
        } catch (error: Throwable) {
            val reason = synchronized(lock) { cancellationReason }
            if (reason != null) throw IllegalStateException(reason, error)
            throw error
        }
    }

    private fun checkNotCancelled() = synchronized(lock) {
        check(cancellationReason == null) { cancellationReason!! }
    }
}

/** Prints are discarded, but their combined stdout/stderr byte budget is enforced. */
private class LimitedGuestOutput : OutputStream() {
    private var bytesWritten = 0
    var exceeded = false
        private set

    override fun write(value: Int) = count(1)

    override fun write(bytes: ByteArray, offset: Int, length: Int) = count(length)

    private fun count(length: Int) {
        if (length > MAX_PRINT_BYTES - bytesWritten) {
            exceeded = true
            throw IllegalStateException("script printed output exceeds limit")
        }
        bytesWritten += length
    }

    private companion object {
        const val MAX_PRINT_BYTES = 1024 * 1024
    }
}
