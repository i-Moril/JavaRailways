package work.art1st.mineRails.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.basic.api.ui.config.*
import work.art1st.mineRails.basic.curve.Anchor
import work.art1st.mineRails.basic.curve.ConstraintSolveResult
import work.art1st.mineRails.basic.curve.ConstraintSolver
import work.art1st.mineRails.basic.curve.StandardRailCurve
import work.art1st.mineRails.basic.impl.config.ConfigUIRegistry
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.logic.RailVisualBootstrap
import work.art1st.mineRails.bukkit.persistence.PersistentAnchorStore
import work.art1st.mineRails.bukkit.persistence.RailRenderMetaStore
import work.art1st.mineRails.bukkit.ui.dialog.DialogRenderer
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import java.util.*
import kotlin.math.*

// ═══════════════════════════════════════════════════════════════
// 工作模式
// ═══════════════════════════════════════════════════════════════

enum class WandMode(val displayName: String, val description: String) {
    CREATE("创建模式", "右键创建锚点 | 左键选择锚点 | Shift+F 切换模式"),
    CONNECT("连接模式", "选择两端锚点后右键连接 | Shift+F 切换模式"),
    EDIT("编辑模式", "右键编辑锚点/轨道 | 左键选择 | Shift+F 切换模式"),
    DELETE("删除模式", "左键锚点/轨道进入待删除 | 再次左键确认 | Shift+F 切换模式"),
    DECORATE("装饰模式", "填充展示实体 | Shift+F 切换模式");
}

// ═══════════════════════════════════════════════════════════════
// 撤销操作记录
// ═══════════════════════════════════════════════════════════════

sealed class WandAction {
    data class CreateAnchor(val worldId: UUID, val anchorId: String) : WandAction()
    data class DeleteAnchor(val worldId: UUID, val anchor: SavedAnchorData) : WandAction()
    data class CreateRail(val worldId: UUID, val railId: UUID, val railType: String) : WandAction()
    data class DeleteRail(val worldId: UUID, val railId: UUID) : WandAction()
    data class MultiAction(val actions: List<WandAction>) : WandAction()
}

/** 可序列化的锚点数据（用于 undo 恢复） */
data class SavedAnchorData(
    val id: String,
    val x: Double, val y: Double, val z: Double,
    val tx: Double, val ty: Double, val tz: Double
)

// ═══════════════════════════════════════════════════════════════
// 玩家状态
// ═══════════════════════════════════════════════════════════════

data class WandPlayerState(
    var mode: WandMode = WandMode.CREATE,
    /** 选中的锚点 ID 列表 */
    val selectedAnchors: MutableList<String> = mutableListOf(),
    /** 选中的轨道 UUID 列表 */
    val selectedRails: MutableList<UUID> = mutableListOf(),
    /** 删除模式下：待删除的锚点 ID（null = 无待删除） */
    var pendingDeleteAnchor: String? = null,
    /** 删除模式下：待删除的轨道 UUID（null = 无待删除） */
    var pendingDeleteRail: UUID? = null,
    // 模式设置
    /** 并行轨道数量 */
    var parallelCount: Int = 1,
    /** 并行轨道间距 (m) */
    var parallelSpacing: Double = 4.5,
    /** 锚点朝向是否对齐到最近 15° */
    var snapToAngle: Boolean = true,
    /** 锚点位置是否对齐方块中心 */
    var snapToBlock: Boolean = true,
    /** 创建锚点后是否打开 Config Dialog */
    var openConfigAfterCreate: Boolean = false,
    /** 是否自动装饰轨道 */
    var autoDecorate: Boolean = true,
    /** 曲线半径 (m)。0 = 自动选择 */
    var curveRadius: Double = 0.0
)

// ═══════════════════════════════════════════════════════════════
// RailWand 主类
// ═══════════════════════════════════════════════════════════════

class RailWand(private val plugin: BukkitPlugin) : Listener {

    companion object {
        private const val WAND_KEY = "mineRails_railWand"
        private val WAND_ITEM = Material.STICK
        private val ANCHOR_DISPLAY_MATERIAL = Material.REDSTONE_TORCH
        private val SELECTED_ANCHOR_MATERIAL = Material.REDSTONE_TORCH

        // 简单的递增锚点 ID
        private var anchorCounter = 0L

        fun nextAnchorId(): String = "A${anchorCounter++}"

        /** 获取 CoreWorld */
        fun getCoreWorld(player: Player): CoreWorld? {
            val wm = BukkitPlugin.INSTANCE.worldManager
            return wm.railWorlds[player.world] as? CoreWorld
        }
    }

    private val anchorStore = PersistentAnchorStore()
    private var initialized = false

    /** ConfigAPI 注册中心 + Dialog 渲染器 */
    private val configUI = ConfigUIRegistry()
    private val dialogRenderer = DialogRenderer(configUI)

    /** Wand 设置 ConfigKeys (per-setting, 每个玩家独立 session) */
    private lateinit var wandKeys: WandConfigKeys

    /** 玩家状态映射 */
    private val playerStates = mutableMapOf<UUID, WandPlayerState>()

    /** 锚点展示实体映射: worldId -> (anchorId -> Set<entityId>) */
    private val anchorEntities = mutableMapOf<String, MutableMap<String, MutableSet<UUID>>>()

    /** 撤销/重做栈 (playerId -> stacks) */
    private val undoStacks = mutableMapOf<UUID, MutableList<WandAction>>()
    private val redoStacks = mutableMapOf<UUID, MutableList<WandAction>>()

    fun init() {
        if (initialized) return
        initialized = true
        anchorStore.init(plugin.dataFolder)
        registerWandConfig()
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("RailWand initialized")
    }

