package work.art1st.javarailways.bukkit.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.basic.api.ato.AtoStallReport
import java.io.File

class AtoStallDiagnosticsTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `writes structured report under plugin debug directory`() {
        val report = AtoStallReport(
            worldId = "world/id",
            trainId = "T1",
            tick = 4L,
            errorType = "test.Failure",
            message = "broken",
            stackTrace = "stack",
            initialTrain = null,
            currentTrain = null,
            route = null,
            perception = null,
            activeProviderId = null,
            constraints = emptyMap(),
            occurredAtEpochMillis = 123L
        )

        val output = AtoStallDiagnostics.write(temporaryDirectory, report)
        val root = Json.parseToJsonElement(output.readText()).jsonObject

        assertEquals(File(temporaryDirectory, "debug"), output.parentFile)
        assertEquals("ato-stall-world_id-123.json", output.name)
        assertEquals("T1", root.getValue("trainId").jsonPrimitive.content)
        assertTrue(output.isFile)
    }
}
