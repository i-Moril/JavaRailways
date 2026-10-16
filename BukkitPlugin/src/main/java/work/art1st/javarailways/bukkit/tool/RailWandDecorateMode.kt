package work.art1st.javarailways.bukkit.tool

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
                player.sendActionBar(RailWandI18n.component(
                    player,
                    "message.no_rail_in_sight"
                ).color(NamedTextColor.RED))
                return@findLookedAtRailAsync
            }
            val state = wand.getState(player)
            if (state.mode != WandMode.DECORATE) return@findLookedAtRailAsync

            val logicalRails = wand.logicalRails(player, rail)
            val selectionId = logicalRails.first().id
            if (state.pendingDecorationDeleteRail != selectionId) {
                state.pendingDecorationDeleteRail = selectionId
                wand.persistPlayerState(player, state)
                player.sendActionBar(RailWandI18n.component(
                    player,
                    "message.decorate_delete_selected"
                ).color(NamedTextColor.YELLOW))
                return@findLookedAtRailAsync
            }

            val railIds = logicalRails.mapTo(linkedSetOf()) { it.id }
            val renderMetas = RailRenderMetaStore.load(player.world)
            val renderMetasBefore = renderMetas.filter { it.railId in railIds }
            logicalRails.forEach { RailVisualBootstrap.unregisterRail(it.id) }
            RailRenderMetaStore.saveAll(
                player.world,
                renderMetas.filterNot { it.railId in railIds }
            )
            state.pendingDecorationDeleteRail = null
            wand.persistPlayerState(player, state)
            wand.recordDecorationUndo(player, railIds, renderMetasBefore, emptyList())
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.decorations_cleared"
            ).color(NamedTextColor.GREEN))
        }
    }

    private fun applyDecoration(player: Player) {
        wand.findLookedAtRailAsync(player) { rail ->
            if (rail == null) {
                player.sendActionBar(RailWandI18n.component(
                    player,
                    "message.no_rail_in_sight"
                ).color(NamedTextColor.RED))
                return@findLookedAtRailAsync
            }
            val state = wand.getState(player)
            if (state.mode != WandMode.DECORATE) return@findLookedAtRailAsync
            val modelName = wand.selectedDecorationModel(player, state)
                ?: return@findLookedAtRailAsync

            runCatching {
                val logicalRails = wand.logicalRails(player, rail)
                val railIds = logicalRails.mapTo(linkedSetOf()) { it.id }
                val renderMetasBefore = RailRenderMetaStore.load(player.world).filter { it.railId in railIds }
                val metas = logicalRails.map { RailVisualBootstrap.registerRail(it, modelName) }
                RailRenderMetaStore.upsertRails(player.world, metas)
                Triple(railIds, renderMetasBefore, metas)
            }.onSuccess { (railIds, renderMetasBefore, expectedAfter) ->
                state.pendingDecorationDeleteRail = null
                wand.persistPlayerState(player, state)
                wand.recordDecorationUndo(player, railIds, renderMetasBefore, expectedAfter)
                player.sendActionBar(RailWandI18n.component(
                    player,
                    "message.rail_decorated",
                    modelName
                ).color(NamedTextColor.GREEN))
            }.onFailure { error ->
                player.sendMessage(RailWandI18n.component(
                    player,
                    "message.decoration_failed",
                    railWandFailureReason(error)
                ).color(NamedTextColor.RED))
            }
        }
    }
}
