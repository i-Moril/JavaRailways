package work.art1st.mineRails.bukkit.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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
import work.art1st.mineRails.basic.curve.AnchorGeometry
import work.art1st.mineRails.basic.curve.RailCurveFactory
import work.art1st.mineRails.basic.impl.config.ConfigUIRegistry
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.logic.RailVisualBootstrap
import work.art1st.mineRails.bukkit.persistence.PersistedRailRenderMeta
import work.art1st.mineRails.bukkit.persistence.RailRenderMetaStore
import work.art1st.mineRails.bukkit.ui.dialog.DialogRenderer
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailView
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
        private val ANCHOR_DIRECTION_MATERIAL = Material.ARROW

        // 简单的递增锚点 ID
        private var anchorCounter = 0L

        fun nextAnchorId(): String = "A${anchorCounter++}"

        /** 获取 CoreWorld */
        fun getCoreWorld(player: Player): CoreWorld? {
            val wm = BukkitPlugin.INSTANCE.worldManager
            return wm.railWorlds[player.world] as? CoreWorld
        }
    }

    /** 临时锚点：仅存在于当前插件运行期，不再写入磁盘。 */
    private val transientAnchors = mutableMapOf<UUID, MutableMap<String, Anchor>>()
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

    private val createMode = RailWandCreateMode(this)
    private val connectMode = RailWandConnectMode(this)
    private val editMode = RailWandEditMode(this)
    private val deleteMode = RailWandDeleteMode(this)
    private val decorateMode = RailWandDecorateMode()

    fun init() {
        if (initialized) return
        initialized = true
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
        val posX = ConfigKey.build<String>("anchor.posX") {
            label = "X 坐标"; defaultValue("0.000"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 1
        }
        val posY = ConfigKey.build<String>("anchor.posY") {
            label = "Y 坐标"; defaultValue("64.000"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 2
        }
        val posZ = ConfigKey.build<String>("anchor.posZ") {
            label = "Z 坐标"; defaultValue("0.000"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 3
        }
        val tangentYaw = ConfigKey.build<String>("anchor.yaw") {
            label = "Yaw (°)"; defaultValue("0.0"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 4
        }
        val tangentPitch = ConfigKey.build<String>("anchor.pitch") {
            label = "Pitch (°)"; defaultValue("0.0"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 5
        }
        val roll = ConfigKey.build<String>("anchor.roll") {
            label = "Roll (°)"; defaultValue("0.0"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 6
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
            items.add(ConfigItemImpl(editKeys!!.tangentPitch))
            items.add(ConfigItemImpl(editKeys!!.roll))
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
            WandMode.CREATE -> createMode.handle(player, state, event)
            WandMode.CONNECT -> connectMode.handle(player, state, event)
            WandMode.EDIT -> editMode.handle(player, state, event)
            WandMode.DELETE -> deleteMode.handle(player, state, event)
            WandMode.DECORATE -> decorateMode.handle(player, state, event)
        }
    }

    // ═══════════════════════════════════════════
    // 创建模式
    // ═══════════════════════════════════════════

    /** 在交互目标方块/面创建锚点（并尝试连接已选锚点） */
    internal fun createAnchorAtPlayer(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        val position = getAnchorPlacementPosition(player, state, event) ?: run {
            player.sendActionBar(Component.text("请右键方块创建锚点", NamedTextColor.RED))
            return
        }
        val tangent = getSnappedTangent(player, state)
        val newAnchors = createParallelAnchorGroup(player, position, tangent, state)
        val newAnchorIds = newAnchors.map { it.id }

        val undoActions = newAnchors.map { WandAction.CreateAnchor(player.world.uid, it.id) }
        pushUndo(player, if (undoActions.size == 1) undoActions.single() else WandAction.MultiAction(undoActions))

        player.sendActionBar(Component.text(
            "已创建 ${newAnchors.size} 个锚点: ${newAnchorIds.joinToString(", ")}", NamedTextColor.GREEN
        ))

        val expectedCount = state.parallelCount * 2
        if (state.selectedAnchors.isNotEmpty()) {
            state.selectedAnchors.addAll(newAnchorIds)
            if (state.selectedAnchors.size >= expectedCount) {
                val endpointSelection = connectSelectedAnchors(player, state)
                if (endpointSelection != null) {
                    state.selectedAnchors.clear()
                    state.selectedAnchors.addAll(endpointSelection)
                }
            } else {
                player.sendActionBar(Component.text(
                    "已选择 ${state.selectedAnchors.size}/$expectedCount 个锚点", NamedTextColor.YELLOW
                ))
            }
        } else {
            state.selectedAnchors.addAll(newAnchorIds)
        }

        updateAnchorDisplay(player)

        // 可选：打开 Config Dialog
        if (state.openConfigAfterCreate) {
            player.sendMessage(Component.text("配置对话框暂未集成到此模式", NamedTextColor.GRAY))
        }
    }

    private fun createParallelAnchorGroup(
        player: Player,
        center: Vector3dc,
        tangent: Vector3dc,
        state: WandPlayerState
    ): List<Anchor> {
        val count = state.parallelCount.coerceAtLeast(1)
        val right = perpendicularRight(tangent)
        return (0 until count).map { index ->
            val offset = (index - (count - 1) / 2.0) * state.parallelSpacing
            val position = Vector3d(center).fma(offset, right)
            val anchorId = nextAnchorId()
            Anchor(anchorId, position, Vector3d(tangent), worldId = player.world.uid.toString()).also { anchor ->
                addAnchor(player.world, anchor)
                spawnAnchorDisplay(player, anchor)
            }
        }
    }

    private fun perpendicularRight(tangent: Vector3dc): Vector3d {
        val horizontal = Vector3d(tangent.x(), 0.0, tangent.z())
        if (horizontal.lengthSquared() < 1e-9) return Vector3d(1.0, 0.0, 0.0)
        return horizontal.normalize().cross(Vector3d(0.0, 1.0, 0.0)).normalize()
    }

    // ═══════════════════════════════════════════
    // 连接模式
    // ═══════════════════════════════════════════

    /** 连接已选择的锚点组，使用 ConstraintSolver 创建曲线 → CoreRail → 渲染 */
    internal fun connectSelectedAnchors(player: Player, state: WandPlayerState): List<String>? {
        val expectedCount = state.parallelCount * 2
        if (state.selectedAnchors.size != expectedCount) {
            player.sendActionBar(Component.text(
                "需要选择 $expectedCount 个锚点 (当前: ${state.selectedAnchors.size})", NamedTextColor.RED
            ))
            return null
        }

        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
            return null
        }

        val anchors = state.selectedAnchors.mapNotNull { getAnchor(player.world, it) }
        if (anchors.size != expectedCount) {
            player.sendActionBar(Component.text("错误: 部分锚点不存在", NamedTextColor.RED))
            return null
        }

        val startAnchors = anchors.take(state.parallelCount)
        val endAnchors = anchors.drop(state.parallelCount)
        val validationError = validateParallelAnchorGroup(startAnchors, endAnchors, state)
        if (validationError != null) {
            player.sendActionBar(Component.text(validationError, NamedTextColor.RED))
            return null
        }

        // Step 1: 预计算所有曲线（失败时不留垃圾）
        data class CurvePair(
            val from: Anchor, val to: Anchor,
            val primeCurve: PrimeCurve
        )
        val curvePairs = mutableListOf<CurvePair>()
        for (i in 0 until state.parallelCount) {
            val fromAnchor = startAnchors[i]
            val toAnchor = endAnchors[i]
            val curve = createCurveBetweenAnchors(fromAnchor, toAnchor, state.curveRadius)
                ?: run {
                    player.sendActionBar(Component.text(
                        "无法创建曲线: ${fromAnchor.id}→${toAnchor.id}", NamedTextColor.RED
                    ))
                    return null
                }
            curvePairs.add(CurvePair(fromAnchor, toAnchor, curve))
        }

        val existingRails = RailRenderMetaStore.load(player.world)
            .mapNotNull { coreWorld.getRail(it.railId) }
        val createdRails = mutableListOf<CoreRail>()
        val persistedMetas = mutableListOf<PersistedRailRenderMeta>()
        val undoActions = mutableListOf<WandAction>()
        try {
            for (pair in curvePairs) {
                val rail = CoreRail(coreWorld, pair.primeCurve, mutableListOf(), mutableListOf())
                val meta = RailVisualBootstrap.registerRail(rail)
                persistedMetas.add(meta)
                createdRails.add(rail)
                undoActions.add(WandAction.CreateRail(player.world.uid, rail.id, "standard"))
            }

            val autoConnected = connectCreatedRailsToExisting(createdRails, existingRails)
            RailRenderMetaStore.upsertRails(player.world, persistedMetas)
            pushUndo(player, WandAction.MultiAction(undoActions))
            player.sendMessage(Component.text(
                "✓ 创建了 ${createdRails.size} 段轨道 (${state.parallelCount} 条并行线)，自动连接 $autoConnected 条", NamedTextColor.GREEN
            ))
            val endpointSelection = createdRails.map { railEndpointAnchorId(it.id, 'B') }
            state.selectedAnchors.toList().forEach { anchorId ->
                if (!isRailEndpointAnchorId(anchorId)) {
                    removeAnchorDisplay(player, anchorId)
                    removeAnchor(player.world, anchorId)
                }
            }
            refreshAllAnchorsForPlayer(player)
            return endpointSelection
        } catch (e: Exception) {
            createdRails.forEach { rail ->
                rail.destroy()
                coreWorld.removeRail(rail)
                RailVisualBootstrap.unregisterRail(rail.id)
                RailRenderMetaStore.removeRail(player.world, rail.id)
            }
            player.sendActionBar(Component.text("创建轨道失败: ${e.message}", NamedTextColor.RED))
            return null
        }
    }

    private data class RailEndpointEditPlan(
        val oldRail: CoreRail,
        val movedEnd: Char,
        val newCurve: PrimeCurve
    )

    private data class RailConnectionSnapshot(
        val rail: CoreRail,
        val abNext: List<RailView>,
        val baNext: List<RailView>,
        val abActive: RailView?,
        val baActive: RailView?
    )

    private fun rebuildRailForEndpointEdit(player: Player, endpointAnchorId: String, updatedEndpoint: Anchor): String? {
        val (editedRailId, editedEnd) = parseRailEndpointAnchorId(endpointAnchorId) ?: return null
        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
            return null
        }
        val editedRail = coreWorld.getRail(editedRailId) ?: run {
            player.sendActionBar(Component.text("轨道不存在: $editedRailId", NamedTextColor.RED))
            return null
        }
        val movedPoint = if (editedEnd == 'A') editedRail.viewAB.startPoint.position else editedRail.viewAB.endPoint.position
        val existingMetas = RailRenderMetaStore.load(player.world)
        val allPersistedRails = existingMetas.mapNotNull { coreWorld.getRail(it.railId) }
        val affectedRails = allPersistedRails.filter { rail ->
            rail.viewAB.startPoint.position.distance(movedPoint) <= Curve.TOLERANCE ||
                rail.viewAB.endPoint.position.distance(movedPoint) <= Curve.TOLERANCE
        }
        if (affectedRails.isEmpty()) {
            player.sendActionBar(Component.text("没有找到连接到该端点的轨道", NamedTextColor.RED))
            return null
        }
        if (affectedRails.any { it.trains.isNotEmpty() }) {
            player.sendActionBar(Component.text("不能编辑有列车占用的轨道", NamedTextColor.RED))
            return null
        }

        val plans = affectedRails.mapNotNull { rail ->
            val moveA = rail.viewAB.startPoint.position.distance(movedPoint) <= Curve.TOLERANCE
            val moveB = rail.viewAB.endPoint.position.distance(movedPoint) <= Curve.TOLERANCE
            val movedEnd = when {
                moveA -> 'A'
                moveB -> 'B'
                else -> return@mapNotNull null
            }
            val oldStart = anchorFromRailEndpoint(player.world, rail, 'A')
            val oldEnd = anchorFromRailEndpoint(player.world, rail, 'B')
            val newStart = if (movedEnd == 'A') updatedEndpoint else oldStart
            val newEnd = if (movedEnd == 'B') updatedEndpoint else oldEnd
            val newCurve = createCurveBetweenAnchors(newStart, newEnd, getState(player).curveRadius) ?: run {
                player.sendActionBar(Component.text("无法根据新端点生成轨道: ${rail.id}", NamedTextColor.RED))
                return null
            }
            RailEndpointEditPlan(rail, movedEnd, newCurve)
        }
        if (plans.size != affectedRails.size) {
            player.sendActionBar(Component.text("端点编辑计划不完整，已取消", NamedTextColor.RED))
            return null
        }

        val snapshots = allPersistedRails.map { snapshotRailConnections(it) }
        val createdRails = mutableListOf<CoreRail>()
        val createdMetas = mutableListOf<PersistedRailRenderMeta>()
        val oldIds = plans.map { it.oldRail.id }.toSet()
        val oldToNew = mutableMapOf<UUID, CoreRail>()
        return try {
            plans.forEach { plan ->
                val newRail = CoreRail(coreWorld, plan.newCurve, mutableListOf(), mutableListOf())
                val meta = RailVisualBootstrap.registerRail(newRail)
                createdRails.add(newRail)
                createdMetas.add(meta)
                oldToNew[plan.oldRail.id] = newRail
            }

            val nextMetas = existingMetas.filterNot { it.railId in oldIds } + createdMetas
            RailRenderMetaStore.saveAll(player.world, nextMetas)

            detachRailReferences(oldIds, allPersistedRails.filter { it.id !in oldIds })
            connectCreatedRailsToExisting(
                createdRails,
                allPersistedRails.filter { it.id !in oldIds } + createdRails
            )

            plans.forEach { plan ->
                RailVisualBootstrap.unregisterRail(plan.oldRail.id)
                plan.oldRail.destroy()
                coreWorld.removeRail(plan.oldRail)
            }

            val replacementForEdited = oldToNew[editedRailId] ?: createdRails.first()
            railEndpointAnchorId(replacementForEdited.id, editedEnd)
        } catch (e: Exception) {
            createdRails.forEach { rail ->
                RailVisualBootstrap.unregisterRail(rail.id)
                rail.destroy()
                coreWorld.removeRail(rail)
            }
            restoreRailConnections(snapshots)
            RailRenderMetaStore.saveAll(player.world, existingMetas)
            existingMetas.forEach { RailVisualBootstrap.fromPersisted(coreWorld, it) }
            player.sendActionBar(Component.text("端点编辑失败，已回滚: ${e.message}", NamedTextColor.RED))
            null
        }
    }

    private fun anchorFromRailEndpoint(world: World, rail: CoreRail, end: Char): Anchor {
        val point = if (end == 'A') rail.viewAB.startPoint else rail.viewAB.endPoint
        return AnchorGeometry.withRoll(
            Anchor(
                id = railEndpointAnchorId(rail.id, end),
                position = Vector3d(point.position),
                tangent = Vector3d(point.tangent),
                worldId = world.uid.toString()
            ),
            AnchorGeometry.rollDegreesFromNormal(point.tangent, point.normal)
        )
    }

    private fun snapshotRailConnections(rail: CoreRail): RailConnectionSnapshot = RailConnectionSnapshot(
        rail = rail,
        abNext = rail.viewAB.mutableNextRails.toList(),
        baNext = rail.viewBA.mutableNextRails.toList(),
        abActive = rail.viewAB.activeRail,
        baActive = rail.viewBA.activeRail
    )

    private fun restoreRailConnections(snapshots: List<RailConnectionSnapshot>) {
        snapshots.forEach { snapshot ->
            restoreViewConnections(snapshot.rail.viewAB, snapshot.abNext, snapshot.abActive)
            restoreViewConnections(snapshot.rail.viewBA, snapshot.baNext, snapshot.baActive)
        }
    }

    private fun restoreViewConnections(view: RailView, nextRails: List<RailView>, activeRail: RailView?) {
        view.activeRail = null
        view.mutableNextRails.clear()
        view.mutableNextRails.addAll(nextRails)
        view.activeRail = activeRail
    }

    private fun detachRailReferences(removedRailIds: Set<UUID>, existingRails: List<CoreRail>) {
        existingRails.forEach { rail ->
            listOf(rail.viewAB, rail.viewBA).forEach { view ->
                val replacement = view.mutableNextRails.firstOrNull { it.rail.id !in removedRailIds }
                if (view.activeRail?.rail?.id in removedRailIds) {
                    view.activeRail = replacement
                }
                view.mutableNextRails.removeIf { it.rail.id in removedRailIds }
            }
        }
    }

    private fun validateParallelAnchorGroup(
        startAnchors: List<Anchor>,
        endAnchors: List<Anchor>,
        state: WandPlayerState
    ): String? {
        if (state.parallelCount <= 1) return null
        val tolerance = max(0.25, state.parallelSpacing * 0.15)
        for (i in 0 until state.parallelCount - 1) {
            val startDistance = startAnchors[i].position.distance(startAnchors[i + 1].position)
            if (abs(startDistance - state.parallelSpacing) > tolerance) {
                return "起点组锚点间距应为 ${state.parallelSpacing}m (当前 %.2fm)".format(startDistance)
            }
            val endDistance = endAnchors[i].position.distance(endAnchors[i + 1].position)
            if (abs(endDistance - state.parallelSpacing) > tolerance) {
                return "终点组锚点间距应为 ${state.parallelSpacing}m (当前 %.2fm)".format(endDistance)
            }
        }
        return null
    }

    /**
     * 将本次新建轨道与既有轨道中端点重合的部分自动连接。
     * 创建模式每次通常只创建一段 A->B；下一次从 B->C 时，新段不会进入同一批 createdRails，
     * 因此必须显式把新段连接到之前已经存在的 B 端轨道。
     */
    private fun connectCreatedRailsToExisting(createdRails: List<CoreRail>, existingRails: List<CoreRail>): Int {
        var connected = 0
        val checkedPairs = HashSet<Pair<UUID, UUID>>()
        for (created in createdRails) {
            for (existing in existingRails) {
                if (created == existing) continue
                val key = if (created.id.toString() <= existing.id.toString()) {
                    created.id to existing.id
                } else {
                    existing.id to created.id
                }
                if (!checkedPairs.add(key)) continue
                if (areRailsConnected(created, existing)) continue
                if (closestEndpointDistance(created, existing) > Curve.TOLERANCE) continue
                runCatching {
                    CoreRail.connect(created, existing)
                    connected++
                }
            }
        }
        return connected
    }

    private fun areRailsConnected(a: CoreRail, b: CoreRail): Boolean =
        a.viewAB.nextRails.any { it.rail == b } ||
            a.viewBA.nextRails.any { it.rail == b } ||
            b.viewAB.nextRails.any { it.rail == a } ||
            b.viewBA.nextRails.any { it.rail == a }

    private fun closestEndpointDistance(a: CoreRail, b: CoreRail): Double {
        val aPoints = listOf(a.viewAB.startPoint.position, a.viewAB.endPoint.position)
        val bPoints = listOf(b.viewAB.startPoint.position, b.viewAB.endPoint.position)
        return aPoints.minOf { pa -> bPoints.minOf { pb -> pa.distance(pb) } }
    }

    private fun createCurveBetweenAnchors(from: Anchor, to: Anchor, radius: Double = 0.0): PrimeCurve? {
        val result = RailCurveFactory.build(from, to, radius)
        if (!result.isSuccess) {
            plugin.logger.warning("RailWand curve build failed: ${result.error ?: "unknown error"}")
        }
        return result.curve
    }

    // ═══════════════════════════════════════════
    // 编辑模式
    // ═══════════════════════════════════════════

    private fun formatEditNumber(value: Double, digits: Int = 3): String =
        "% .${digits}f".format(Locale.US, value).trim()

    private fun parseEditDouble(value: String?, fallback: Double, label: String, player: Player): Double? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        return raw.toDoubleOrNull() ?: run {
            player.sendActionBar(Component.text("$label 不是有效数字: $raw", NamedTextColor.RED))
            null
        }
    }

    internal fun editAnchor(player: Player, anchor: Anchor, state: WandPlayerState) {
        // 将锚点当前值写入 session，然后打开 ConfigAPI Dialog
        val session = configUI.getPlayerSession(player.uniqueId)
        val yaw = AnchorGeometry.yawDegrees(anchor.tangent)
        val pitch = AnchorGeometry.pitchDegrees(anchor.tangent)
        val roll = AnchorGeometry.rollDegrees(anchor)
        session.setValue(editKeys.anchorId, anchor.id)
        session.setValue(editKeys.posX, formatEditNumber(anchor.position.x()))
        session.setValue(editKeys.posY, formatEditNumber(anchor.position.y()))
        session.setValue(editKeys.posZ, formatEditNumber(anchor.position.z()))
        session.setValue(editKeys.tangentYaw, formatEditNumber(yaw, digits = 1))
        session.setValue(editKeys.tangentPitch, formatEditNumber(pitch, digits = 1))
        session.setValue(editKeys.roll, formatEditNumber(roll, digits = 1))
        dialogRenderer.showConfigDialog(player, "anchor.edit") { p ->
            // 确认回调: 读取新值并更新锚点
            val s = configUI.getPlayerSession(p.uniqueId)
            val x = parseEditDouble(s.getValue(editKeys.posX), anchor.position.x(), "X 坐标", p) ?: return@showConfigDialog
            val y = parseEditDouble(s.getValue(editKeys.posY), anchor.position.y(), "Y 坐标", p) ?: return@showConfigDialog
            val z = parseEditDouble(s.getValue(editKeys.posZ), anchor.position.z(), "Z 坐标", p) ?: return@showConfigDialog
            val newYaw = parseEditDouble(s.getValue(editKeys.tangentYaw), yaw, "Yaw", p) ?: return@showConfigDialog
            val newPitch = parseEditDouble(s.getValue(editKeys.tangentPitch), pitch, "Pitch", p) ?: return@showConfigDialog
            val newRoll = parseEditDouble(s.getValue(editKeys.roll), roll, "Roll", p) ?: return@showConfigDialog
            val newPos = Vector3d(x, y, z)
            val newTan = AnchorGeometry.tangentFromYawPitch(newYaw, newPitch)
            val updated = AnchorGeometry.withRoll(anchor.withPosition(newPos).withTangent(newTan), newRoll)
            if (isRailEndpointAnchorId(anchor.id)) {
                val newEndpointId = rebuildRailForEndpointEdit(p, anchor.id, updated) ?: return@showConfigDialog
                state.selectedAnchors.clear()
                state.selectedAnchors.add(newEndpointId)
                refreshAllAnchorsForPlayer(p)
                p.sendActionBar(Component.text("轨道端点已更新", NamedTextColor.GREEN))
            } else {
                updateAnchor(player.world, updated)
                removeAnchorDisplay(player, anchor.id)
                spawnAnchorDisplay(player, updated)
                p.sendActionBar(Component.text("锚点 ${anchor.id} 已更新", NamedTextColor.GREEN))
            }
        }
    }

    internal fun editSelectedAnchors(player: Player, state: WandPlayerState) {
        val anchors = state.selectedAnchors.mapNotNull { getAnchor(player.world, it) }
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
                    updateAnchor(p.world, updated)
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

    internal fun deleteAnchor(player: Player, anchorId: String, state: WandPlayerState) {
        if (isRailEndpointAnchorId(anchorId)) {
            player.sendActionBar(Component.text("轨道端点锚点不能单独删除，请删除轨道", NamedTextColor.YELLOW))
            return
        }
        val anchor = getAnchor(player.world, anchorId) ?: run {
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
        removeAnchor(player.world, anchorId)
        state.selectedAnchors.remove(anchorId)

        pushUndo(player, WandAction.DeleteAnchor(player.world.uid, savedData))
        player.sendActionBar(Component.text("已删除锚点 $anchorId", NamedTextColor.GREEN))
    }

    // ═══════════════════════════════════════════
    // 辅助方法 — 临时锚点存储/查找
    // ═══════════════════════════════════════════

    private fun anchorsFor(world: World): MutableMap<String, Anchor> =
        transientAnchors.getOrPut(world.uid) { mutableMapOf() }

    private fun addAnchor(world: World, anchor: Anchor) {
        anchorsFor(world)[anchor.id] = anchor
    }

    private fun getAnchor(world: World, id: String): Anchor? =
        anchorsFor(world)[id] ?: getRailEndpointAnchor(world, id)

    private fun getAllAnchors(world: World): List<Anchor> =
        anchorsFor(world).values.toList() + getRailEndpointAnchors(world)

    private fun updateAnchor(world: World, anchor: Anchor) {
        if (!isRailEndpointAnchorId(anchor.id)) {
            anchorsFor(world)[anchor.id] = anchor
        }
    }

    private fun removeAnchor(world: World, id: String) {
        if (!isRailEndpointAnchorId(id)) {
            anchorsFor(world).remove(id)
        }
    }

    private fun railEndpointAnchorId(railId: UUID, end: Char): String = "rail:$railId:$end"

    private fun isRailEndpointAnchorId(id: String): Boolean = parseRailEndpointAnchorId(id) != null

    private fun parseRailEndpointAnchorId(id: String): Pair<UUID, Char>? {
        val parts = id.split(':')
        if (parts.size != 3 || parts[0] != "rail") return null
        val end = parts[2].singleOrNull()?.uppercaseChar() ?: return null
        if (end != 'A' && end != 'B') return null
        val railId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
        return railId to end
    }

    private fun getRailEndpointAnchor(world: World, id: String): Anchor? {
        val (railId, end) = parseRailEndpointAnchorId(id) ?: return null
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[world] as? CoreWorld ?: return null
        val rail = coreWorld.getRail(railId) ?: return null
        val point = if (end == 'A') rail.viewAB.startPoint else rail.viewAB.endPoint
        return AnchorGeometry.withRoll(
            Anchor(
                id = id,
                position = Vector3d(point.position),
                tangent = Vector3d(point.tangent),
                worldId = world.uid.toString()
            ),
            AnchorGeometry.rollDegreesFromNormal(point.tangent, point.normal)
        )
    }

    private fun getRailEndpointAnchors(world: World): List<Anchor> {
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[world] as? CoreWorld ?: return emptyList()
        return RailRenderMetaStore.load(world).flatMap { meta ->
            val rail = coreWorld.getRail(meta.railId) ?: return@flatMap emptyList()
            listOfNotNull(
                getRailEndpointAnchor(world, railEndpointAnchorId(rail.id, 'A')),
                getRailEndpointAnchor(world, railEndpointAnchorId(rail.id, 'B'))
            )
        }
    }

    internal fun findNearestAnchor(player: Player, maxDistance: Double): Anchor? {
        val playerPos = player.location.toVector().toVector3d()
        return getAllAnchors(player.world)
            .filter { it.position.distance(playerPos) <= maxDistance }
            .minByOrNull { it.position.distance(playerPos) }
    }

    // ═══════════════════════════════════════════
    // 辅助方法 — 位置/朝向吸附
    // ═══════════════════════════════════════════

    private fun getAnchorPlacementPosition(
        player: Player,
        state: WandPlayerState,
        event: PlayerInteractEvent
    ): Vector3dc? {
        val target = when {
            event.action == Action.RIGHT_CLICK_BLOCK -> {
                val block = event.clickedBlock ?: return null
                block to event.blockFace
            }
            event.action == Action.RIGHT_CLICK_AIR -> {
                val hit = player.rayTraceBlocks(128.0) ?: return null
                val block = hit.hitBlock ?: return null
                block to (hit.hitBlockFace ?: BlockFace.UP)
            }
            else -> return null
        }

        if (!state.snapToBlock) {
            val hit = player.rayTraceBlocks(128.0)
            if (hit?.hitBlock == target.first) {
                return hit.hitPosition.toVector3d()
            }
        }

        return getBlockFaceCenter(target.first, target.second)
    }

    private fun getBlockFaceCenter(block: Block, face: BlockFace): Vector3dc = Vector3d(
        block.x + 0.5 + face.modX * 0.5,
        block.y + 0.5 + face.modY * 0.5,
        block.z + 0.5 + face.modZ * 0.5
    )

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

    // ═══════════════════════════════════════════
    // 锚点展示实体
    // ═══════════════════════════════════════════

    private fun spawnAnchorDisplay(player: Player, anchor: Anchor) {
        val worldId = player.world.uid.toString()
        val entityIds = anchorEntities
            .getOrPut(worldId) { mutableMapOf() }
            .getOrPut(anchor.id) { mutableSetOf() }

        val anchorEntity = spawnAnchorDisplayEntity(
            player = player,
            anchor = anchor,
            position = anchor.position,
            material = ANCHOR_DISPLAY_MATERIAL,
            scale = 1.1f,
            showName = true
        )
        entityIds.add(anchorEntity.uniqueId)

        val direction = Vector3d(anchor.tangent)
        if (direction.lengthSquared() > 1e-9) {
            direction.normalize()
            val directionPosition = Vector3d(anchor.position).fma(1.35, direction)
            val directionEntity = spawnAnchorDisplayEntity(
                player = player,
                anchor = anchor,
                position = directionPosition,
                material = ANCHOR_DIRECTION_MATERIAL,
                scale = 0.8f,
                showName = false
            )
            entityIds.add(directionEntity.uniqueId)
        }
    }

    private fun spawnAnchorDisplayEntity(
        player: Player,
        anchor: Anchor,
        position: Vector3dc,
        material: Material,
        scale: Float,
        showName: Boolean
    ): ItemDisplay {
        val displayEntity = player.world.spawn(
            Location(player.world, position.x(), position.y(), position.z()),
            ItemDisplay::class.java
        ) { entity ->
            entity.setItemStack(ItemStack(material))
            entity.isPersistent = false
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            entity.customName(Component.text(anchor.id, NamedTextColor.RED))
            entity.isCustomNameVisible = showName
            entity.transformation = Transformation(
                entity.transformation.translation,
                entity.transformation.leftRotation,
                org.joml.Vector3f(scale, scale, scale),
                entity.transformation.rightRotation
            )
            entity.isVisibleByDefault = false
        }
        player.showEntity(plugin, displayEntity)
        return displayEntity
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
    internal fun updateAnchorDisplay(player: Player) {
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
        getAllAnchors(player.world).forEach { spawnAnchorDisplay(player, it) }
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
        removeAnchor(world, action.anchorId)
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
        addAnchor(player.world, anchor)
        spawnAnchorDisplay(player, anchor)
    }

    private fun redoDeleteAnchor(player: Player, action: WandAction.DeleteAnchor) {
        removeAnchorDisplay(player, action.anchor.id)
        removeAnchor(player.world, action.anchor.id)
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

    internal fun clearAnchorSelection(player: Player, state: WandPlayerState) {
        if (state.selectedAnchors.isEmpty()) return
        state.selectedAnchors.clear()
        updateAnchorDisplay(player)
        player.sendActionBar(Component.text("已取消选择锚点", NamedTextColor.GRAY))
    }

    /** Convert Bukkit Vector to JOML Vector3d */
    private fun org.bukkit.util.Vector.toVector3d(): Vector3d = Vector3d(x, y, z)
}
