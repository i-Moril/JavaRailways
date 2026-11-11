package work.art1st.javarailways.core.testing

import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs a deliberately process-terminal fault in a fresh JVM, without resetting production state.
 * [whenArgument] restricts isolation to matching string parameters of a parameterized test.
 * An empty list isolates every invocation of the annotated method.
 *
 * Keep setup/cleanup inside the test body; this marker isolates the invocation, not class fixtures.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(GlobalHaltInvocation::class)
annotation class IsolateGlobalHalt(vararg val whenArgument: String)

class GlobalHaltInvocation : InvocationInterceptor {
    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext
    ) = execute(invocation, invocationContext, extensionContext)

    override fun interceptTestTemplateMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext
    ) = execute(invocation, invocationContext, extensionContext)

    private fun execute(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        context: ExtensionContext
    ) {
        val annotation = invocationContext.executable.getAnnotation(IsolateGlobalHalt::class.java)
        val selected = annotation.whenArgument.isEmpty() ||
            invocationContext.arguments.any { it is String && it in annotation.whenArgument }
        if (System.getProperty(CHILD_PROPERTY) == "true" || !selected) {
            invocation.proceed()
            return
        }
        invocation.skip()
        val output = Files.createTempFile("javarailways-halt-test-", ".log")
        try {
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D$CHILD_PROPERTY=true",
                "-cp", System.getProperty("javarailways.haltTest.classpath")
                    ?: System.getProperty("java.class.path"),
                GlobalHaltTestProcess::class.java.name,
                context.uniqueId
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                check(process.waitFor(120, TimeUnit.SECONDS)) {
                    "Isolated global-halt invocation timed out: ${context.uniqueId}\n${Files.readString(output)}"
                }
                val diagnostics = Files.readString(output)
                check(process.exitValue() == 0) {
                    "Isolated global-halt invocation failed: ${context.uniqueId}\n$diagnostics"
                }
                if (diagnostics.isNotEmpty()) print(diagnostics)
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    check(process.waitFor(10, TimeUnit.SECONDS)) { "Global-halt test JVM did not exit" }
                }
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    companion object {
        private const val CHILD_PROPERTY = "javarailways.haltTest.child"
    }
}

/** Fixture-only launcher; unique IDs preserve individual parameterized invocations. */
object GlobalHaltTestProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val summary = SummaryGeneratingListener()
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectUniqueId(args.single()))
            .build()
        LauncherFactory.create().execute(request, summary)
        val result = summary.summary
        val writer = PrintWriter(System.out, true)
        result.printTo(writer)
        result.printFailuresTo(writer)
        // Fail if selection silently ran no test or accidentally ran sibling invocations.
        exitProcess(if (result.testsSucceededCount == 1L &&
            result.testsFailedCount == 0L && result.containersFailedCount == 0L) 0 else 1)
    }
}
