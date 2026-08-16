package work.art1st.javarailways.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import work.art1st.javarailways.bukkit.logic.RailVisualBootstrap
import work.art1st.javarailways.bukkit.persistence.RailRenderMetaStore

/** Adds, replaces, and clears visual rail decoration without changing rail topology. */
internal class RailWandDecorateMode(private val wand: RailWand) {
    fun handle(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> prepareOrClearDecoration(player)
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> applyDecoration(player)
            else -> Unit
        }
    }

    private fun prepareOrClearDecoration(player: Player) {
        wand.findLookedAtRailAsync(player) { rail ->
            if (rail == null) {
                player.sendActionBar(Component.text("视线范围内没有轨道", NamedTextColor.RED))
                return@findLookedAtRailAsync
            }
            val state = wand.getState(player)
            if (state.mode != WandMode.DECORATE) return@findLookedAtRailAsync

            val logicalRails = wand.logicalRails(player, rail)
            val selectionId = logicalRails.first().id
            if (state.pendingDecorationDeleteRail != selectionId) {
                state.pendingDecorationDeleteRail = selectionId
                wand.persistPlayerState(player, state)
                player.sendActionBar(Component.text(
                    "已选择轨道 ${rail.id.toString().take(8)}；再次左键清除全部装饰",
                    NamedTextColor.YELLOW
                ))
                return@findLookedAtRailAsync
            }

            logicalRails.forEach { RailVisualBootstrap.unregisterRail(it.id) }
            RailRenderMetaStore.saveAll(
                player.world,
                RailRenderMetaStore.load(player.world).filterNot { meta ->
                    logicalRails.any { it.id == meta.railId }
                }
            )
            state.pendingDecorationDeleteRail = null
            wand.persistPlayerState(player, state)
            player.sendActionBar(Component.text("已清除该轨道的全部装饰", NamedTextColor.GREEN))
        }
    }

    private fun applyDecoration(player: Player) {
        wand.findLookedAtRailAsync(player) { rail ->
            if (rail == null) {
                player.sendActionBar(Component.text("视线范围内没有轨道", NamedTextColor.RED))
                return@findLookedAtRailAsync
            }
            val state = wand.getState(player)
            if (state.mode != WandMode.DECORATE) return@findLookedAtRailAsync
            val modelName = wand.selectedDecorationModel(player, state)
                ?: return@findLookedAtRailAsync

            runCatching {
                val logicalRails = wand.logicalRails(player, rail)
                val metas = logicalRails.map { RailVisualBootstrap.registerRail(it, modelName) }
                RailRenderMetaStore.upsertRails(player.world, metas)
            }.onSuccess {
                state.pendingDecorationDeleteRail = null
                wand.persistPlayerState(player, state)
                player.sendActionBar(Component.text("已使用 $modelName 装饰轨道", NamedTextColor.GREEN))
            }.onFailure { error ->
                player.sendMessage(Component.text(
                    "轨道装饰失败：${railWandFailureReason(error)}",
                    NamedTextColor.RED
                ))
            }
        }
    }
}
