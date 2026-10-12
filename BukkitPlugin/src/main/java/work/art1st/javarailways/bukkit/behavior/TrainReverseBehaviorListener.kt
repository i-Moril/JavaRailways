package work.art1st.javarailways.bukkit.behavior

import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.train.renderer
import work.art1st.javarailways.core.api.event.TrainSwitchDirectionEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent

class TrainReverseBehaviorListener : EventListener {
    @HandlesEvent
    fun onTrainSwitchDirection(event: TrainSwitchDirectionEvent) {
        val trainId = event.train.id
        event.train.renderer.callBehavior("onReverse", listOf(trainId), optional = true).whenComplete { _, failure ->
            if (failure != null) {
                BukkitPlugin.INSTANCE.logger.warning("[CarBehavior:$trainId] onReverse failed: ${failure.message}")
            }
        }
    }
}
