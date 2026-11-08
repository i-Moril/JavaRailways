package work.art1st.javarailways.bukkit.behavior

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.joml.Vector3d
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.bukkit.config.model.*
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import java.util.concurrent.CompletableFuture

class CarAnimationPlaybackTest {
    @Test
    fun `reset to default cancels playback and clears every projected model part`() {
        val animation = Animation(listOf(Animation.AnimationCommand(CompactTransform(), 0.0, "closed"),
            Animation.AnimationCommand(CompactTransform(position = Vector3d(1.0, 0.0, 0.0)), 1.0, "open")))
        val clock = Clock(listOf(mapOf("door" to animation), mapOf("lamp" to animation)))
        val opening = clock.playback.play("door", "open")
        clock.tick(300.0); clock.commit()
        clock.frames.clear()
        clock.playback.resetToDefault()
        assertTrue(opening.isCompletedExceptionally)
        assertTrue(clock.active.isEmpty())
        assertEquals(2, clock.frames.size)
        assertTrue(clock.frames.all { it.name == null && it.scene == null && it.transform == null && it.display == null })
        clock.playback.close()
    }

    @Test
    fun `actual converted S100 door plans include warning delay and complete playback for all six cars`() {
        val source = requireNotNull(PackageLoader(java.io.File("../models/s100")).loadTrainPackage())
        assertEquals(6, source.cars.size)
        source.cars.forEach { (name, model) ->
            val parts = SavedCarModelTree.build(model, source.partRegistry).captureAnimationParts()
            val capabilities = parts.flatMap { it.entries }.groupBy { it.key }.mapValues { (_, entries) -> entries.flatMap { it.value.sceneNames() }.toSet() }
            val clock = Clock(parts)
            val delayed = mutableListOf<Pair<Double, () -> Unit>>()
            val results = mutableListOf<Result<Unit>>()
            CarBehavior({ source.behavior }, { source.actions }, clock.playback::play, { _, _, _ -> },
                { at, run -> delayed.add(at to run); AutoCloseable {} }, {}, { throw it }, capabilities::supportsAnimation,
                clock.playback::closeDurationTicks, { parts }).use { behavior ->
                for (tail in listOf(false, true)) for (side in listOf(DoorSide.LEFT, DoorSide.RIGHT, DoorSide.BOTH)) {
                    assertEquals(38L, behavior.estimateDoorCloseDurationTicks(side, tail), "$name $tail $side")
                }
                assertTrue(clock.active.isEmpty(), "duration query must not start effects")
                behavior.door(DoorSide.LEFT, true, false, results::add)
                delayed.sortedBy { it.first }.forEach { it.second() }; delayed.clear()
                assertTrue(results.isEmpty(), "open result must wait real animation: $name")
                clock.tick(1299.0); clock.commit()
                assertTrue(results.isEmpty())
                clock.tick(1.0); clock.commit()
                assertTrue(results.single().isSuccess)
                results.clear()
                behavior.door(DoorSide.LEFT, false, false, results::add)
                assertTrue(results.isEmpty())
                clock.tick(600.0); clock.commit()
                delayed.sortedBy { it.first }.forEach { it.second() }; delayed.clear()
                assertTrue(results.isEmpty(), "delayed door start is not completion")
                clock.tick(1299.0); clock.commit()
                assertTrue(results.isEmpty())
                clock.tick(1.0); clock.commit()
                assertTrue(results.single().isSuccess, name)
            }
            clock.playback.close()
        }
    }

