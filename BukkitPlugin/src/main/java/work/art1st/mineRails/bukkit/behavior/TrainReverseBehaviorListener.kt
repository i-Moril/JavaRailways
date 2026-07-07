package work.art1st.mineRails.bukkit.behavior

import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.train.renderer
import work.art1st.mineRails.core.api.event.TrainReverseEvent
import work.art1st.mineRails.core.api.event.processor.EventListener
import work.art1st.mineRails.core.api.event.processor.HandlesEvent

class TrainReverseBehaviorListener : EventListener {
    @HandlesEvent
    fun onTrainReverse(event: TrainReverseEvent) {
        val result = event.train.renderer.callBehavior("onReverse", listOf(event.train.id))
        if (result is TrainBehaviorCallResult.Failure && result.message != "行为方法不存在: onReverse") {
            BukkitPlugin.INSTANCE.logger.warning("[TrainBehavior:${event.train.id}] onReverse failed: ${result.message}")
        }
    }
}
