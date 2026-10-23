package work.art1st.javarailways.bukkit.http

import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimetableHttpPathTest {
    @Test
    fun `routing decodes path segments exactly once without treating plus as space`() {
        val paths = mapOf(
            "A+B" to "A+B",
            "A%2BB" to "A+B",
            "A%20B" to "A B",
            "A%2520B" to "A%20B",
            "A%2FB" to "A/B",
            "%E4%B8%AD%E5%A4%AE" to "中央",
        )
        for ((encoded, expected) in paths) {
            assertEquals(
                listOf("worlds", expected, "timetables", expected),
                timetablePathSegments(URI.create("/worlds/$encoded/timetables/$encoded?id=ignored+query")),
                encoded,
            )
        }
    }
}