    // ═══════════════════════════════════════════
    // ConfigAPI 集成 — Wand 设置
    // ═══════════════════════════════════════════

    /** 每个玩家独立持有 wand 设置，但通过 PlayerConfigSession 隔离值 */
    class WandConfigKeys {
        val parallelCount = ConfigKey.build<Int>("wand.parallelCount") {
            label = "并行轨道数"
            description = "同时创建的并行轨道数量"
            defaultValue(1)
            valueType = ConfigValueType.INTEGER
            constraints = ConfigConstraints.IntRange(1, 10)
            group = "wand.settings"
            order = 0
        }
        val parallelSpacing = ConfigKey.build<Double>("wand.parallelSpacing") {
            label = "并行间距"
            description = "相邻并行轨道之间的距离"
            defaultValue(4.5)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(1.0, 20.0, 0.5, "m")
            group = "wand.settings"
            order = 1
        }
        val snapToAngle = ConfigKey.build<Boolean>("wand.snapAngle") {
            label = "吸附角度 15°"
            description = "锚点朝向是否对齐到最近的 15° 倍数"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 2
        }
        val snapToBlock = ConfigKey.build<Boolean>("wand.snapBlock") {
            label = "吸附方块中心"
            description = "锚点位置是否对齐到方块中心 (+0.5, 0, +0.5)"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 3
        }
        val openConfigAfterCreate = ConfigKey.build<Boolean>("wand.openConfig") {
            label = "创建后打开配置"
            description = "创建锚点后自动打开轨道配置对话框"
            defaultValue(false)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 4
        }
        val autoDecorate = ConfigKey.build<Boolean>("wand.autoDecorate") {
            label = "自动装饰"
            description = "创建轨道后自动填充展示实体"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 5
        }
        val curveRadius = ConfigKey.build<Double>("wand.curveRadius") {
            label = "曲线半径"
            description = "圆曲线半径 (m)。设为 0 则自动选择最优半径。"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 10000.0, 10.0, "m")
            group = "wand.settings"
            order = 6
        }
    }

    /** 锚点编辑配置键 */
    class AnchorEditKeys {
        val anchorId = ConfigKey.build<String>("anchor.id") {
            label = "锚点 ID"; defaultValue(""); valueType = ConfigValueType.STRING; group = "anchor.edit"; order = 0
        }
        val posX = ConfigKey.build<Double>("anchor.posX") {
            label = "X 坐标"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-3e7, 3e7, 0.5, ""); group = "anchor.edit"; order = 1
        }
        val posY = ConfigKey.build<Double>("anchor.posY") {
            label = "Y 坐标"; defaultValue(64.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-64.0, 320.0, 0.5, ""); group = "anchor.edit"; order = 2
        }
        val posZ = ConfigKey.build<Double>("anchor.posZ") {
            label = "Z 坐标"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-3e7, 3e7, 0.5, ""); group = "anchor.edit"; order = 3
        }
        val tangentYaw = ConfigKey.build<Double>("anchor.yaw") {
            label = "朝向 (yaw°)"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-180.0, 180.0, 1.0, "°"); group = "anchor.edit"; order = 4
        }
        // 批量编辑模式: 增量字段
        val deltaX = ConfigKey.build<Double>("anchor.deltaX") {
            label = "ΔX"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-100.0, 100.0, 0.5, "m"); group = "anchor.batchEdit"; order = 0
        }
        val deltaY = ConfigKey.build<Double>("anchor.deltaY") {
            label = "ΔY"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-100.0, 100.0, 0.5, "m"); group = "anchor.batchEdit"; order = 1
        }
        val deltaZ = ConfigKey.build<Double>("anchor.deltaZ") {
            label = "ΔZ"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-100.0, 100.0, 0.5, "m"); group = "anchor.batchEdit"; order = 2
        }
        val deltaYaw = ConfigKey.build<Double>("anchor.deltaYaw") {
            label = "ΔYaw"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-180.0, 180.0, 1.0, "°"); group = "anchor.batchEdit"; order = 3
        }
    }

    private lateinit var editKeys: AnchorEditKeys

    private fun registerWandConfig() {
        wandKeys = WandConfigKeys()
        editKeys = AnchorEditKeys()

        // wand 设置分组
        val wandGroup = ConfigGroup("wand.settings", "RailWand 设置", description = "RailWand 工具参数设置").apply {
            items.add(ConfigItemImpl(wandKeys.parallelCount))
            items.add(ConfigItemImpl(wandKeys.parallelSpacing))
            items.add(ConfigItemImpl(wandKeys.snapToAngle))
            items.add(ConfigItemImpl(wandKeys.snapToBlock))
            items.add(ConfigItemImpl(wandKeys.openConfigAfterCreate))
            items.add(ConfigItemImpl(wandKeys.autoDecorate))
            items.add(ConfigItemImpl(wandKeys.curveRadius))
        }
        configUI.register(wandGroup)

        // 锚点编辑分组 (单锚点)
        val editGroup = ConfigGroup("anchor.edit", "编辑锚点", description = "修改锚点位置和朝向").apply {
            items.add(ConfigItemImpl(editKeys!!.posX))
            items.add(ConfigItemImpl(editKeys!!.posY))
            items.add(ConfigItemImpl(editKeys!!.posZ))
            items.add(ConfigItemImpl(editKeys!!.tangentYaw))
        }
        configUI.register(editGroup)

        // 锚点批量编辑分组 (增量)
        val batchGroup = ConfigGroup("anchor.batchEdit", "批量平移锚点", description = "对所有选中锚点应用增量").apply {
            items.add(ConfigItemImpl(editKeys!!.deltaX))
            items.add(ConfigItemImpl(editKeys!!.deltaY))
            items.add(ConfigItemImpl(editKeys!!.deltaZ))
            items.add(ConfigItemImpl(editKeys!!.deltaYaw))
        }
        configUI.register(batchGroup)
    }

    /** 将 ConfigAPI 中的值同步到 WandPlayerState */
    private fun syncConfigToState(player: Player) {
        val state = getState(player)
        val session = configUI.getPlayerSession(player.uniqueId)
        state.parallelCount = session.getValue(wandKeys.parallelCount)
            ?: (configUI.get(wandKeys.parallelCount) ?: 1)
        state.parallelSpacing = session.getValue(wandKeys.parallelSpacing)
            ?: (configUI.get(wandKeys.parallelSpacing) ?: 4.5)
        state.snapToAngle = session.getValue(wandKeys.snapToAngle)
            ?: (configUI.get(wandKeys.snapToAngle) ?: true)
        state.snapToBlock = session.getValue(wandKeys.snapToBlock)
            ?: (configUI.get(wandKeys.snapToBlock) ?: true)
        state.openConfigAfterCreate = session.getValue(wandKeys.openConfigAfterCreate)
            ?: (configUI.get(wandKeys.openConfigAfterCreate) ?: false)
        state.autoDecorate = session.getValue(wandKeys.autoDecorate)
            ?: (configUI.get(wandKeys.autoDecorate) ?: true)
        state.curveRadius = session.getValue(wandKeys.curveRadius)
            ?: (configUI.get(wandKeys.curveRadius) ?: 0.0)
    }

    /** 将 WandPlayerState 中的值同步到 ConfigAPI session (在打开 Dialog 前调用) */
    private fun syncStateToConfig(player: Player) {
        val state = getState(player)
        val session = configUI.getPlayerSession(player.uniqueId)
        session.setValue(wandKeys.parallelCount, state.parallelCount)
        session.setValue(wandKeys.parallelSpacing, state.parallelSpacing)
        session.setValue(wandKeys.snapToAngle, state.snapToAngle)
        session.setValue(wandKeys.snapToBlock, state.snapToBlock)
        session.setValue(wandKeys.openConfigAfterCreate, state.openConfigAfterCreate)
        session.setValue(wandKeys.autoDecorate, state.autoDecorate)
        session.setValue(wandKeys.curveRadius, state.curveRadius)
    }

    // ═══════════════════════════════════════════
    // 状态管理
    // ═══════════════════════════════════════════

    fun getState(player: Player): WandPlayerState =
        playerStates.getOrPut(player.uniqueId) { WandPlayerState() }

    fun isWandItem(item: ItemStack?): Boolean {
        if (item == null || item.type != WAND_ITEM) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(NamespacedKey(plugin, WAND_KEY), PersistentDataType.BOOLEAN)
    }

    fun giveWand(player: Player) {
        val item = ItemStack(WAND_ITEM)
        val meta = item.itemMeta as ItemMeta
        meta.displayName(Component.text("RailWand", NamedTextColor.GOLD))
        meta.persistentDataContainer.set(NamespacedKey(plugin, WAND_KEY), PersistentDataType.BOOLEAN, true)
        meta.lore(listOf(
            Component.text("Shift+F 切换模式", NamedTextColor.GRAY),
            Component.text("Shift+右键 打开设置", NamedTextColor.GRAY)
        ))
        item.itemMeta = meta
        player.inventory.addItem(item)
    }

    // ═══════════════════════════════════════════
    // 事件处理
    // ═══════════════════════════════════════════

    @EventHandler
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) return
        event.isCancelled = true

        val state = getState(player)
        val modeValues = WandMode.entries.toTypedArray()
        val newIndex = (state.mode.ordinal + 1) % modeValues.size
        state.mode = modeValues[newIndex]
        // 切换模式时清除选择
        clearSelection(state)
        player.sendActionBar(Component.text("${state.mode.displayName}: ${state.mode.description}", NamedTextColor.GREEN))
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (!isWandItem(player.inventory.itemInMainHand)) return
        if (event.hand != EquipmentSlot.HAND) return

        val state = getState(player)
        val world = player.world

        when {
            // Shift + 右键空气 → 打开模式设置
            player.isSneaking && event.action == Action.RIGHT_CLICK_AIR -> {
                event.isCancelled = true
                showModeSettings(player, state)
            }
            // Shift + 右键方块 → 也打开设置
            player.isSneaking && event.action == Action.RIGHT_CLICK_BLOCK -> {
                event.isCancelled = true
                showModeSettings(player, state)
            }
            // Shift + 左键空气 → 清空选择
            player.isSneaking && event.action == Action.LEFT_CLICK_AIR -> {
                event.isCancelled = true
                clearSelection(state)
                player.sendActionBar(Component.text("已清空选择", NamedTextColor.GRAY))
            }
            // 常规交互 → 根据模式分发
            else -> {
                event.isCancelled = true
                dispatchModeAction(player, state, event)
            }
        }
    }

    @EventHandler
    fun onItemHeldChange(event: PlayerItemHeldEvent) {
        val player = event.player
        if (player.isSneaking && isWandItem(player.inventory.itemInMainHand)) {
            // Shift + 切换物品键 → 循环切换模式
            event.isCancelled = true
            val state = getState(player)
            val modeValues = WandMode.entries.toTypedArray()
            val newIndex = (state.mode.ordinal + 1) % modeValues.size
            state.mode = modeValues[newIndex]
            clearSelection(state)
            player.sendActionBar(Component.text("${state.mode.displayName}: ${state.mode.description}", NamedTextColor.GREEN))
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        // 清理该玩家的锚点展示实体
        anchorEntities.values.forEach { worldAnchors ->
            worldAnchors.values.forEach { entityIds ->
                entityIds.forEach { id ->
                    event.player.world.getEntity(id)?.remove()
                }
            }
        }
        anchorEntities.clear()
        playerStates.remove(playerId)
        undoStacks.remove(playerId)
        redoStacks.remove(playerId)
    }

    // ═══════════════════════════════════════════
    // 模式分发
    // ═══════════════════════════════════════════

    private fun dispatchModeAction(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when (state.mode) {
            WandMode.CREATE -> handleCreateMode(player, state, event)
            WandMode.CONNECT -> handleConnectMode(player, state, event)
            WandMode.EDIT -> handleEditMode(player, state, event)
            WandMode.DELETE -> handleDeleteMode(player, state, event)
            WandMode.DECORATE -> handleDecorateMode(player, state, event)
        }
    }

    // ═══════════════════════════════════════════
    // 创建模式
    // ═══════════════════════════════════════════

    private fun handleCreateMode(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            // 右键 → 创建锚点
            event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.RIGHT_CLICK_AIR -> {
                createAnchorAtPlayer(player, state)
            }
            // 左键锚点 → 选择
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                val clickedAnchor = findNearestAnchor(player, 3.0)
                if (clickedAnchor != null && clickedAnchor.id !in state.selectedAnchors) {
                    if (player.isSneaking) {
                        state.selectedAnchors.add(clickedAnchor.id)
                        player.sendActionBar(Component.text("已选择锚点 ${clickedAnchor.id}", NamedTextColor.YELLOW))
                    } else {
                        state.selectedAnchors.clear()
                        state.selectedAnchors.add(clickedAnchor.id)
                        player.sendActionBar(Component.text("已选择锚点 ${clickedAnchor.id}", NamedTextColor.YELLOW))
                    }
                    updateAnchorDisplay(player)
                }
            }
        }
    }

    /** 在玩家位置创建锚点（并尝试连接已选锚点） */
    private fun createAnchorAtPlayer(player: Player, state: WandPlayerState) {
        val position = getSnappedPosition(player, state)
        val tangent = getSnappedTangent(player, state)
        val anchorId = nextAnchorId()
        val anchor = Anchor(anchorId, position, tangent, worldId = player.world.uid.toString())
        anchorStore.add(player.world, anchor)

        // 渲染锚点
        spawnAnchorDisplay(player, anchor)

        // Undo 记录
        pushUndo(player, WandAction.CreateAnchor(player.world.uid, anchorId))

        player.sendActionBar(Component.text("已创建锚点 $anchorId", NamedTextColor.GREEN))

        // 如果已选择锚点，尝试连接
        if (state.selectedAnchors.isNotEmpty()) {
            state.selectedAnchors.add(anchorId)
            connectSelectedAnchors(player, state)
        }

        // 选择新锚点
        state.selectedAnchors.clear()
        state.selectedAnchors.add(anchorId)
        updateAnchorDisplay(player)

        // 可选：打开 Config Dialog
        if (state.openConfigAfterCreate) {
            player.sendMessage(Component.text("配置对话框暂未集成到此模式", NamedTextColor.GRAY))
        }
    }

    // ═══════════════════════════════════════════
    // 连接模式
    // ═══════════════════════════════════════════

    private fun handleConnectMode(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            // 右键 → 如果选择列表已满，创建连接
            event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.RIGHT_CLICK_AIR -> {
                val expectedCount = state.parallelCount * 2  // 并行 × (起点+终点)
                if (state.selectedAnchors.size >= expectedCount) {
                    connectSelectedAnchors(player, state)
                    state.selectedAnchors.clear()
                    updateAnchorDisplay(player)
                } else {
                    player.sendActionBar(Component.text("请选择 $expectedCount 个锚点 (当前: ${state.selectedAnchors.size})", NamedTextColor.RED))
                }
            }
            // 左键锚点 → 选择
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                val clickedAnchor = findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    if (player.isSneaking) {
                        if (clickedAnchor.id !in state.selectedAnchors) {
                            state.selectedAnchors.add(clickedAnchor.id)
                            player.sendActionBar(Component.text("已选择锚点 ${clickedAnchor.id} (${state.selectedAnchors.size})", NamedTextColor.YELLOW))
                        }
                    } else {
                        state.selectedAnchors.clear()
                        state.selectedAnchors.add(clickedAnchor.id)
                        player.sendActionBar(Component.text("已选择锚点 ${clickedAnchor.id}", NamedTextColor.YELLOW))
                    }
                    updateAnchorDisplay(player)
                }
            }
        }
    }

    /** 连接已选择的锚点对，使用 ConstraintSolver 创建曲线 → CoreRail → 渲染 */
    private fun connectSelectedAnchors(player: Player, state: WandPlayerState) {
        if (state.selectedAnchors.size < 2) {
            player.sendActionBar(Component.text("至少需要选择 2 个锚点", NamedTextColor.RED))
            return
        }

        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
            return
        }

        val anchors = state.selectedAnchors.mapNotNull { anchorStore.get(player.world, it) }
        if (anchors.size < 2) {
            player.sendActionBar(Component.text("错误: 部分锚点不存在", NamedTextColor.RED))
            return
        }

        // Step 1: 预计算所有曲线（失败时不留垃圾）
        data class CurvePair(
            val from: Anchor, val to: Anchor,
            val primeCurve: PrimeCurve
        )
        val curvePairs = mutableListOf<CurvePair>()
        for (i in 0 until state.parallelCount) {
            val offset = (i - (state.parallelCount - 1) / 2.0) * state.parallelSpacing
            for (j in 0 until anchors.size step 2) {
                if (j + 1 >= anchors.size) break
                val fromAnchor = if (offset == 0.0) anchors[j] else shiftAnchorPerpendicular(anchors[j], offset)
                val toAnchor = if (offset == 0.0) anchors[j + 1] else shiftAnchorPerpendicular(anchors[j + 1], offset)
                val curve = createCurveBetweenAnchors(fromAnchor, toAnchor, state.curveRadius)
                    ?: run {
                        player.sendActionBar(Component.text(
                            "无法创建曲线: ${fromAnchor.id}→${toAnchor.id}", NamedTextColor.RED
                        ))
                        return
                    }
                curvePairs.add(CurvePair(fromAnchor, toAnchor, curve))
            }
        }

        // Step 2: 注册所有曲线（全部预计算成功后一次性提交）
        val createdRails = mutableListOf<CoreRail>()
        val undoActions = mutableListOf<WandAction>()
        try {
            for (pair in curvePairs) {
                val rail = CoreRail(coreWorld, pair.primeCurve, mutableListOf(), mutableListOf())
                val meta = RailVisualBootstrap.registerRail(rail)
                RailRenderMetaStore.upsertRail(player.world, meta)
                createdRails.add(rail)
                undoActions.add(WandAction.CreateRail(player.world.uid, rail.id, "standard"))
            }
            // 连接相邻轨道
            for (k in 1 until createdRails.size) {
                CoreRail.connect(createdRails[k - 1], createdRails[k])
            }

            pushUndo(player, WandAction.MultiAction(undoActions))
            player.sendMessage(Component.text(
                "✓ 创建了 ${createdRails.size} 段轨道 (${state.parallelCount} 条并行线)", NamedTextColor.GREEN
            ))
        } catch (e: Exception) {
            createdRails.forEach { rail ->
                rail.destroy()
                coreWorld.removeRail(rail)
                RailVisualBootstrap.unregisterRail(rail.id)
            }
            player.sendActionBar(Component.text("创建轨道失败: ${e.message}", NamedTextColor.RED))
        }
    }

    /**
     * 在两锚点间创建五段曲线 (line/clothoid/arc/clothoid/line)。
     * 使用 ConstraintSolver.solveBest() 自动选择最优半径，再用 generateCurve() 生成完整点列。
     */
    private fun createCurveBetweenAnchors(from: Anchor, to: Anchor, radius: Double = 0.0): PrimeCurve? {
        val fromPos = Vector3d(from.position)
        val fromTan3d = Vector3d(from.tangent).normalize()
        val toPos = Vector3d(to.position)
        val toTan3d = Vector3d(to.tangent).normalize()

        // 求解器只需水平分量（Y 剥离），纵断面独立处理
        val fromTan2d = Vector3d(fromTan3d.x(), 0.0, fromTan3d.z()).normalize()
        val toTan2d = Vector3d(toTan3d.x(), 0.0, toTan3d.z()).normalize()

        val angle = ConstraintSolver.computeAngle(fromTan2d, toTan2d)

        // 同方向直线：直接生成直线 PrimeCurve
        if (angle < 1e-6) {
            val dist = fromPos.distance(toPos)
            val direction = Vector3d(toPos).sub(fromPos).normalize()
            return createStraightCurve(fromPos, direction, dist)
        }

        // 求解: 指定 R 或自动 (50m 倍数, 尽可能大)
        val result: ConstraintSolveResult = if (radius > 0) {
            ConstraintSolver.solve(fromPos, fromTan2d, toPos, toTan2d, radius)
        } else {
            ConstraintSolver.solveBest(fromPos, fromTan2d, toPos, toTan2d)
        }
        if (result !is ConstraintSolveResult.Success) {
            plugin.logger.warning("ConstraintSolver: ${(result as ConstraintSolveResult.Failure).reason}")
            return null
        }

        // 生成曲线时使用 3D 切线（含坡度）实现抛物线竖曲线
        val curvePoints = ConstraintSolver.generateCurve(result, fromPos, fromTan3d, toPos, toTan3d, 0.2)
        return PrimeCurve.fromPoints(curvePoints)
    }

    // ═══════════════════════════════════════════
    // 编辑模式
    // ═══════════════════════════════════════════

    private fun handleEditMode(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            // 右键锚点 → 编辑单锚点
            event.action == Action.RIGHT_CLICK_BLOCK || event.action == Action.RIGHT_CLICK_AIR -> {
                val clickedAnchor = findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    state.selectedAnchors.clear()
                    state.selectedAnchors.add(clickedAnchor.id)
                    updateAnchorDisplay(player)
                    editAnchor(player, clickedAnchor, state)
                } else {
                    // 右键空气 → 编辑已选择的锚点列表
                    if (state.selectedAnchors.isNotEmpty()) {
                        editSelectedAnchors(player, state)
                    } else {
                        player.sendActionBar(Component.text("请选择锚点后右键编辑", NamedTextColor.GRAY))
                    }
                }
            }
            // 左键锚点 → 选择
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                val clickedAnchor = findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    if (player.isSneaking) {
                        if (clickedAnchor.id !in state.selectedAnchors) {
                            state.selectedAnchors.add(clickedAnchor.id)
                        }
                    } else {
                        state.selectedAnchors.clear()
                        state.selectedAnchors.add(clickedAnchor.id)
                    }
                    updateAnchorDisplay(player)
                    player.sendActionBar(Component.text("已选择锚点 ${clickedAnchor.id}", NamedTextColor.YELLOW))
                }
            }
        }
    }

    private fun editAnchor(player: Player, anchor: Anchor, state: WandPlayerState) {
        // 将锚点当前值写入 session，然后打开 ConfigAPI Dialog
        val session = configUI.getPlayerSession(player.uniqueId)
        val yaw = Math.toDegrees(atan2(anchor.tangent.x(), anchor.tangent.z()))
        session.setValue(editKeys.anchorId, anchor.id)
        session.setValue(editKeys.posX, anchor.position.x())
        session.setValue(editKeys.posY, anchor.position.y())
        session.setValue(editKeys.posZ, anchor.position.z())
        session.setValue(editKeys.tangentYaw, yaw)
        dialogRenderer.showConfigDialog(player, "anchor.edit") { p ->
            // 确认回调: 读取新值并更新锚点
            val s = configUI.getPlayerSession(p.uniqueId)
            val newPos = Vector3d(
                s.getValue(editKeys.posX) ?: anchor.position.x(),
                s.getValue(editKeys.posY) ?: anchor.position.y(),
                s.getValue(editKeys.posZ) ?: anchor.position.z()
            )
            val newYaw = s.getValue(editKeys.tangentYaw) ?: yaw
            val rad = Math.toRadians(newYaw)
            val newTan = Vector3d(-sin(rad), 0.0, cos(rad))
            val updated = anchor.withPosition(newPos).withTangent(newTan)
            anchorStore.update(player.world, updated)
            removeAnchorDisplay(player, anchor.id)
            spawnAnchorDisplay(player, updated)
            p.sendActionBar(Component.text("锚点 ${anchor.id} 已更新", NamedTextColor.GREEN))
        }
    }

    private fun editSelectedAnchors(player: Player, state: WandPlayerState) {
        val anchors = state.selectedAnchors.mapNotNull { anchorStore.get(player.world, it) }
        if (anchors.isEmpty()) return

        if (anchors.size == 1) {
            editAnchor(player, anchors.first(), state)
        } else {
            // 批量编辑: 通过 ConfigAPI Dialog 设置增量，应用到所有选中锚点
            val session = configUI.getPlayerSession(player.uniqueId)
            session.setValue(editKeys!!.deltaX, 0.0)
            session.setValue(editKeys!!.deltaY, 0.0)
            session.setValue(editKeys!!.deltaZ, 0.0)
            session.setValue(editKeys!!.deltaYaw, 0.0)
            dialogRenderer.showConfigDialog(player, "anchor.batchEdit") { p ->
                val s = configUI.getPlayerSession(p.uniqueId)
                val dx = s.getValue(editKeys!!.deltaX) ?: 0.0
                val dy = s.getValue(editKeys!!.deltaY) ?: 0.0
                val dz = s.getValue(editKeys!!.deltaZ) ?: 0.0
                val dYaw = s.getValue(editKeys!!.deltaYaw) ?: 0.0
                val dRad = Math.toRadians(dYaw)
                anchors.forEach { a ->
                    val newPos = Vector3d(a.position.x() + dx, a.position.y() + dy, a.position.z() + dz)
                    val oldYaw = atan2(a.tangent.x(), a.tangent.z())
                    val newYaw = oldYaw + dRad
                    val newTan = Vector3d(-sin(newYaw), 0.0, cos(newYaw))
                    val updated = a.withPosition(newPos).withTangent(newTan)
                    anchorStore.update(p.world, updated)
                    removeAnchorDisplay(p, a.id)
                    spawnAnchorDisplay(p, updated)
                }
                p.sendActionBar(Component.text("${anchors.size} 个锚点已平移", NamedTextColor.GREEN))
            }
        }
    }

    // ═══════════════════════════════════════════
    // 删除模式
    // ═══════════════════════════════════════════

    private fun handleDeleteMode(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when {
            event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR -> {
                // 检查是否点击了锚点
                val clickedAnchor = findNearestAnchor(player, 3.0)
                if (clickedAnchor != null) {
                    if (state.pendingDeleteAnchor == clickedAnchor.id) {
                        // 二次确认 → 删除
                        deleteAnchor(player, clickedAnchor.id, state)
                        state.pendingDeleteAnchor = null
                    } else {
                        // 第一次左键 → 进入待删除状态
                        state.pendingDeleteAnchor = clickedAnchor.id
                        state.pendingDeleteRail = null
                        player.sendActionBar(Component.text("再次左键锚点 ${clickedAnchor.id} 以确认删除", NamedTextColor.RED))
                    }
                    return
                }

                // 退出待删除状态
                state.pendingDeleteAnchor = null
                state.pendingDeleteRail = null
            }
        }
    }

    private fun deleteAnchor(player: Player, anchorId: String, state: WandPlayerState) {
        val anchor = anchorStore.get(player.world, anchorId) ?: run {
            player.sendActionBar(Component.text("锚点不存在: $anchorId", NamedTextColor.RED))
            return
        }

        // 保存数据用于 undo
        val savedData = SavedAnchorData(
            anchorId,
            anchor.position.x(), anchor.position.y(), anchor.position.z(),
            anchor.tangent.x(), anchor.tangent.y(), anchor.tangent.z()
        )

        // 清除展示实体
        removeAnchorDisplay(player, anchorId)
        anchorStore.remove(player.world, anchorId)
        state.selectedAnchors.remove(anchorId)

        pushUndo(player, WandAction.DeleteAnchor(player.world.uid, savedData))
        player.sendActionBar(Component.text("已删除锚点 $anchorId", NamedTextColor.GREEN))
    }

    // ═══════════════════════════════════════════
    // 装饰模式（占位）
    // ═══════════════════════════════════════════

    private fun handleDecorateMode(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        player.sendActionBar(Component.text("装饰模式 - 功能尚未实现", NamedTextColor.YELLOW))
    }

    // ═══════════════════════════════════════════
    // 辅助方法 — 锚点查找
    // ═══════════════════════════════════════════

    private fun findNearestAnchor(player: Player, maxDistance: Double): Anchor? {
        val playerPos = player.location.toVector().toVector3d()
        return anchorStore.getAll(player.world)
            .filter { it.position.distance(playerPos) <= maxDistance }
            .minByOrNull { it.position.distance(playerPos) }
    }

    // ═══════════════════════════════════════════
    // 辅助方法 — 位置/朝向吸附
    // ═══════════════════════════════════════════

    private fun getSnappedPosition(player: Player, state: WandPlayerState): Vector3dc {
        return if (state.snapToBlock) {
            // 对齐到方块坐标 + (0.5, 0, 0.5)
            val loc = player.location
            Vector3d(
                loc.blockX + 0.5,
                loc.blockY.toDouble(),
                loc.blockZ + 0.5
            )
        } else {
            Vector3d(player.location.x, player.location.y, player.location.z)
        }
    }

    private fun getSnappedTangent(player: Player, state: WandPlayerState): Vector3dc {
        val yawRad = Math.toRadians(player.location.yaw.toDouble() + 180.0) // 玩家朝向
        val snappedYaw = if (state.snapToAngle) {
            // 对齐到最近 15°
            Math.round(yawRad / (Math.PI / 12.0)) * (Math.PI / 12.0)
        } else {
            yawRad
        }
        return Vector3d(-sin(snappedYaw), 0.0, cos(snappedYaw))
    }

    /** 将锚点沿法线方向平移 offset 距离 */
    private fun shiftAnchorPerpendicular(anchor: Anchor, offset: Double): Anchor {
        val tangent = Vector3d(anchor.tangent)
        val up = Vector3d(0.0, 1.0, 0.0)
        val right = Vector3d(tangent).cross(up).normalize()
        return anchor.withPosition(
            Vector3d(anchor.position).add(
                right.x() * offset,
                0.0,
                right.z() * offset,
                Vector3d()
            )
        )
    }

    // ═══════════════════════════════════════════
    // 辅助方法 — 曲线创建
    // ═══════════════════════════════════════════

    private fun createStraightCurve(from: Vector3dc, direction: Vector3dc, length: Double): PrimeCurve {
        val step = Curve.PRECISION
        val steps = maxOf(2, (length / step).toInt())
        val normal = Vector3d(0.0, 1.0, 0.0)
        val points = (0..steps).map { i ->
            val t = i.toDouble() / steps
            val arcLength = t * length
            Curve.RawPoint(
                position = Vector3d(from.x() + direction.x() * length * t,
                    from.y() + direction.y() * length * t,
                    from.z() + direction.z() * length * t),
                tangent = Vector3d(direction),
                normal = normal,
                arcLength = arcLength
            )
        }
        return PrimeCurve.fromPoints(points)
    }

    // ═══════════════════════════════════════════
    // 锚点展示实体
    // ═══════════════════════════════════════════

    private fun spawnAnchorDisplay(player: Player, anchor: Anchor) {
        val worldId = player.world.uid.toString()
        val displayEntity = player.world.spawn(
            Location(player.world, anchor.position.x(), anchor.position.y(), anchor.position.z()),
            ItemDisplay::class.java
        ) { entity ->
            entity.setItemStack(ItemStack(ANCHOR_DISPLAY_MATERIAL))
            entity.isPersistent = false
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            // 设置名称标签
            entity.customName(Component.text(anchor.id, NamedTextColor.RED))
            entity.isCustomNameVisible = true
            // 缩小一点
            entity.transformation = Transformation(
                entity.transformation.translation,
                entity.transformation.leftRotation,
                org.joml.Vector3f(0.5f, 0.5f, 0.5f),
                entity.transformation.rightRotation
            )
            // 仅对持有 wand 的玩家可见
            entity.isVisibleByDefault = false
        }

        // 注册展示实体
        anchorEntities
            .getOrPut(worldId) { mutableMapOf() }
            .getOrPut(anchor.id) { mutableSetOf() }
            .add(displayEntity.uniqueId)

        // 设置可见性为仅此玩家
        player.showEntity(plugin, displayEntity)
    }

    private fun removeAnchorDisplay(player: Player, anchorId: String) {
        val worldId = player.world.uid.toString()
        val entries = anchorEntities[worldId]?.remove(anchorId) ?: return
        entries.forEach { entityId ->
            player.world.getEntity(entityId)?.remove()
        }
    }

    private fun removeAllAnchorDisplays(player: Player) {
        val worldId = player.world.uid.toString()
        anchorEntities[worldId]?.forEach { (_, entityIds) ->
            entityIds.forEach { entityId ->
                player.world.getEntity(entityId)?.remove()
            }
        }
        anchorEntities.remove(worldId)
    }

    /** 更新锚点展示效果：选中 = Glowing */
    private fun updateAnchorDisplay(player: Player) {
        val worldId = player.world.uid.toString()
        val state = getState(player)
        anchorEntities[worldId]?.forEach { (anchorId, entityIds) ->
            entityIds.forEach { entityId ->
                val entity = player.world.getEntity(entityId) ?: return@forEach
                entity.isGlowing = anchorId in state.selectedAnchors
            }
        }
    }

    /** 加载世界时刷新所有锚点展示 */
    fun refreshAllAnchorsForPlayer(player: Player) {
        removeAllAnchorDisplays(player)
        anchorStore.getAll(player.world).forEach { spawnAnchorDisplay(player, it) }
    }

    // ═══════════════════════════════════════════
    // Undo / Redo
    // ═══════════════════════════════════════════

    private fun pushUndo(player: Player, action: WandAction) {
        val stack = undoStacks.getOrPut(player.uniqueId) { mutableListOf() }
        stack.add(action)
        redoStacks[player.uniqueId]?.clear()  // 新操作清空 redo
    }

    fun undo(player: Player): Boolean {
        val stack = undoStacks[player.uniqueId] ?: return false
        if (stack.isEmpty()) {
            player.sendActionBar(Component.text("没有可撤销的操作", NamedTextColor.GRAY))
            return false
        }
        val action = stack.removeAt(stack.size - 1)
        redoStacks.getOrPut(player.uniqueId) { mutableListOf() }.add(action)

        when (action) {
            is WandAction.CreateAnchor -> undoCreateAnchor(player, action)
            is WandAction.DeleteAnchor -> undoDeleteAnchor(player, action)
            is WandAction.CreateRail -> undoCreateRail(player, action)
            is WandAction.DeleteRail -> undoDeleteRail(player, action)
            is WandAction.MultiAction -> {
                action.actions.reversed().forEach { sub ->
                    when (sub) {
                        is WandAction.CreateRail -> undoCreateRail(player, sub)
                        is WandAction.DeleteRail -> undoDeleteRail(player, sub)
                        is WandAction.CreateAnchor -> undoCreateAnchor(player, sub)
                        is WandAction.DeleteAnchor -> undoDeleteAnchor(player, sub)
                        else -> {}
                    }
                }
            }
        }
        player.sendActionBar(Component.text("已撤销", NamedTextColor.GREEN))
        return true
    }

    fun redo(player: Player): Boolean {
        val stack = redoStacks[player.uniqueId] ?: return false
        if (stack.isEmpty()) {
            player.sendActionBar(Component.text("没有可重做的操作", NamedTextColor.GRAY))
            return false
        }
        val action = stack.removeAt(stack.size - 1)
        undoStacks.getOrPut(player.uniqueId) { mutableListOf() }.add(action)

        when (action) {
            is WandAction.CreateAnchor -> redoCreateAnchor(player, action)
            is WandAction.DeleteAnchor -> redoDeleteAnchor(player, action)
            is WandAction.CreateRail -> redoCreateRail(player, action)
            is WandAction.DeleteRail -> redoDeleteRail(player, action)
            is WandAction.MultiAction -> {
                action.actions.forEach { sub ->
                    when (sub) {
                        is WandAction.CreateRail -> redoCreateRail(player, sub)
                        is WandAction.DeleteRail -> redoDeleteRail(player, sub)
                        is WandAction.CreateAnchor -> redoCreateAnchor(player, sub)
                        is WandAction.DeleteAnchor -> redoDeleteAnchor(player, sub)
                        else -> {}
                    }
                }
            }
        }
        player.sendActionBar(Component.text("已重做", NamedTextColor.GREEN))
        return true
    }

    private fun undoCreateAnchor(player: Player, action: WandAction.CreateAnchor) {
        val world = player.world
        removeAnchorDisplay(player, action.anchorId)
        anchorStore.remove(world, action.anchorId)
        getState(player).selectedAnchors.remove(action.anchorId)
    }

    private fun redoCreateAnchor(player: Player, action: WandAction.CreateAnchor) {
        // Cannot fully redo anchor creation without anchor data stored in original action
        player.sendActionBar(Component.text("无法重做锚点创建（数据不足）", NamedTextColor.RED))
    }

    private fun undoDeleteAnchor(player: Player, action: WandAction.DeleteAnchor) {
        val data = action.anchor
        val anchor = Anchor(
            data.id,
            Vector3d(data.x, data.y, data.z),
            Vector3d(data.tx, data.ty, data.tz),
            player.world.uid.toString()
        )
        anchorStore.add(player.world, anchor)
        spawnAnchorDisplay(player, anchor)
    }

    private fun redoDeleteAnchor(player: Player, action: WandAction.DeleteAnchor) {
        removeAnchorDisplay(player, action.anchor.id)
        anchorStore.remove(player.world, action.anchor.id)
    }

    private fun undoCreateRail(player: Player, action: WandAction.CreateRail) {
        val coreWorld = getCoreWorld(player) ?: return
        val rail = coreWorld.getRail(action.railId)
        if (rail != null) {
            RailVisualBootstrap.unregisterRail(action.railId)
            RailRenderMetaStore.removeRail(player.world, action.railId)
            rail.destroy()
            coreWorld.removeRail(rail)
        }
    }

    private fun redoCreateRail(player: Player, action: WandAction.CreateRail) {
        player.sendActionBar(Component.text("无法重做轨道创建（数据不足）", NamedTextColor.RED))
    }

    private fun undoDeleteRail(player: Player, action: WandAction.DeleteRail) {
        player.sendActionBar(Component.text("无法撤销轨道删除（数据不足）", NamedTextColor.RED))
    }

    private fun redoDeleteRail(player: Player, action: WandAction.DeleteRail) {
        val coreWorld = getCoreWorld(player) ?: return
        val rail = coreWorld.getRail(action.railId)
        if (rail != null) {
            RailVisualBootstrap.unregisterRail(action.railId)
            RailRenderMetaStore.removeRail(player.world, action.railId)
            rail.destroy()
            coreWorld.removeRail(rail)
        }
    }

    // ═══════════════════════════════════════════
    // 模式设置 (使用 ConfigAPI + Paper Dialog)
    // ═══════════════════════════════════════════

    /** Shift+右键 → 打开 Paper 原生 Dialog 设置界面 */
    private fun showModeSettings(player: Player, state: WandPlayerState) {
        syncStateToConfig(player)
        dialogRenderer.showConfigDialog(player, "wand.settings") { p ->
            syncConfigToState(p)
            p.sendActionBar(Component.text("RailWand 设置已更新", NamedTextColor.GREEN))
        }
    }

    // ═══════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════

    private fun clearSelection(state: WandPlayerState) {
        state.selectedAnchors.clear()
        state.selectedRails.clear()
        state.pendingDeleteAnchor = null
        state.pendingDeleteRail = null
    }

    /** Convert Bukkit Vector to JOML Vector3d */
    private fun org.bukkit.util.Vector.toVector3d(): Vector3d = Vector3d(x, y, z)
}
