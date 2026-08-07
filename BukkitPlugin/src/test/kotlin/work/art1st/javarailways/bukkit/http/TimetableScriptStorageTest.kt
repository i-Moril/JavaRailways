package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TimetableScriptStorageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `writes normalized UTF-8 source and requires explicit overwrite`() {
        TimetableScriptSandbox(directory).use { scripts ->
            assertFalse(scripts.saveSource("weekday.js", "first\r\nline", overwrite = false))
            assertEquals("first\nline\n", Files.readString(directory.resolve("weekday.js")))
            assertThrows(TimetableScriptConflict::class.java) {
                scripts.saveSource("weekday.js", "replacement", overwrite = false)
            }
            assertTrue(scripts.saveSource("weekday.js", "replacement", overwrite = true))
            assertEquals("replacement\n", Files.readString(directory.resolve("weekday.js")))
        }
    }

    @Test
    fun `renames without overwriting and deletes scripts`() {
        TimetableScriptSandbox(directory).use { scripts ->
            scripts.saveSource("weekday.js", "one", overwrite = false)
            scripts.saveSource("weekend.js", "two", overwrite = false)
            assertThrows(TimetableScriptConflict::class.java) { scripts.renameSource("weekday.js", "weekend.js") }
            scripts.renameSource("weekday.js", "workday.js")
            assertFalse(Files.exists(directory.resolve("weekday.js")))
            assertTrue(Files.exists(directory.resolve("workday.js")))
            assertTrue(scripts.deleteSource("workday.js"))
            assertFalse(scripts.deleteSource("workday.js"))
        }
    }

    @Test
    fun `rejects unsafe script names`() {
        TimetableScriptSandbox(directory).use { scripts ->
            assertThrows(IllegalArgumentException::class.java) { scripts.saveSource("../escape.js", "x", false) }
            assertThrows(IllegalArgumentException::class.java) { scripts.saveSource("not-js.txt", "x", false) }
        }
    }
}
