package work.art1st.javarailways.bukkit.render

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.model.CompactTransform

class AnimationTest {
    @Test
    fun `display overrides change only when playback reaches a keyframe`() {
        val stone = AnimationDisplayState(itemType = "stone", brightnessBlock = 2, brightnessSky = 3)
        val gold = AnimationDisplayState(itemType = "gold_block", brightnessBlock = 12, brightnessSky = 15)
        val animation = Animation(listOf(
            Animation.AnimationCommand(transform(0.0), 0.0, "closed", stone),
            Animation.AnimationCommand(transform(1.0), 1.0, "open", gold),
            Animation.AnimationCommand(transform(2.0), 1.0, "end")
        ))
        val committed = mutableListOf<AnimationDisplayState?>()
        val active = animation.activate(onDestroy = {}, onCommit = { committed += it.currentDisplayState })
        try {
            active.tick(50.0)
            active.commit()
            assertEquals(stone, committed.last())
            assertEquals(0.05, active.currentTransform.position.z(), 1e-9)
            active.tick(949.0)
            active.commit()
            assertEquals(stone, committed.last())
            active.tick(1.0)
            active.commit()
            assertEquals(gold, committed.last())
            active.tick(500.0)
            active.commit()
            assertEquals(gold, committed.last(), "transform-only frames retain the last display override")
        } finally {
            active.destroy()
        }

        val reverse = animation.activateToScene("closed", currentScene = "open", onDestroy = {})
        try {
            reverse.tick(50.0)
            assertEquals(gold, reverse.currentDisplayState)
            reverse.tick(950.0)
            assertEquals(stone, reverse.currentDisplayState)
        } finally {
            reverse.destroy()
        }
    }

    @Test
    fun `scene animation uses milliseconds ticks and reaches final scene`() {
        var destroyed = false
        val active = doorAnimation().activateToScene(
            sceneName = "open",
            currentScene = "closed",
            onDestroy = { destroyed = true }
        )

        active.tick(650.0)
        active.commit()
        assertEquals(0.3125, active.currentTransform.position.z(), 1e-6)
        assertEquals(1.5, active.currentTransform.scale.x(), 1e-6)
        assertTrue(!destroyed)

        active.tick(650.0)
        assertTrue(!destroyed)
        active.commit()
        assertTrue(destroyed)
        assertEquals(0.625, active.finalTransform.position.z(), 1e-6)
        assertEquals("open", active.targetScene)
    }

    @Test
    fun `scene animation can play backward`() {
        val active = doorAnimation().activateToScene(
            sceneName = "closed",
            currentScene = "open",
            onDestroy = {}
        )

        active.tick(650.0)
        active.commit()
        assertEquals(0.3125, active.currentTransform.position.z(), 1e-6)

        active.tick(650.0)
        active.commit()
        assertEquals(0.0, active.finalTransform.position.z(), 1e-6)
        assertEquals("closed", active.targetScene)
    }

    @Test
    fun `zero duration animation does not divide by zero`() {
        var destroyed = false
        val active = Animation(
            listOf(
                Animation.AnimationCommand(
                    transform = transform(z = 1.0),
                    deltaT = 0.0,
                    scene = "open"
                )
            )
        ).activate(onDestroy = { destroyed = true })

        active.tick(0.0)
        assertTrue(!destroyed)
        active.commit()
        assertTrue(destroyed)
        assertEquals(1.0, active.finalTransform.position.z(), 1e-6)
    }

    private fun doorAnimation(): Animation {
        return Animation(
            listOf(
                Animation.AnimationCommand(
                    transform = transform(z = 0.0, scale = 1.0),
                    deltaT = 0.0,
                    scene = "closed"
                ),
                Animation.AnimationCommand(
                    transform = transform(z = 0.625, scale = 2.0),
                    deltaT = 1.3,
                    scene = "open"
                )
            )
        )
    }

    private fun transform(z: Double, scale: Double = 1.0): CompactTransform {
        return CompactTransform(
            position = Vector3d(0.0, 0.0, z),
            scale = Vector3d(scale, scale, scale)
        )
    }
}
