package work.art1st.javarailways.basic.curve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerticalProfileSmootherTest {
    @Test
    fun `single selected crest becomes a level symmetric summit`() {
        val solution = VerticalProfileSmoother.solve(
            stations = listOf(0.0, 10.0, 20.0),
            elevations = listOf(0.0, 5.0, 0.0),
            startGrade = 0.0,
            endGrade = 0.0
        )

        assertEquals(listOf(0.0, 0.0, 0.0), solution.grades.map { rounded(it) })
        assertEquals(solution.segments[0].heightAt(2.5), solution.segments[1].heightAt(7.5), 1.0e-9)
        assertTrue(solution.segments[0].heightAt(5.0) in 0.0..5.0)
        assertTrue(solution.segments[1].heightAt(5.0) in 0.0..5.0)
    }

    @Test
    fun `free interior grade minimizes bending and keeps curvature continuous`() {
        val solution = VerticalProfileSmoother.solve(
            stations = listOf(0.0, 10.0, 25.0),
            elevations = listOf(0.0, 4.0, 13.0),
            startGrade = 0.1,
            endGrade = 0.2
        )

        val leftCurvature = solution.segments[0].curvatureAt(10.0)
        val rightCurvature = solution.segments[1].curvatureAt(0.0)
        assertEquals(leftCurvature, rightCurvature, 1.0e-9)
        assertTrue(solution.grades[1] > 0.0)
    }

    @Test
    fun `horizontal segment remains an exact level hard constraint`() {
        val solution = VerticalProfileSmoother.solve(
            stations = listOf(0.0, 10.0, 20.0),
            elevations = listOf(3.0, 3.0, 8.0),
            startGrade = 0.4,
            endGrade = 0.0,
            horizontalSegments = setOf(0)
        )

        assertEquals(0.0, solution.grades[0], 1.0e-12)
        assertEquals(0.0, solution.grades[1], 1.0e-12)
        for (i in 0..10) {
            assertEquals(3.0, solution.segments[0].heightAt(i.toDouble()), 1.0e-12)
            assertEquals(0.0, solution.segments[0].gradeAt(i.toDouble()), 1.0e-12)
        }
    }

    @Test
    fun `horizontal segment rejects different endpoint elevations`() {
        assertThrows(IllegalArgumentException::class.java) {
            VerticalProfileSmoother.solve(
                stations = listOf(0.0, 10.0),
                elevations = listOf(0.0, 1.0),
                startGrade = 0.0,
                endGrade = 0.0,
                horizontalSegments = setOf(0)
            )
        }
    }

    @Test
    fun `stations must increase strictly`() {
        assertThrows(IllegalArgumentException::class.java) {
            VerticalProfileSmoother.solve(
                stations = listOf(0.0, 0.0),
                elevations = listOf(0.0, 1.0),
                startGrade = 0.0,
                endGrade = 0.0
            )
        }
    }

    private fun rounded(value: Double): Double = if (kotlin.math.abs(value) < 1.0e-12) 0.0 else value
}
