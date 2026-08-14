package work.art1st.javarailways.tunnelbridgebuilder.wand

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class BlockWandMenu(
    private val plugin: JavaPlugin,
    private val wandItem: BlockWandItem
) : Listener {
    fun open(player: Player, wand: ItemStack) {
        val instanceId = wandItem.instanceId(wand) ?: return
        val state = wandItem.load(wand) ?: return
        val holder = SectionInventoryHolder(instanceId, state)
        render(holder)
        player.openInventory(holder.inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? SectionInventoryHolder ?: return
        val player = event.whoClicked as? Player ?: return
        val clicked = event.clickedInventory ?: return
        if (clicked !== event.view.topInventory) {
            // Let players pick up a material onto the cursor, but never quick-move it into the GUI.
            if (event.isShiftClick) event.isCancelled = true
            return
        }
        event.isCancelled = true
        val cell = BlockWandState.cellAtSlot(event.rawSlot) ?: return
        val slot = cell.row * BlockWandState.WIDTH + cell.column

        holder.state = when {
            event.isRightClick -> BlockWandEditor.rightClick(
                holder.state,
                slot,
                makePrimary = event.isShiftClick
            )
            event.isLeftClick -> {
                val cursorMaterial = event.cursor.type
                if (!cursorMaterial.isAir && !cursorMaterial.isBlock) return
                val materialKey = cursorMaterial.takeUnless(Material::isAir)?.key?.asString()
                BlockWandEditor.leftClick(holder.state, slot, materialKey)
            }
            else -> return
        }
        persist(player, holder)
        render(holder)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !is SectionInventoryHolder) return
        if (event.rawSlots.any { it < event.view.topInventory.size }) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? SectionInventoryHolder ?: return
        persist(event.player as? Player ?: return, holder)
    }

    private fun persist(player: Player, holder: SectionInventoryHolder) {
        val wand = wandItem.findByInstance(player, holder.wandInstanceId) ?: return
        wandItem.save(wand, holder.state)
    }

    private fun render(holder: SectionInventoryHolder) {
        holder.inventory.clear()
        for (row in 0 until BlockWandState.HEIGHT) {
            for (column in 0 until BlockWandState.WIDTH) {
                val cell = GridCell(column, row)
                val slot = row * BlockWandState.WIDTH + column
                holder.inventory.setItem(BlockWandState.slotOf(cell), cellIcon(holder.state, slot))
            }
        }
        renderAxes(holder)
    }

    private fun renderAxes(holder: SectionInventoryHolder) {
        for (row in 0 until BlockWandState.HEIGHT) {
            holder.inventory.setItem(row * 9, axisIcon(axisMaterialForRow(holder.state, row)))
        }
        for (column in 0 until BlockWandState.WIDTH) {
            holder.inventory.setItem(5 * 9 + column + 1, axisIcon(axisMaterialForColumn(holder.state, column)))
        }
        holder.inventory.setItem(5 * 9, axisIcon(Material.BLACK_STAINED_GLASS_PANE))
    }

    private fun axisMaterialForRow(state: BlockWandState, row: Int): Material = when {
        state.primaryRailCell?.row == row -> Material.GREEN_STAINED_GLASS_PANE
        state.secondaryRailCells.any { it.row == row } -> Material.RED_STAINED_GLASS_PANE
        else -> Material.BLACK_STAINED_GLASS_PANE
    }

    private fun axisMaterialForColumn(state: BlockWandState, column: Int): Material = when {
        state.primaryRailCell?.column == column -> Material.GREEN_STAINED_GLASS_PANE
        state.secondaryRailCells.any { it.column == column } -> Material.RED_STAINED_GLASS_PANE
        else -> Material.BLACK_STAINED_GLASS_PANE
    }

    private fun axisIcon(material: Material): ItemStack = ItemStack(material).also { item ->
        val meta = item.itemMeta
        meta.displayName(
            Component.text(
                when (material) {
                    Material.GREEN_STAINED_GLASS_PANE -> "基准轨道坐标"
                    Material.RED_STAINED_GLASS_PANE -> "附属轨道坐标"
                    else -> "坐标标记"
                },
                when (material) {
                    Material.GREEN_STAINED_GLASS_PANE -> NamedTextColor.GREEN
                    Material.RED_STAINED_GLASS_PANE -> NamedTextColor.RED
                    else -> NamedTextColor.DARK_GRAY
                }
            )
        )
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
        item.itemMeta = meta
    }

    private fun cellIcon(state: BlockWandState, slot: Int): ItemStack {
        val cell = state[slot]
        val material = when (cell) {
            WandCell.Air -> Material.WHITE_STAINED_GLASS_PANE
            WandCell.Ignore -> Material.BARRIER
            is WandCell.Material -> Material.matchMaterial(cell.key) ?: Material.BARRIER
        }
        return ItemStack(material).also { item ->
            val meta = item.itemMeta
            val cellName = when (cell) {
                WandCell.Air -> "空气（挖空）"
                WandCell.Ignore -> "屏障（不修改）"
                is WandCell.Material -> cell.key
            }
            meta.displayName(Component.text(cellName, NamedTextColor.WHITE))
            meta.lore(
                when {
                    state.isPrimaryRailCell(slot) -> listOf(
                        Component.text("基准轨道格", NamedTextColor.GREEN),
                        Component.text("左边和底边的绿色标记在此相交", NamedTextColor.GRAY),
                        Component.text("此格固定为空气；右键删除", NamedTextColor.DARK_GRAY)
                    )
                    state.isSecondaryRailCell(slot) -> listOf(
                        Component.text("附属轨道格", NamedTextColor.RED),
                        Component.text("左边和底边的红色标记在此相交", NamedTextColor.GRAY),
                        Component.text("Shift + 右键设为基准轨道", NamedTextColor.YELLOW),
                        Component.text("右键删除", NamedTextColor.DARK_GRAY)
                    )
                    else -> listOf(
                        Component.text("光标拿方块左键：复制材料", NamedTextColor.GRAY),
                        Component.text("空手左键：空气/屏障", NamedTextColor.GRAY),
                        Component.text("右键：添加附属轨道", NamedTextColor.RED),
                        Component.text("Shift + 右键：设为基准轨道", NamedTextColor.GREEN)
                    )
                }
            )
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
            item.itemMeta = meta
        }
    }

    private class SectionInventoryHolder(
        val wandInstanceId: UUID,
        state: BlockWandState
    ) : InventoryHolder {
        var state: BlockWandState = state
        private val backingInventory = Bukkit.createInventory(
            this,
            BlockWandState.INVENTORY_SIZE,
            Component.text("Tunnel/Bridge 横断面")
        )

        override fun getInventory(): Inventory = backingInventory
    }
}
