package work.art1st.javarailways.bukkit.render

import java.util.concurrent.CompletionStage
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps

import org.bukkit.Location
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.bukkit.util.PlatformCommitAffinity
import work.art1st.javarailways.core.api.util.Destroyable
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickingObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Display-entity state that can be changed by an animation keyframe. */
data class AnimationDisplayState(
    val itemType: String? = null,
    val itemModel: String? = null,
    val customModelData: Int? = null,
    val brightnessBlock: Int? = null,
    val brightnessSky: Int? = null
) {
    val hasItemOverride: Boolean
        get() = itemType != null || itemModel != null || customModelData != null

    val hasBrightnessOverride: Boolean
        get() = brightnessBlock != null || brightnessSky != null
}

/**
 * Runtime transform animation.
 *
 * Command durations are seconds. [ActiveAnimation.tick] receives milliseconds
 * from TickManager and converts them to seconds internally.
 */
class Animation(
    val commandList: List<AnimationCommand>,
    val keepFinalState: Boolean = true
) {
    private val timeline: List<TimelineFrame>
    private val sceneTimes: Map<String, Double>

    init {
        if (commandList.isEmpty()) {
            throw IllegalArgumentException("No animation commands available")
        }

        val frames = mutableListOf(TimelineFrame(0.0, INITIAL_TRANSFORM, null, null))
        var t = 0.0
        for (command in commandList) {
            t += max(0.0, command.deltaT)
            frames.add(TimelineFrame(t, command.transform, command.scene, command.displayState))
        }
        timeline = frames.sortedBy { it.timeSeconds }
        sceneTimes = timeline.mapNotNull { frame -> frame.scene?.let { it to frame.timeSeconds } }.toMap()
    }

    fun activate(
        startFrom: Double = 0.0,
        commitLocation: () -> Location? = { null },
        onDestroy: (ActiveAnimation) -> Unit,
        onCommit: (ActiveAnimation) -> Unit = {}
    ): ActiveAnimation {
        val start = startFrom.coerceIn(firstTime, lastTime)
        return ActiveAnimation(
            onDestroy = onDestroy,
            onCommit = onCommit,
            commitLocation = commitLocation,
            frames = segmentFrames(start, lastTime),
            keepFinalState = keepFinalState,
            targetScene = timeline.lastOrNull { it.timeSeconds == lastTime }?.scene
        )
    }

    /** Existing runnable scene keys, detached from animation playback state. */
    internal fun sceneNames(): Set<String> = sceneTimes.keys.toSet()

    fun hasScene(sceneName: String): Boolean = sceneTimes.containsKey(normalizeSceneName(sceneName))

    fun stateAtScene(sceneName: String): Pair<CompactTransform, AnimationDisplayState?>? {
        val sceneTime = sceneTimes[normalizeSceneName(sceneName)] ?: return null
        return interpolateAt(sceneTime) to displayStateAt(sceneTime)
    }

    fun activateToScene(
        sceneName: String,
        currentScene: String? = null,
        currentTransform: CompactTransform? = null,
        commitLocation: () -> Location? = { null },
        onDestroy: (ActiveAnimation) -> Unit,
        onCommit: (ActiveAnimation) -> Unit = {}
    ): ActiveAnimation {
        val targetScene = normalizeSceneName(sceneName)
        val targetTime = sceneTimes[targetScene]
            ?: throw IllegalArgumentException("Animation scene not found: $sceneName")
        val normalizedCurrentScene = normalizeSceneName(currentScene)
        val currentSceneTime = normalizedCurrentScene?.let { sceneTimes[it] }

        val frames = if (currentSceneTime != null) {
            segmentFrames(currentSceneTime, targetTime)
        } else if (currentTransform != null) {
            val targetTransform = interpolateAt(targetTime)
            val duration = max(MIN_SCENE_DURATION_SECONDS, abs(targetTime - firstTime))
            listOf(
                PlaybackFrame(0.0, currentTransform, null),
                PlaybackFrame(duration, targetTransform, displayStateAt(targetTime))
            )
        } else {
            segmentFrames(firstTime, targetTime)
        }

        return ActiveAnimation(
            onDestroy = onDestroy,
            onCommit = onCommit,
            commitLocation = commitLocation,
            frames = frames,
            keepFinalState = keepFinalState,
            targetScene = targetScene
        )
    }

    /** Real converted timeline duration; scene transitions share activateToScene's source rules. */
    internal fun durationSeconds(scene: String? = null, currentScene: String? = null, fromTransform: Boolean = false): Double {
        if (scene == null) return lastTime - firstTime
        val targetTime = requireNotNull(sceneTimes[normalizeSceneName(scene)]) { "Animation scene not found: $scene" }
        val sourceTime = sceneTimes[normalizeSceneName(currentScene)]
        val span = abs(targetTime - (sourceTime ?: firstTime))
        return if (sourceTime == null && fromTransform) max(MIN_SCENE_DURATION_SECONDS, span) else span
    }

    internal fun isDefaultScene(scene: String): Boolean =
        stateAtScene(scene) == (INITIAL_TRANSFORM to null)

    private val firstTime: Double get() = timeline.first().timeSeconds
    private val lastTime: Double get() = timeline.last().timeSeconds

    private fun segmentFrames(startTime: Double, endTime: Double): List<PlaybackFrame> {
        val start = startTime.coerceIn(firstTime, lastTime)
        val end = endTime.coerceIn(firstTime, lastTime)
        if (abs(start - end) <= EPSILON) {
            return listOf(PlaybackFrame(0.0, interpolateAt(end), displayStateAt(end)))
        }

        val forward = end > start
        val low = min(start, end)
        val high = max(start, end)
        val keyTimes = buildList {
            add(start)
            timeline.asSequence()
                .map { it.timeSeconds }
                .filter { it > low + EPSILON && it < high - EPSILON }
                .forEach { add(it) }
            add(end)
        }.distinct().sorted().let { if (forward) it else it.asReversed() }

        var elapsed = 0.0
        var previous = keyTimes.first()
        return keyTimes.mapIndexed { index, time ->
            if (index > 0) elapsed += abs(time - previous)
            previous = time
            PlaybackFrame(elapsed, interpolateAt(time), displayStateAt(time))
        }
    }

    private fun displayStateAt(timeSeconds: Double): AnimationDisplayState? {
        val t = timeSeconds.coerceIn(firstTime, lastTime)
        return timeline.lastOrNull { it.timeSeconds <= t + EPSILON && it.displayState != null }?.displayState
    }

    private fun interpolateAt(timeSeconds: Double): CompactTransform {
        val t = timeSeconds.coerceIn(firstTime, lastTime)
        val exact = timeline.lastOrNull { abs(it.timeSeconds - t) <= EPSILON }
        if (exact != null) return exact.transform

        val next = timeline.firstOrNull { it.timeSeconds > t } ?: return timeline.last().transform
        val previous = timeline.lastOrNull { it.timeSeconds < t } ?: return timeline.first().transform
        val span = next.timeSeconds - previous.timeSeconds
        if (span <= EPSILON) return next.transform
        return previous.transform.lerp(next.transform, (t - previous.timeSeconds) / span)
    }

    class AnimationCommand(
        val transform: CompactTransform,
        val deltaT: Double,
        val scene: String? = null,
        val displayState: AnimationDisplayState? = null
    ) {
        companion object {
            val INITIAL_STATE = AnimationCommand(INITIAL_TRANSFORM, 0.0)
        }
    }

    class ActiveAnimation internal constructor(
        private val onDestroy: (ActiveAnimation) -> Unit,
        private val onCommit: (ActiveAnimation) -> Unit,
        private val commitLocation: () -> Location?,
        private val frames: List<PlaybackFrame>,
        val keepFinalState: Boolean,
        val targetScene: String?
    ) : TickingObject, Synchronizable, Destroyable, PlatformCommitAffinity {
        val completion = java.util.concurrent.CompletableFuture<Unit>()
        val completedNormally: Boolean get() = committedFinal
        private var committedFinal = false
        private var elapsedSeconds: Double = 0.0
        private var destroyed: Boolean = false
        private var finished: Boolean = false
        private var animationTransform: CompactTransform = frames.firstOrNull()?.transform ?: INITIAL_TRANSFORM
        private var displayState: AnimationDisplayState? = frames.firstOrNull()?.displayState

        /** Current interpolated transform for rendering. */
        val currentTransform: CompactTransform
            get() = animationTransform

        val finalTransform: CompactTransform
            get() = frames.lastOrNull()?.transform ?: animationTransform

        val currentDisplayState: AnimationDisplayState?
            get() = displayState

        val finalDisplayState: AnimationDisplayState?
            get() = frames.lastOrNull { it.displayState != null }?.displayState

        init {
            updateTransform()
        }

        @Synchronized
        override fun tick(deltaT: Double) {
            if (destroyed || finished) return
            elapsedSeconds += max(0.0, deltaT) / 1000.0
            updateTransform()
            if (elapsedSeconds >= durationSeconds - EPSILON) {
                animationTransform = finalTransform
                displayState = finalDisplayState
                finished = true
            }
        }

        override fun commitLocation(): Location? = commitLocation.invoke()

        @Synchronized
        override fun commit() {
            if (destroyed) return
            try {
                onCommit.invoke(this)
                if (finished) {
                    committedFinal = true
                    // Success requires the final frame's owner commit, never only elapsed time.
                    destroy().toCompletableFuture().getNow(null)
                }
            } catch (failure: Throwable) {
                completion.completeExceptionally(failure)
                try { destroy().toCompletableFuture().getNow(null) } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        internal val durationSeconds: Double
            get() = frames.lastOrNull()?.timeSeconds ?: 0.0

        @Synchronized
        internal fun remainingSeconds(): Double = (durationSeconds - elapsedSeconds).coerceAtLeast(0.0)

        private fun updateTransform() {
            if (frames.isEmpty()) return
            if (frames.size == 1 || durationSeconds <= EPSILON) {
                animationTransform = finalTransform
                displayState = finalDisplayState
                return
            }

            val t = elapsedSeconds.coerceIn(0.0, durationSeconds)
            val nextIndex = frames.indexOfFirst { it.timeSeconds >= t - EPSILON }.takeIf { it >= 0 }
                ?: frames.lastIndex
            if (nextIndex <= 0) {
                animationTransform = frames.first().transform
                displayState = frames.first().displayState
                return
            }
            val previous = frames[nextIndex - 1]
            val next = frames[nextIndex]
            val span = next.timeSeconds - previous.timeSeconds
            animationTransform = if (span <= EPSILON) {
                next.transform
            } else {
                previous.transform.lerp(next.transform, (t - previous.timeSeconds) / span)
            }
            displayState = frames.lastOrNull {
                it.timeSeconds <= t + EPSILON && it.displayState != null
            }?.displayState
        }

        private val destruction = Destruction()

        /** Visibility unload may project the target immediately; semantic playback keeps its clock. */
        @Synchronized
        internal fun finishForUnload(): CompletionStage<Void> {
            finished = true
            committedFinal = true
            animationTransform = finalTransform
            displayState = finalDisplayState
            return destroy()
        }

        @Synchronized
        override fun destroy(): CompletionStage<Void> = destruction.run {
            destroyed = true
            val steps = DestructionSteps()
            steps.action { onDestroy.invoke(this) }
            steps.finish().whenComplete { _, failure ->
                if (failure != null) completion.completeExceptionally(failure)
                else if (committedFinal) completion.complete(Unit)
                else completion.completeExceptionally(java.util.concurrent.CancellationException("animation replaced or destroyed"))
            }
        }
    }

    private data class TimelineFrame(
        val timeSeconds: Double,
        val transform: CompactTransform,
        val scene: String?,
        val displayState: AnimationDisplayState?
    )

    internal data class PlaybackFrame(
        val timeSeconds: Double,
        val transform: CompactTransform,
        val displayState: AnimationDisplayState?
    )

    companion object {
        private const val EPSILON = 1e-9
        private const val MIN_SCENE_DURATION_SECONDS = 0.05
        private val INITIAL_TRANSFORM = CompactTransform()

        fun normalizeSceneName(scene: String?): String? {
            return when (val normalized = scene?.trim()?.lowercase()) {
                null, "" -> null
                "o" -> "open"
                "c" -> "closed"
                else -> normalized
            }
        }
    }
}
