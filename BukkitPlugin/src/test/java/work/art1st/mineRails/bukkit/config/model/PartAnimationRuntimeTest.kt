package work.art1st.mineRails.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PartAnimationRuntimeTest {
    @Test
    fun `compiles keyframes to scene-aware runtime animation`() {
        val runtime = PartAnimationConfig(
            name = "doors_l",
            keyframes = listOf(
                PartAnimationKeyframeConfig(
                    at = 1.3,
                    scene = "open",
                    offset = Vec3Config(z = 0.625),
                    scale = PartScaleConfig(x = 2.0, y = 2.0, z = 2.0)
                ),
                PartAnimationKeyframeConfig(
                    at = 0.0,
                    scene = "closed"
                )
            )
        ).toRuntimeAnimationOrNull()

        assertNotNull(runtime)
        val active = runtime!!.activateToScene(
            sceneName = "open",
            currentScene = "closed",
            onDestroy = {}
        )
        active.tick(650.0)

        assertEquals(0.3125, active.currentTransform.position.z(), 1e-6)
        assertEquals(1.5, active.currentTransform.scale.x(), 1e-6)
    }

    @Test
    fun `compiles command animations and drops empty definitions`() {
        val runtimeMap = listOf(
            PartAnimationConfig(
                name = "door_left_open",
                commands = listOf(
                    PartAnimationCommandConfig(duration = 0.3, offset = Vec3Config(z = 0.1)),
                    PartAnimationCommandConfig(duration = 1.0, scene = "open", offset = Vec3Config(z = 0.625))
                )
            ),
            PartAnimationConfig(name = "empty")
        ).toRuntimeAnimationMap()

        assertEquals(setOf("door_left_open"), runtimeMap.keys)
        val active = runtimeMap.getValue("door_left_open").activate(onDestroy = {})
        active.tick(1300.0)
        assertEquals(0.625, active.finalTransform.position.z(), 1e-6)
        assertEquals("open", active.targetScene)
    }

    @Test
    fun `compiles display state overrides into runtime animation`() {
        val runtime = PartAnimationConfig(
            name = "door_light_warn",
            keyframes = listOf(
                PartAnimationKeyframeConfig(
                    at = 0.0,
                    itemType = "IRON_NUGGET",
                    itemModel = "art1st/tc/s100/left_door_light_warning",
                    brightness = PartBrightnessConfig(block = 5, sky = 0)
                ),
                PartAnimationKeyframeConfig(
                    at = 0.6,
                    itemModel = "art1st/tc/s100/left_door_light",
                    brightness = PartBrightnessConfig(block = 0, sky = 0)
                )
            )
        ).toRuntimeAnimationOrNull()!!

        val active = runtime.activate(onDestroy = {})
        active.tick(0.0)
        assertEquals("IRON_NUGGET", active.currentDisplayState!!.itemType)
        assertEquals("art1st/tc/s100/left_door_light_warning", active.currentDisplayState!!.itemModel)
        assertEquals(5, active.currentDisplayState!!.brightnessBlock)

        active.tick(600.0)
        assertEquals("art1st/tc/s100/left_door_light", active.finalDisplayState!!.itemModel)
        assertEquals(0, active.finalDisplayState!!.brightnessBlock)
    }

    @Test
    fun `item-only display states do not override brightness`() {
        val runtime = PartAnimationConfig(
            name = "door_light_item_only",
            keyframes = listOf(
                PartAnimationKeyframeConfig(
                    at = 0.0,
                    itemModel = "art1st/tc/s100/left_door_light_warning"
                ),
                PartAnimationKeyframeConfig(
                    at = 0.6,
                    itemModel = "art1st/tc/s100/left_door_light"
                )
            )
        ).toRuntimeAnimationOrNull()!!

        val active = runtime.activate(onDestroy = {})
        active.tick(600.0)
        assertEquals("art1st/tc/s100/left_door_light", active.finalDisplayState!!.itemModel)
        assertEquals(null, active.finalDisplayState!!.brightnessBlock)
        assertEquals(null, active.finalDisplayState!!.brightnessSky)
    }
}