    @Test
    fun `packaged S100 behavior matches its action declarations and preserves real door durations`() {
        val temporary = java.nio.file.Files.createTempDirectory("s100-door-package-")
        try {
            java.util.zip.ZipFile("../models/s100.zip").use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val target = temporary.resolve(entry.name).normalize()
                    require(target.startsWith(temporary))
                    if (entry.isDirectory) java.nio.file.Files.createDirectories(target) else {
                        java.nio.file.Files.createDirectories(target.parent)
                        zip.getInputStream(entry).use { input -> java.nio.file.Files.copy(input, target) }
                    }
                }
            }
            val source = requireNotNull(PackageLoader(temporary.resolve("s100").toFile()).loadTrainPackage())
            assertEquals(java.io.File("../models/s100/behavior.js").readText(), source.behavior!!.source)
            assertEquals(6, source.cars.size)
            source.cars.forEach { (_, model) ->
                val parts = SavedCarModelTree.build(model, source.partRegistry).captureAnimationParts()
                val capabilities = parts.flatMap { it.entries }.groupBy { it.key }.mapValues { (_, entries) -> entries.flatMap { it.value.sceneNames() }.toSet() }
                val clock = Clock(parts)
                CarBehavior({ source.behavior }, { source.actions }, clock.playback::play, { _, _, _ -> },
                    { _, _ -> error("query must not schedule effects") }, {}, { throw it }, capabilities::supportsAnimation,
                    clock.playback::closeDurationTicks, { parts }).use { behavior ->
                    assertEquals(38L, behavior.estimateDoorCloseDurationTicks(DoorSide.BOTH, false))
                    assertTrue(clock.active.isEmpty())
                }
                clock.playback.close()
            }
        } finally {
            java.nio.file.Files.walk(temporary).use { paths -> paths.sorted(java.util.Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) } }
        }
    }

    @Test
    fun `hidden playback restores its current frame and replacement fails previous operation exactly once`() {
        val animation = Animation(listOf(Animation.AnimationCommand(CompactTransform(), 0.0, "closed"),
            Animation.AnimationCommand(CompactTransform(position = Vector3d(1.0, 0.0, 0.0)), 1.0, "open")))
        val clock = Clock(listOf(mapOf("door" to animation)))
        val opening = clock.playback.play("door", "open")
        clock.tick(400.0); clock.commit()
        assertFalse(opening.isDone)
        val before = clock.frames.last().transform!!.position.x()
        clock.frames.clear()
        clock.playback.projectCurrentFrames() // renderer reconstructed during a hidden animation
        assertEquals(before, clock.frames.single().transform!!.position.x(), 1e-9)
        assertEquals(0.4, before, 1e-9)
        val closing = clock.playback.play("door", "closed")
        assertTrue(opening.isCompletedExceptionally)
        clock.tick(49.0); clock.commit()
        assertFalse(closing.isDone)
        clock.tick(1.0)
        assertFalse(closing.isDone, "physics/generic tick alone cannot complete owner effect")
        clock.commit()
        assertTrue(closing.isDone)
        assertFalse(closing.isCompletedExceptionally)
        assertEquals("closed", clock.frames.last().scene)
        val pending = clock.playback.play("door", "open")
        val committedFrames = clock.frames.size
        clock.projectionAllowed = false
        assertDoesNotThrow { clock.playback.close() }
        assertEquals(committedFrames, clock.frames.size, "logical close must never project onto another region's display")
        assertTrue(pending.isCompletedExceptionally)
        assertTrue(clock.active.isEmpty())
    }

    @Test
    fun `persisted held open scene seeds invisible closing duration`() {
        val animation = Animation(listOf(Animation.AnimationCommand(CompactTransform(), 0.0, "closed"),
            Animation.AnimationCommand(CompactTransform(position = Vector3d(1.0, 0.0, 0.0)), 1.0, "open")))
        val clock = Clock(listOf(mapOf("door" to animation)), mapOf("door" to "open"))
        val close = clock.playback.play("door", "closed")
        assertFalse(close.isDone)
        clock.tick(999.0); clock.commit(); assertFalse(close.isDone)
        clock.tick(1.0); clock.commit(); assertTrue(close.isDone)
        clock.playback.close()
    }

    @Test
    fun `model source reset cancels old slots while visibility projection preserves them`() {
        val animation = Animation(listOf(Animation.AnimationCommand(CompactTransform(), 0.0, "closed"),
            Animation.AnimationCommand(CompactTransform(position = Vector3d(1.0, 0.0, 0.0)), 1.0, "open")))
        val clock = Clock(listOf(mapOf("door" to animation)))
        val old = clock.playback.play("door", "open")
        clock.tick(250.0); clock.commit()
        clock.playback.projectCurrentFrames()
        assertFalse(old.isDone)
        val committedFrames = clock.frames.size
        clock.projectionAllowed = false
        assertDoesNotThrow { clock.playback.resetForSourceChange() }
        assertEquals(committedFrames, clock.frames.size, "source reset only detaches logical playback")
        assertTrue(old.isCompletedExceptionally)
        assertTrue(clock.active.isEmpty())
        clock.frames.clear()
        clock.playback.projectCurrentFrames()
        assertTrue(clock.frames.isEmpty(), "old model slot must never project into a replacement tree")
        clock.projectionAllowed = true
        val current = clock.playback.play("door", "open")
        clock.tick(1000.0); clock.commit()
        assertTrue(current.isDone && !current.isCompletedExceptionally)
        clock.playback.close()
    }

    private class Clock(parts: List<Map<String, Animation>>, initial: Map<String, String> = emptyMap()) {
        val active = mutableListOf<Animation.ActiveAnimation>()
        val frames = mutableListOf<CarAnimationPlayback.Frame>()
        var projectionAllowed = true
        val playback = CarAnimationPlayback({ parts }, { animation ->
            active.add(animation); AutoCloseable { active.remove(animation) }
        }, { initial }, { _, frame ->
            check(projectionAllowed) { "projection from logical teardown owner" }
            frames.add(frame)
        })
        fun tick(ms: Double) = active.toList().forEach { it.tick(ms) }
        fun commit() = active.toList().forEach { it.commit() }
    }
}
