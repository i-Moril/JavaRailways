package work.art1st.javarailways.simplesignals.signalpack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Runs the distributed package, not an independently copied test rule or smoke fixture. */
class JrFourLightSignalPackTest {
    private val directory = Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light/signal-packs")).toURI())
    private val referenceSpeed = 100.0 / 3.6
    private val deceleration = 0.6
    private val scanDistance = referenceSpeed * referenceSpeed / (2 * deceleration)
    private val caution = SignalLogicOutput(45.0 / 3.6, "caution")
    private val slow = SignalLogicOutput(65.0 / 3.6, "slow")
    private val stop = SignalLogicOutput(0.0, "stop")
    private val proceed = SignalLogicOutput(null, "proceed")

    @Test
    fun `complete distributed package resolves one player type model and computed scanning logic`() {
        val catalog = SignalPackLoader.load(directory)
        val signal = catalog.signals.getValue("jr_four_light:block")
        assertEquals(setOf("jr_four_light:block"), catalog.signals.keys)
        assertEquals("jr_four_light:head", signal.modelId)
        assertEquals("jr_four_light:block", signal.logicId)
        assertEquals(deceleration, signal.logic.referenceDecelerationMetresPerSecondSquared)
        assertNull(signal.logic.scanDistanceMetres)
        assertEquals(setOf("stop", "caution", "slow", "proceed"), signal.model.materialized.variants().keys)
    }

    @Test
    fun `real Graal emits all four aspects with explicit clear for green`() = withRuntime { runtime ->
        assertEquals(stop, evaluate(runtime, occupied = true))
        assertEquals(stop, evaluate(runtime, listOf(section(50.0, speed = 0.0))))
        assertEquals(caution, evaluate(runtime, listOf(section(150.0, speed = 0.0))))
        assertEquals(slow, evaluate(runtime, listOf(section(300.0, speed = 0.0))))
        assertEquals(proceed, evaluate(runtime))
    }

    @Test
    fun `multiple short sections beyond the next yellow constrain the current aspect`() = withRuntime { runtime ->
        assertEquals(slow, evaluate(runtime, listOf(section(150.0, speed = 45.0 / 3.6))))
        val red = section(200.0, speed = 0.0)
        val thirdYellow = section(170.0, speed = 45.0 / 3.6, next = listOf(red))
        val secondYellow = section(160.0, speed = 45.0 / 3.6, next = listOf(thirdYellow))
        val firstYellow = section(150.0, speed = 45.0 / 3.6, next = listOf(secondYellow))
        assertEquals(caution, evaluate(runtime, listOf(firstYellow)))
        // This pack has no YY or variable-speed Y; insufficient distance must not promise 45 km/h.
        assertEquals(stop, evaluate(runtime, listOf(section(20.0, occupied = true))))
    }

    @Test
    fun `selected ready protection branch uses ordinary section facts and ignores occupied siblings`() = withRuntime { runtime ->
        val selected = section(150.0, selection = "selected", ready = true)
        val other = section(20.0, occupied = true, selection = "not-selected", ready = false)
        assertEquals(proceed, evaluate(runtime, listOf(other, selected)))
        assertEquals(caution, evaluate(runtime, listOf(section(200.0, selection = "selected", ready = false))))
        // Neither a current route nor a selected turnout path may be inferred from an empty active list.
        assertEquals(stop, evaluate(runtime, listOf(other), length = 50.0))
        val nestedUnselected = section(400.0, selection = "not-selected")
        assertEquals(caution, evaluate(runtime, listOf(section(150.0, next = listOf(nestedUnselected), length = 50.0))))
    }

    @Test
    fun `downstream explicit clear is not confused with red and occupancy remains independent`() = withRuntime { runtime ->
        assertEquals(proceed, evaluate(runtime, listOf(section(50.0))))
        assertEquals(stop, evaluate(runtime, listOf(section(50.0, speed = 0.0))))
        assertEquals(stop, evaluate(runtime, listOf(section(50.0, occupied = true))))
        // Missing downstreamSignal is legal for an un-signalled section or a suppressed cycle occurrence.
        assertEquals(proceed, evaluate(runtime, listOf(section(50.0, exposeSignal = false))))
    }

    @Test
    fun `each published termination reason is handled at its own absolute distance`() = withRuntime { runtime ->
        listOf("track-end", "cycle", "route-unresolved", "topology-unavailable").forEach { reason ->
            assertEquals(caution, evaluate(runtime, end = end(reason, 200.0)), reason)
            val terminal = buildJsonObject {
                put("distanceMeters", 200.0)
                put("selection", "not-applicable")
                put("end", end(reason, 200.0))
            }
            assertEquals(caution, evaluate(runtime, listOf(terminal)), reason)
            assertEquals(caution, evaluate(runtime, listOf(section(50.0, termination = end(reason, 200.0)))), reason)
        }
        assertEquals(proceed, evaluate(runtime, end = end("distance-limit", scanDistance)))
        assertEquals(stop, evaluate(runtime, end = null, length = 50.0))
    }

