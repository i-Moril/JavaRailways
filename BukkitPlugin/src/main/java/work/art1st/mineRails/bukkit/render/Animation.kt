package work.art1st.mineRails.bukkit.render

import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.render.Animation.AnimationCommand.Companion.INITIAL_STATE
import work.art1st.mineRails.bukkit.render.model.CompactTransform
import work.art1st.mineRails.core.api.util.tick.TickManager
import work.art1st.mineRails.core.api.util.tick.TickingObject
import work.art1st.mineRails.core.util.Destroyable

// TODO: Implement animation
class Animation(
    val commandList: List<AnimationCommand>
) {
    init {
        if (commandList.isEmpty()) {
            throw IllegalArgumentException("No animation commands available")
        }
    }

    fun activate(startFrom: Double = 0.0, onDestroy: (ActiveAnimation) -> Unit) : ActiveAnimation {
        return ActiveAnimation(onDestroy, this, startFrom)
    }

    class AnimationCommand(
        val transform: CompactTransform,
        val deltaT: Double
    ) {
        companion object {
            val INITIAL_STATE = AnimationCommand(CompactTransform(), 0.0)
        }
    }

    class ActiveAnimation(
        private val onDestroy: (ActiveAnimation) -> Unit,
        private val animation: Animation,
        t: Double = 0.0,
    ) : TickingObject, Destroyable {
        // 从开始到现在的总时间
        private var t: Double
        // 从上一个command到现在的总时间
        private var dt: Double
        private lateinit var animationTransform: CompactTransform
        private var currentCommand: AnimationCommand
        private var nextCommandIndex: Int
        private val nextCommand: AnimationCommand
            get() = animation.commandList[nextCommandIndex]

        /**
         * 获取当前插值后的动画变换。
         * 每帧更新，用于渲染。
         */
        val currentTransform: CompactTransform
            get() = animationTransform

        init {
            val command = getCommand(t) ?: throw IllegalArgumentException("Invalid animation start time T")
            currentCommand = command.first
            nextCommandIndex = command.second
            this.t = t
            dt = command.third
            lerp()
        }

        // 获取现在是哪个command
        // Triple<当前, 下一个, dt>
        private fun getCommand(t: Double): Triple<AnimationCommand, Int, Double>? {
            var animT = 0.0
            var current = INITIAL_STATE
            for (i in animation.commandList.indices) {
                val next = animation.commandList[i]
                if (t in animT..<animT + next.deltaT) {
                    return Triple(current, i, t - animT)
                }
                animT += next.deltaT
                current = next
            }
            return null
        }

        private fun CompactTransform.lerp(target: CompactTransform, alpha: Double) : CompactTransform {
            return CompactTransform(
                this.position.lerp(target.position, alpha, Vector3d()),
                this.rotation.slerp(target.rotation, alpha, Quaterniond())
            )
        }

        private fun lerp() {
            animationTransform = currentCommand.transform.lerp(nextCommand.transform, dt / nextCommand.deltaT)
        }

        override fun tick(deltaT: Double) {
            t += deltaT
            dt += deltaT
            if (dt >= nextCommand.deltaT) {
                currentCommand = nextCommand
                nextCommandIndex += 1
                if (nextCommandIndex >= animation.commandList.size) {
                    destroy()
                    return
                }
            }
            lerp()
        }

        override fun destroy() {
            onDestroy.invoke(this)
        }
    }
}