package work.art1st.javarailways.bukkit.behavior

import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.train.renderer
import work.art1st.javarailways.core.api.event.TrainSwitchDirectionEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent

class TrainReverseBehaviorListener : EventListener {
    @HandlesEvent
    fun onTrainSwitchDirection(event: TrainSwitchDirectionEvent) {
        val result = event.train.renderer.callBehavior("onReverse", listOf(event.train.id))
        if (result is TrainBehaviorCallResult.Failure && result.message != "行为方法不存在: onReverse") {
            BukkitPlugin.INSTANCE.logger.warning("[TrainBehavior:${event.train.id}] onReverse failed: ${result.message}")
        }
    }
}
