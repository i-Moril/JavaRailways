package work.art1st.javarailways.scriptworker

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import java.io.ByteArrayOutputStream

private val json = Json { encodeDefaults = true }

fun executeScript(source: String, timetableId: String): String {
    val builder = TimetableBuilder(timetableId)
    val guestOutput = ByteArrayOutputStream()
    val hostAccess = HostAccess.newBuilder(HostAccess.NONE)
        .allowAccessAnnotatedBy(HostAccess.Export::class.java)
        .build()
    Context.newBuilder("js")
        .allowHostAccess(hostAccess)
        .allowHostClassLookup { false }
        .allowIO(false)
        .allowCreateThread(false)
        .allowNativeAccess(false)
        .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE)
        .out(guestOutput).err(guestOutput)
        .option("engine.WarnInterpreterOnly", "false")
        .build().use { context ->
            val scope = context.eval(Source.newBuilder("js", source, "timetable.js").buildLiteral())
            val main = context.getBindings("js").getMember("main")
            require(main != null && main.canExecute()) { "script must define function main(timetable)" }
            main.executeVoid(builder)
        }
    return json.encodeToString(builder.build())
}

fun main(args: Array<String>) {
    try {
        require(args.size == 1 && args[0].isNotBlank()) { "usage: ScriptWorker <timetable-id>" }
        val source = System.`in`.bufferedReader().readText()
        require(source.isNotBlank()) { "stdin must contain JavaScript source" }
        print(executeScript(source, args[0]))
    } catch (error: Throwable) {
        val message = if (error is PolyglotException) error.message else error.message ?: error::class.simpleName
        System.err.println("ScriptWorker: $message")
        kotlin.system.exitProcess(1)
    }
}
