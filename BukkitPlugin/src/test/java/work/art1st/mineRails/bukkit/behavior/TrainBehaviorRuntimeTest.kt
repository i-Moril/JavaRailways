package work.art1st.mineRails.bukkit.behavior

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.bukkit.config.model.TrainBehaviorScript

class TrainBehaviorRuntimeTest {
    @Test
    fun `onStation can call declarative door actions`() {
        val bindings = RecordingBindings()
        val runtime = TrainBehaviorRuntime(
            TrainBehaviorScript(
                fileName = "behavior.js",
                source = """
                    function onStation(time, door_side) {
                      if (time === 0) action(`door-${'$'}{door_side}-open`)
                      if (time >= 20) action(`door-${'$'}{door_side}-close`)
                    }
                """.trimIndent()
            ),
            bindings
        )

        assertTrue(runtime.call("onStation", listOf(0, "left")) is TrainBehaviorCallResult.Success)
        assertEquals(listOf("door-left-open"), bindings.actions)

        assertTrue(runtime.call("onStation", listOf(20, "left")) is TrainBehaviorCallResult.Success)
        assertEquals(listOf("door-left-open", "door-left-close"), bindings.actions)
        runtime.close()
    }

    @Test
    fun `custom methods can use sound and log`() {
        val bindings = RecordingBindings()
        val runtime = TrainBehaviorRuntime(
            TrainBehaviorScript(
                fileName = "behavior.js",
                source = """
                    function horn() {
                      sound("minecraft:entity.minecart.riding", 0.8, 1.2)
                      log("horn")
                      return "ok"
                    }
                """.trimIndent()
            ),
            bindings
        )

        val result = runtime.call("horn")
        assertTrue(result is TrainBehaviorCallResult.Success)
        assertEquals("ok", (result as TrainBehaviorCallResult.Success).returnValue)
        assertEquals(listOf(Triple("minecraft:entity.minecart.riding", 0.8, 1.2)), bindings.sounds)
        assertEquals(listOf("horn"), bindings.logs)
        runtime.close()
    }

    @Test
    fun `missing method returns failure`() {
        val runtime = TrainBehaviorRuntime(
            TrainBehaviorScript(fileName = "behavior.js", source = "function horn() {}"),
            RecordingBindings()
        )

        val result = runtime.call("onStation")
        assertTrue(result is TrainBehaviorCallResult.Failure)
        runtime.close()
    }

    private class RecordingBindings : TrainBehaviorBindings {
        val actions = mutableListOf<String>()
        val sounds = mutableListOf<Triple<String, Double, Double>>()
        val logs = mutableListOf<String>()

        override fun action(name: String): Boolean {
            actions += name
            return true
        }

        override fun sound(key: String, volume: Double, pitch: Double): Boolean {
            sounds += Triple(key, volume, pitch)
            return true
        }

        override fun log(message: String) {
            logs += message
        }
    }
}