    @Test
    fun `branch distances are absolute and sibling lengths never combine into braking distance`() = withRuntime { runtime ->
        val near = section(150.0, occupied = true, selection = "selected")
        val far = section(300.0, occupied = true, selection = "not-selected")
        assertEquals(caution, evaluate(runtime, listOf(near, far)))
        val fartherOnSamePath = section(300.0, occupied = true)
        assertEquals(slow, evaluate(runtime, listOf(section(200.0, length = 100.0, next = listOf(fartherOnSamePath)))))
    }

    @Test
    fun `quantization respects thresholds and low reference speeds without inventing a green cap`() = withRuntime { runtime ->
        val yellowDistance = (45.0 / 3.6) * (45.0 / 3.6) / (2 * deceleration)
        val slowDistance = (65.0 / 3.6) * (65.0 / 3.6) / (2 * deceleration)
        assertEquals(stop, evaluate(runtime, listOf(section(yellowDistance - 0.01, speed = 0.0))))
        assertEquals(caution, evaluate(runtime, listOf(section(yellowDistance + 0.01, speed = 0.0))))
        assertEquals(caution, evaluate(runtime, listOf(section(slowDistance - 0.01, speed = 0.0))))
        assertEquals(slow, evaluate(runtime, listOf(section(slowDistance + 0.01, speed = 0.0))))
        assertEquals(proceed, evaluate(runtime, listOf(section(100.0, speed = 0.0)), speed = 10.0))
    }

    @Test
    fun `wrong scanning mode and unknown terminal contract cannot silently produce a valid green`() = withRuntime { runtime ->
        assertThrows(SignalLogicException::class.java) {
            runtime.evaluate("jr_four_light:block", buildJsonObject {
                put("self", buildJsonObject { put("occupied", false); put("lengthMeters", 100.0) })
                put("forward", JsonArray(emptyList()))
                put("end", end("distance-limit", scanDistance))
            })
        }
        assertThrows(SignalLogicException::class.java) {
            evaluate(runtime, end = end("future-unknown-reason", scanDistance * 2))
        }
    }

    private fun withRuntime(check: (SignalLogicRuntime) -> Unit) {
        SignalLogicRuntime(SignalPackLoader.load(directory)).use(check)
    }

    private fun evaluate(
        runtime: SignalLogicRuntime,
        branches: List<JsonObject> = emptyList(),
        occupied: Boolean = false,
        length: Double = 150.0,
        end: JsonObject? = if (branches.isEmpty()) end("distance-limit", scanDistance) else null,
        speed: Double = referenceSpeed
    ): SignalLogicOutput = runtime.evaluate("jr_four_light:block", buildJsonObject {
        put("scanDistanceMeters", speed * speed / (2 * deceleration))
        put("referenceSpeedMetersPerSecond", speed)
        put("referenceDecelerationMetersPerSecondSquared", deceleration)
        put("self", buildJsonObject { put("occupied", occupied); put("lengthMeters", length) })
        put("forward", JsonArray(branches))
        if (end != null) put("end", end)
    }).also { assertTrue(it.modelVariant in setOf("stop", "caution", "slow", "proceed")) }

    private fun section(
        distance: Double,
        speed: Double? = null,
        occupied: Boolean = false,
        length: Double = 100.0,
        selection: String = "not-applicable",
        ready: Boolean? = null,
        next: List<JsonObject> = emptyList(),
        termination: JsonObject? = if (next.isEmpty()) end("distance-limit", scanDistance) else null,
        exposeSignal: Boolean = true
    ) = buildJsonObject {
        put("distanceMeters", distance)
        put("section", buildJsonObject { put("occupied", occupied); put("lengthMeters", length) })
        put("selection", selection)
        if (ready != null) put("routeReady", ready)
        if (exposeSignal) put("downstreamSignal", buildJsonObject {
            if (speed == null) put("speedLimitMetersPerSecond", JsonNull) else put("speedLimitMetersPerSecond", speed)
        })
        put("next", JsonArray(next))
        if (termination != null) put("end", termination)
    }

    private fun end(reason: String, distance: Double) = buildJsonObject {
        put("reason", reason)
        put("distanceMeters", distance)
        if (reason == "cycle") {
            put("cycleStartDistanceMeters", 0.0)
            put("cycleLengthMeters", distance)
        }
    }

    companion object {
        @JvmStatic @BeforeAll
        fun bootstrapPaperItems() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
