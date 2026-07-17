package work.art1st.mineRails.bukkit.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import work.art1st.mineRails.basic.curve.RailCurveBuildOptions
import work.art1st.mineRails.basic.curve.RailCurveFactory
import work.art1st.mineRails.basic.curve.RailCurveTemplateType
import work.art1st.mineRails.basic.curve.StandardTurnout
import work.art1st.mineRails.basic.curve.StandardTurnoutFactory
import work.art1st.mineRails.basic.curve.SuperelevationConfig
import work.art1st.mineRails.basic.impl.config.ConfigUIRegistry
import work.art1st.mineRails.basic.pathFinding.basicWorldDataOrNull
import work.art1st.mineRails.basic.rail.RailAutoConnector
import work.art1st.mineRails.basic.safety.RailSpeedCalculator
import work.art1st.mineRails.basic.safety.RailSpeedLimits
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.logic.RailVisualBootstrap
import work.art1st.mineRails.bukkit.persistence.PersistedRailRenderMeta
import work.art1st.mineRails.bukkit.persistence.RailRenderMetaStore
import work.art1st.mineRails.bukkit.ui.dialog.DialogRenderer
import work.art1st.mineRails.bukkit.util.BukkitAsyncTasks
import work.art1st.mineRails.bukkit.util.HeavyRailQueries
import work.art1st.mineRails.bukkit.util.PlayerOperationGate
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.rail.ParallelRailMetadata
import work.art1st.mineRails.core.api.physics.rail.RailCurveKind
import work.art1st.mineRails.core.api.physics.rail.RailGeometryMetadata
import work.art1st.mineRails.core.api.physics.rail.RailMetadata
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.api.util.annotation.Heavy
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailTopologyEditor
import work.art1st.mineRails.core.physics.rail.RailView
import java.util.*
import kotlin.math.*

/**
 * 右键点确定一个垂直于起点切线的平面；直线终点是起点切线与该平面的交点。
 */
internal fun straightTerminalPosition(
    startPosition: Vector3dc,
    startTangent: Vector3dc,
    planePoint: Vector3dc
): Vector3d {
    val tangent = Vector3d(startTangent)
    if (tangent.lengthSquared() < 1.0e-12) return Vector3d(startPosition)
    tangent.normalize()
    val distance = Vector3d(planePoint).sub(startPosition).dot(tangent)
    return Vector3d(startPosition).fma(distance, tangent)
}

/** 终点复用起点的切线表示；Factory 对 T/-T 做无向归一化。 */
internal fun straightTerminalTangent(startTangent: Vector3dc): Vector3d =
    Vector3d(startTangent)

// ═══════════════════════════════════════════════════════════════
// 工作模式
// ═══════════════════════════════════════════════════════════════

enum class WandMode(override val displayName: String, override val description: String) : WandModeSpec {
    CREATE("创建模式", "右键创建锚点 | 左键选择锚点 | Shift+F 切换模式"),
    CONNECT("连接模式", "选择两端锚点后右键连接 | Shift+F 切换模式"),
    TURNOUT("道岔模式", "靠近锚点右键创建标准道岔侧线 | Shift+F 切换模式"),
    EDIT("编辑模式", "右键编辑锚点/轨道 | 左键选择 | Shift+F 切换模式"),
    SPLIT("分割模式", "右键选择/确认分割 | 左键分割锚点合并 | Shift+F 切换模式"),
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

internal class WandActionHistory(private val limit: Int = 10) {
    private val undoActions = mutableListOf<WandAction>()
    private val redoActions = mutableListOf<WandAction>()

    internal val undoSize: Int get() = undoActions.size
    internal val redoSize: Int get() = redoActions.size

    fun record(action: WandAction) {
        appendBounded(undoActions, action)
        redoActions.clear()
    }

    fun takeUndo(): WandAction? {
        val action = undoActions.removeLastOrNull() ?: return null
        appendBounded(redoActions, action)
        return action
    }

    fun takeRedo(): WandAction? {
        val action = redoActions.removeLastOrNull() ?: return null
        appendBounded(undoActions, action)
        return action
    }

    fun discardCreatedAnchors(anchorIds: Set<String>) {
        val retained = undoActions.mapNotNull { it.withoutCreatedAnchors(anchorIds) }
        undoActions.clear()
        undoActions.addAll(retained.takeLast(limit))
    }

    private fun appendBounded(actions: MutableList<WandAction>, action: WandAction) {
        actions.add(action)
        if (actions.size > limit) actions.removeAt(0)
    }

    private fun WandAction.withoutCreatedAnchors(anchorIds: Set<String>): WandAction? = when (this) {
        is WandAction.CreateAnchor -> takeUnless { anchorId in anchorIds }
        is WandAction.MultiAction -> actions.mapNotNull { it.withoutCreatedAnchors(anchorIds) }
            .takeIf { it.isNotEmpty() }
            ?.let { WandAction.MultiAction(it) }
        else -> this
    }
}

// ═══════════════════════════════════════════════════════════════
// 玩家状态
// ═══════════════════════════════════════════════════════════════

data class WandPlayerState(
    override var mode: WandMode = WandMode.CREATE,
    /** 选中的锚点 ID 列表 */
    val selectedAnchors: MutableList<String> = mutableListOf(),
    /** 选中的轨道 UUID 列表 */
    val selectedRails: MutableList<UUID> = mutableListOf(),
    /** 删除模式下：待删除的锚点 ID（null = 无待删除） */
    var pendingDeleteAnchor: String? = null,
    /** 删除模式下：待删除的轨道 UUID（null = 无待删除） */
    var pendingDeleteRail: UUID? = null,
    /** 分割模式第一次右键选中的轨道。 */
    var pendingSplitRail: UUID? = null,
    /** 分割点在轨道 AB 曲线上的弧长。 */
    var pendingSplitArcLength: Double? = null,
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
    var curveRadius: Double = 0.0,
    /** 曲线模板。AUTO 会按端点几何自动分类；STRAIGHT 沿起点切线投影终点。 */
    var curveTemplate: RailCurveTemplateType = RailCurveTemplateType.AUTO,
    /** 标准道岔号数，同时确定侧线半径和限速。 */
    var turnoutStandard: StandardTurnout = StandardTurnout.N9,
    /** 新建轨道的设计时速。 */
    var designSpeedKmH: Double = 80.0,
    /** 运营限速计算允许的欠超高。 */
    var allowableCantDeficiencyMm: Double = 75.0,
    /** 五段式曲线是否按设计时速生成外轨超高。 */
    var automaticSuperelevation: Boolean = true,
    /** 自动超高上限。 */
    var maxSuperelevationMm: Double = 150.0
) : WandState<WandMode>

@Serializable
internal data class SavedRailWandStateV1(
    val mode: String = WandMode.CREATE.name,
    val selectedAnchors: List<String> = emptyList(),
    val selectedRails: List<String> = emptyList(),
    val pendingDeleteAnchor: String? = null,
    val pendingDeleteRail: String? = null,
    val pendingSplitRail: String? = null,
    val pendingSplitArcLength: Double? = null,
    val parallelCount: Int = 1,
    val parallelSpacing: Double = 4.5,
    val snapToAngle: Boolean = true,
    val snapToBlock: Boolean = true,
    val openConfigAfterCreate: Boolean = false,
    val autoDecorate: Boolean = true,
    val curveRadius: Double = 0.0,
    val curveTemplate: String = RailCurveTemplateType.AUTO.name,
    val turnoutStandard: String = StandardTurnout.N9.name,
    val designSpeedKmH: Double = 80.0,
    val allowableCantDeficiencyMm: Double = 75.0,
    val automaticSuperelevation: Boolean = true,
    val maxSuperelevationMm: Double = 150.0
)

internal object RailWandStateCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: WandPlayerState): String = json.encodeToString(
        SavedRailWandStateV1(
            mode = state.mode.name,
            selectedAnchors = state.selectedAnchors.toList(),
            selectedRails = state.selectedRails.map(UUID::toString),
            pendingDeleteAnchor = state.pendingDeleteAnchor,
            pendingDeleteRail = state.pendingDeleteRail?.toString(),
            pendingSplitRail = state.pendingSplitRail?.toString(),
            pendingSplitArcLength = state.pendingSplitArcLength,
            parallelCount = state.parallelCount,
            parallelSpacing = state.parallelSpacing,
            snapToAngle = state.snapToAngle,
            snapToBlock = state.snapToBlock,
            openConfigAfterCreate = state.openConfigAfterCreate,
            autoDecorate = state.autoDecorate,
            curveRadius = state.curveRadius,
            curveTemplate = state.curveTemplate.name,
            turnoutStandard = state.turnoutStandard.name,
            designSpeedKmH = state.designSpeedKmH,
            allowableCantDeficiencyMm = state.allowableCantDeficiencyMm,
            automaticSuperelevation = state.automaticSuperelevation,
            maxSuperelevationMm = state.maxSuperelevationMm
        )
    )

    fun decode(encoded: String): WandPlayerState? = runCatching {
        val saved = json.decodeFromString<SavedRailWandStateV1>(encoded)
        WandPlayerState(
            mode = enumValueOrDefault(saved.mode, WandMode.CREATE),
            selectedAnchors = saved.selectedAnchors.toMutableList(),
            selectedRails = saved.selectedRails.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toMutableList(),
            pendingDeleteAnchor = saved.pendingDeleteAnchor,
            pendingDeleteRail = saved.pendingDeleteRail?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            pendingSplitRail = saved.pendingSplitRail?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            pendingSplitArcLength = saved.pendingSplitArcLength?.takeIf { it.isFinite() && it > 0.0 },
            parallelCount = saved.parallelCount.coerceIn(1, 10),
            parallelSpacing = saved.parallelSpacing.coerceIn(1.0, 20.0),
            snapToAngle = saved.snapToAngle,
            snapToBlock = saved.snapToBlock,
            openConfigAfterCreate = saved.openConfigAfterCreate,
            autoDecorate = saved.autoDecorate,
            curveRadius = saved.curveRadius.coerceAtLeast(0.0),
            curveTemplate = enumValueOrDefault(saved.curveTemplate, RailCurveTemplateType.AUTO),
            turnoutStandard = enumValueOrDefault(saved.turnoutStandard, StandardTurnout.N9),
            designSpeedKmH = saved.designSpeedKmH.coerceIn(5.0, 500.0),
            allowableCantDeficiencyMm = saved.allowableCantDeficiencyMm.coerceIn(0.0, 300.0),
            automaticSuperelevation = saved.automaticSuperelevation,
            maxSuperelevationMm = saved.maxSuperelevationMm.coerceIn(0.0, 300.0)
        )
    }.getOrNull()

    private inline fun <reified E : Enum<E>> enumValueOrDefault(value: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == value } ?: fallback
}

// ═══════════════════════════════════════════════════════════════
// RailWand 主类
// ═══════════════════════════════════════════════════════════════

class RailWand(plugin: BukkitPlugin) : AbstractWand<WandPlayerState, WandMode>(
    plugin = plugin,
    wandKey = "mineRails_railWand",
    material = Material.STICK,
    itemDisplayName = Component.text("RailWand", NamedTextColor.GOLD),
    itemLore = listOf(
        Component.text("Shift+F 切换模式", NamedTextColor.GRAY),
        Component.text("Shift+右键 打开设置", NamedTextColor.GRAY)
    ),
    modes = WandMode.entries.toList()
) {

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

    /** ConfigAPI 注册中心 + Dialog 渲染器 */
    private val configUI = ConfigUIRegistry()
    private val dialogRenderer = DialogRenderer(configUI)

    /** Wand 设置 ConfigKeys (per-setting, 每个玩家独立 session) */
    private lateinit var wandKeys: WandConfigKeys

    /** Per-player transient anchor marker entities: player UUID -> (anchorId -> Set<entityId>). */
    private val anchorEntities = mutableMapOf<UUID, MutableMap<String, MutableSet<UUID>>>()
    private val heavyOperationGate = PlayerOperationGate()

    /** 每名玩家最近 10 次有效操作。 */
    private val actionHistories = mutableMapOf<UUID, WandActionHistory>()

    private data class SplitPreviewTarget(
        val worldId: UUID,
        val railId: UUID,
        val arcLength: Double,
        val position: Vector3d
    )

    private val splitPreviewTargets = mutableMapOf<UUID, SplitPreviewTarget>()
    private val splitPreviewRequests = mutableSetOf<UUID>()

    private val createMode = RailWandCreateMode(this)
    private val connectMode = RailWandConnectMode(this)
    private val turnoutMode = RailWandTurnoutMode(this)
    private val editMode = RailWandEditMode(this)
    private val splitMode = RailWandSplitMode(this)
    private val deleteMode = RailWandDeleteMode(this)
    private val decorateMode = RailWandDecorateMode()

    override fun createDefaultState(): WandPlayerState = WandPlayerState()

    override fun encodeState(state: WandPlayerState): String = RailWandStateCodec.encode(state)

    override fun decodeState(encoded: String): WandPlayerState? = RailWandStateCodec.decode(encoded)

    override fun onBeforeRegisterEvents() {
        registerWandConfig()
    }

    override fun onAfterRegisterEvents() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { updateSplitPreviews() }, 1L, 4L)
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
        val curveTemplate = ConfigKey.build<RailCurveTemplateType>("wand.curveTemplate") {
            label = "曲线模板"
            description = "自动/直线/五段式/S弯。直线终点由右键点所在的切线垂直平面确定。"
            defaultValue(RailCurveTemplateType.AUTO)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(
                RailCurveTemplateType.entries,
                RailCurveTemplateType.entries.associateWith { it.displayName }
            )
            group = "wand.settings"
            order = 7
        }
        val turnoutStandard = ConfigKey.build<StandardTurnout>("wand.turnoutStandard") {
            label = "标准道岔号数"
            description = "道岔号数同时确定侧线半径和侧线限速。"
            defaultValue(StandardTurnout.N9)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(
                StandardTurnout.entries,
                StandardTurnout.entries.associateWith { it.displayName }
            )
            group = "wand.settings"
            order = 8
        }
        val designSpeedKmH = ConfigKey.build<Double>("wand.designSpeedKmH") {
            label = "设计时速"
            description = "新建轨道的设计时速，并用于自动计算曲线超高。"
            defaultValue(80.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(5.0, 500.0, 5.0, "km/h")
            group = "wand.settings"
            order = 9
        }
        val allowableCantDeficiencyMm = ConfigKey.build<Double>("wand.allowableCantDeficiencyMm") {
            label = "允许欠超高"
            description = "根据最终曲率和实际超高计算运营限速。"
            defaultValue(75.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 300.0, 5.0, "mm")
            group = "wand.settings"
            order = 10
        }
        val automaticSuperelevation = ConfigKey.build<Boolean>("wand.automaticSuperelevation") {
            label = "自动外轨超高"
            description = "五段式曲线按设计时速和半径自动生成外轨超高。"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 11
        }
        val maxSuperelevationMm = ConfigKey.build<Double>("wand.maxSuperelevationMm") {
            label = "最大外轨超高"
            description = "自动外轨超高的上限。"
            defaultValue(150.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 300.0, 5.0, "mm")
            group = "wand.settings"
            order = 12
        }
    }

    class RailEditKeys {
        val designSpeedKmH = ConfigKey.build<Double>("rail.edit.designSpeedKmH") {
            label = "设计时速"
            description = "该轨道段的设计时速。"
            defaultValue(80.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(5.0, 500.0, 5.0, "km/h")
            group = "rail.edit"
            order = 0
        }
        val curveRadius = ConfigKey.build<Double>("rail.edit.curveRadius") {
            label = "曲线半径"
            description = "0 表示自动选择；S弯中作为最小半径。并行线路会整组重建。"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 10000.0, 10.0, "m")
            group = "rail.edit"
            order = 1
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
    private lateinit var railEditKeys: RailEditKeys

    private fun registerWandConfig() {
        wandKeys = WandConfigKeys()
        editKeys = AnchorEditKeys()
        railEditKeys = RailEditKeys()

        configUI.register(ConfigGroup(
            "wand.settings.general",
            "RailWand 设置 1/2",
            description = "锚点吸附、并行线路与显示设置",
            items = mutableListOf(
                ConfigItemImpl(wandKeys.parallelCount),
                ConfigItemImpl(wandKeys.parallelSpacing),
                ConfigItemImpl(wandKeys.snapToAngle),
                ConfigItemImpl(wandKeys.snapToBlock),
                ConfigItemImpl(wandKeys.openConfigAfterCreate),
                ConfigItemImpl(wandKeys.autoDecorate)
            )
        ))
        configUI.register(ConfigGroup(
            "wand.settings.geometry",
            "RailWand 设置 2/2",
            description = "普通曲线、标准道岔与基础设施参数",
            items = mutableListOf(
                ConfigItemImpl(wandKeys.curveRadius),
                ConfigItemImpl(wandKeys.curveTemplate),
                ConfigItemImpl(wandKeys.turnoutStandard),
                ConfigItemImpl(wandKeys.designSpeedKmH),
                ConfigItemImpl(wandKeys.allowableCantDeficiencyMm),
                ConfigItemImpl(wandKeys.automaticSuperelevation),
                ConfigItemImpl(wandKeys.maxSuperelevationMm)
            )
        ))

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

        configUI.register(ConfigGroup(
            id = "rail.edit",
            label = "编辑轨道",
            description = "修改轨道段的基础设施属性。修改曲线半径会重建轨道。",
            items = mutableListOf(
                ConfigItemImpl(railEditKeys.designSpeedKmH),
                ConfigItemImpl(railEditKeys.curveRadius)
            )
        ))

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
        state.curveTemplate = session.getValue(wandKeys.curveTemplate)
            ?: (configUI.get(wandKeys.curveTemplate) ?: RailCurveTemplateType.AUTO)
        state.turnoutStandard = session.getValue(wandKeys.turnoutStandard)
            ?: (configUI.get(wandKeys.turnoutStandard) ?: StandardTurnout.N9)
        state.designSpeedKmH = session.getValue(wandKeys.designSpeedKmH)
            ?: (configUI.get(wandKeys.designSpeedKmH) ?: 80.0)
        state.allowableCantDeficiencyMm = session.getValue(wandKeys.allowableCantDeficiencyMm)
            ?: (configUI.get(wandKeys.allowableCantDeficiencyMm) ?: 75.0)
        state.automaticSuperelevation = session.getValue(wandKeys.automaticSuperelevation)
            ?: (configUI.get(wandKeys.automaticSuperelevation) ?: true)
        state.maxSuperelevationMm = session.getValue(wandKeys.maxSuperelevationMm)
            ?: (configUI.get(wandKeys.maxSuperelevationMm) ?: 150.0)
        persistState(player, state)
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
        session.setValue(wandKeys.curveTemplate, state.curveTemplate)
        session.setValue(wandKeys.turnoutStandard, state.turnoutStandard)
        session.setValue(wandKeys.designSpeedKmH, state.designSpeedKmH)
        session.setValue(wandKeys.allowableCantDeficiencyMm, state.allowableCantDeficiencyMm)
        session.setValue(wandKeys.automaticSuperelevation, state.automaticSuperelevation)
        session.setValue(wandKeys.maxSuperelevationMm, state.maxSuperelevationMm)
    }

    override fun clearSelection(player: Player, state: WandPlayerState) {
        clearSelection(state)
        updateAnchorDisplay(player)
    }

    override fun showSettings(player: Player, state: WandPlayerState) {
        showModeSettings(player, state)
    }

    override fun handleWandInteract(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        dispatchModeAction(player, state, event)
    }

    override fun onPlayerQuit(player: Player, state: WandPlayerState) {
        removeAllAnchorDisplays(player)
        actionHistories.remove(player.uniqueId)
        splitPreviewTargets.remove(player.uniqueId)
        splitPreviewRequests.remove(player.uniqueId)
        heavyOperationGate.clear(player.uniqueId)
        configUI.removePlayerSession(player.uniqueId)
    }

    // ═══════════════════════════════════════════
    // 模式分发
    // ═══════════════════════════════════════════

    private fun dispatchModeAction(player: Player, state: WandPlayerState, event: PlayerInteractEvent) {
        when (state.mode) {
            WandMode.CREATE -> createMode.handle(player, state, event)
            WandMode.CONNECT -> connectMode.handle(player, state, event)
            WandMode.TURNOUT -> turnoutMode.handle(player, state, event)
            WandMode.EDIT -> editMode.handle(player, state, event)
            WandMode.SPLIT -> splitMode.handle(player, state, event)
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
        val straightStarts = getCreateModeStraightStarts(player, state)
        val newAnchors = if (straightStarts != null) {
            createStraightTerminalAnchorGroup(player, position, straightStarts)
        } else {
            createParallelAnchorGroup(player, position, getSnappedTangent(player, state), state)
        }
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
                connectSelectedAnchors(player, state)
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
        placementTangent: Vector3dc,
        state: WandPlayerState
    ): List<Anchor> {
        val count = state.parallelCount.coerceAtLeast(1)
        val right = perpendicularRight(placementTangent)
        return (0 until count).map { index ->
            val offset = (index - (count - 1) / 2.0) * state.parallelSpacing
            val position = Vector3d(center).fma(offset, right)
            val anchorId = nextAnchorId()
            Anchor(anchorId, position, Vector3d(placementTangent), worldId = player.world.uid.toString()).also { anchor ->
                addAnchor(player.world, anchor)
                spawnAnchorDisplay(player, anchor)
            }
        }
    }

    /**
     * 直线的第二次右键只选择终点横断面：每条起点切线与经过右键点、
     * 垂直于该切线的平面相交，得到对应终点；终点复用起点切线表示。
     */
    private fun getCreateModeStraightStarts(player: Player, state: WandPlayerState): List<Anchor>? {
        if (state.curveTemplate != RailCurveTemplateType.STRAIGHT) return null
        val count = state.parallelCount.coerceAtLeast(1)
        if (state.selectedAnchors.size != count) return null
        return state.selectedAnchors.map { getAnchor(player.world, it) ?: return null }
    }

    private fun createStraightTerminalAnchorGroup(
        player: Player,
        planePoint: Vector3dc,
        starts: List<Anchor>
    ): List<Anchor> = starts.map { start ->
        val anchor = Anchor(
            id = nextAnchorId(),
            position = straightTerminalPosition(start.position, start.tangent, planePoint),
            tangent = straightTerminalTangent(start.tangent),
            worldId = player.world.uid.toString()
        )
        addAnchor(player.world, anchor)
        spawnAnchorDisplay(player, anchor)
        anchor
    }

    private fun perpendicularRight(tangent: Vector3dc): Vector3d {
        val horizontal = Vector3d(tangent.x(), 0.0, tangent.z())
        if (horizontal.lengthSquared() < 1e-9) return Vector3d(1.0, 0.0, 0.0)
        return horizontal.normalize().cross(Vector3d(0.0, 1.0, 0.0)).normalize()
    }

    // ═══════════════════════════════════════════
    // 连接模式
    // ═══════════════════════════════════════════

    private data class CurveBuildSettingsSnapshot(
        val parallelCount: Int,
        val parallelSpacing: Double,
        val curveRadius: Double,
        val curveTemplate: RailCurveTemplateType,
        val designSpeedKmH: Double,
        val allowableCantDeficiencyMm: Double,
        val automaticSuperelevation: Boolean,
        val maxSuperelevationMm: Double
    )

    private data class BuiltCurve(
        val curve: PrimeCurve,
        val speedLimits: RailSpeedLimits
    )

    private data class RailEndpointRebuildInput(
        val railId: UUID,
        val movedEnd: Char,
        val oldStart: Anchor,
        val oldEnd: Anchor,
        val oldMetadata: RailMetadata
    )

    @OptIn(Heavy::class)
    internal fun createTurnoutAtAnchor(player: Player, state: WandPlayerState, anchor: Anchor) {
        val towards = Vector3d(player.location.x, anchor.position.y(), player.location.z).sub(anchor.position)
        if (towards.lengthSquared() < 1.0e-6) {
            player.sendActionBar(Component.text("玩家与锚点的水平方向不明确", NamedTextColor.RED))
            return
        }
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "道岔创建")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val standard = state.turnoutStandard
        val cantDeficiencyMeters = state.allowableCantDeficiencyMm / 1000.0
        val derailAcceleration = plugin.mineRailsConfig.derailLateralAccelerationMetersPerSecondSquared
        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailWand.createTurnoutAtAnchor",
            supplier = {
                StandardTurnoutFactory.build(anchor, towards, standard)?.let { result ->
                    result to RailSpeedCalculator.calculate(
                        curve = result.curve,
                        requestedLimitMetersPerSecond = standard.divergingSpeedKmH / 3.6,
                        allowableCantDeficiencyMeters = cantDeficiencyMeters,
                        derailLateralAccelerationMetersPerSecondSquared = derailAcceleration
                    )
                }
            },
            onSuccess = { builtTurnout ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                if (builtTurnout == null || getAnchor(livePlayer.world, anchor.id) != anchor) {
                    livePlayer.sendActionBar(Component.text("锚点已变化或方向无效，请重试", NamedTextColor.RED))
                    return@supply
                }
                val (result, speedLimits) = builtTurnout
                val coreWorld = getCoreWorld(livePlayer) ?: return@supply
                val existingRails = RailRenderMetaStore.load(livePlayer.world)
                    .mapNotNull { coreWorld.getRail(it.railId) }
                val rail = CoreRail(
                    coreWorld,
                    result.curve,
                    mutableListOf(),
                    mutableListOf(),
                    RailMetadata(
                        designSpeedMetersPerSecond = standard.divergingSpeedKmH / 3.6,
                        speedLimitMetersPerSecond = speedLimits.operatingLimitMetersPerSecond,
                        derailSpeedMetersPerSecond = speedLimits.derailSpeedMetersPerSecond,
                        geometry = RailGeometryMetadata(
                            curveKind = RailCurveKind.FIVE_SEGMENT,
                            curveRadiusMeters = standard.radiusMeters,
                            automaticSuperelevation = false,
                            allowableCantDeficiencyMeters = cantDeficiencyMeters
                        )
                    )
                )
                val renderMeta = RailVisualBootstrap.registerRail(rail)
                RailRenderMetaStore.upsertRail(livePlayer.world, renderMeta)
                val autoConnected = connectCreatedRailsToExisting(listOf(rail), existingRails)
                pushUndo(livePlayer, WandAction.CreateRail(livePlayer.world.uid, rail.id, "standard"))
                val liveState = getState(livePlayer)
                liveState.selectedAnchors.clear()
                liveState.selectedAnchors.add(railEndpointAnchorId(rail.id, 'B'))
                persistState(livePlayer, liveState)
                updateAnchorDisplay(livePlayer)
                livePlayer.sendMessage(Component.text(
                    "✓ 创建 ${standard.number} 号道岔侧线，限速 ${(speedLimits.operatingLimitMetersPerSecond * 3.6).toInt()} km/h，连接 $autoConnected 条",
                    NamedTextColor.GREEN
                ))
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("RailWand turnout build failed: ${error.message ?: error::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(
                    Component.text("道岔创建失败", NamedTextColor.RED)
                )
            }
        )
    }

    private data class RailConnectionSnapshot(
        val rail: CoreRail,
        val abNext: List<RailView>,
        val baNext: List<RailView>,
        val abActive: RailView?,
        val baActive: RailView?
    )

    private fun snapshotCurveBuildSettings(state: WandPlayerState): CurveBuildSettingsSnapshot {
        return CurveBuildSettingsSnapshot(
            parallelCount = state.parallelCount,
            parallelSpacing = state.parallelSpacing,
            curveRadius = state.curveRadius,
            curveTemplate = state.curveTemplate,
            designSpeedKmH = state.designSpeedKmH,
            allowableCantDeficiencyMm = state.allowableCantDeficiencyMm,
            automaticSuperelevation = state.automaticSuperelevation,
            maxSuperelevationMm = state.maxSuperelevationMm
        )
    }

    /** 连接已选择的锚点组，使用 ConstraintSolver 创建曲线 → CoreRail → 渲染 */
    internal fun connectSelectedAnchors(player: Player, state: WandPlayerState) {
        val expectedCount = state.parallelCount * 2
        if (state.selectedAnchors.size != expectedCount) {
            player.sendActionBar(Component.text(
                "需要选择 $expectedCount 个锚点 (当前: ${state.selectedAnchors.size})", NamedTextColor.RED
            ))
            return
        }

        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
            return
        }

        val anchors = state.selectedAnchors.mapNotNull { getAnchor(player.world, it) }
        if (anchors.size != expectedCount) {
            player.sendActionBar(Component.text("错误: 部分锚点不存在", NamedTextColor.RED))
            return
        }

        val startAnchors = anchors.take(state.parallelCount)
        val endAnchors = anchors.drop(state.parallelCount)
        val validationError = validateParallelAnchorGroup(startAnchors, endAnchors, state)
        if (validationError != null) {
            player.sendActionBar(Component.text(validationError, NamedTextColor.RED))
            return
        }

        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "轨道连接")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val selectionSnapshot = state.selectedAnchors.toList()
        val anchorSnapshotsById = anchors.associateBy { it.id }
        val settingsSnapshot = snapshotCurveBuildSettings(state)
        val worldId = player.world.uid
        player.sendActionBar(Component.text("正在生成轨道曲线...", NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailWand.connectSelectedAnchors",
            supplier = { createCurvesWithSpeedLimits(startAnchors, endAnchors, settingsSnapshot) },
            onSuccess = { builtCurves ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveCoreWorld = getCoreWorld(livePlayer) ?: run {
                    livePlayer.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
                    return@supply
                }
                val liveState = getState(livePlayer)
                if (liveState.selectedAnchors != selectionSnapshot) {
                    livePlayer.sendActionBar(Component.text("锚点选择已变化，已取消本次轨道创建", NamedTextColor.YELLOW))
                    return@supply
                }
                val anchorsStillSame = selectionSnapshot.all { anchorId ->
                    getAnchor(livePlayer.world, anchorId) == anchorSnapshotsById[anchorId]
                }
                if (!anchorsStillSame) {
                    livePlayer.sendActionBar(Component.text("锚点已变化，请重试轨道连接", NamedTextColor.YELLOW))
                    return@supply
                }
                if (builtCurves == null) {
                    livePlayer.sendActionBar(Component.text("无法创建并行曲线", NamedTextColor.RED))
                    return@supply
                }

                val existingRails = RailRenderMetaStore.load(livePlayer.world)
                    .mapNotNull { liveCoreWorld.getRail(it.railId) }
                val createdRails = mutableListOf<CoreRail>()
                val persistedMetas = mutableListOf<PersistedRailRenderMeta>()
                val undoActions = mutableListOf<WandAction>()
                try {
                    val parallelGroupId = UUID.randomUUID().toString().takeIf { builtCurves.size > 1 }
                    builtCurves.forEachIndexed { index, builtCurve ->
                        val curve = builtCurve.curve
                        val parallel = parallelGroupId?.let {
                            ParallelRailMetadata(
                                groupId = it,
                                index = index,
                                count = builtCurves.size,
                                spacingMeters = settingsSnapshot.parallelSpacing
                            )
                        }
                        val rail = CoreRail(
                            liveCoreWorld,
                            curve,
                            mutableListOf(),
                            mutableListOf(),
                            railMetadata(settingsSnapshot, builtCurve.speedLimits, parallel)
                        )
                        val meta = RailVisualBootstrap.registerRail(rail)
                        persistedMetas.add(meta)
                        createdRails.add(rail)
                        undoActions.add(WandAction.CreateRail(livePlayer.world.uid, rail.id, "standard"))
                    }

                    val autoConnected = connectCreatedRailsToExisting(createdRails, existingRails)
                    RailRenderMetaStore.upsertRails(livePlayer.world, persistedMetas)
                    actionHistory(livePlayer).discardCreatedAnchors(selectionSnapshot.toSet())
                    pushUndo(livePlayer, WandAction.MultiAction(undoActions))
                    livePlayer.sendMessage(Component.text(
                        "✓ 创建了 ${createdRails.size} 段轨道 (${settingsSnapshot.parallelCount} 条并行线)，自动连接 $autoConnected 条",
                        NamedTextColor.GREEN
                    ))
                    val endpointSelection = createdRails.map { railEndpointAnchorId(it.id, 'B') }
                    selectionSnapshot.forEach { anchorId ->
                        if (!isRailEndpointAnchorId(anchorId)) {
                            removeAnchorDisplay(livePlayer, anchorId)
                            removeAnchor(livePlayer.world, anchorId)
                        }
                    }
                    liveState.selectedAnchors.clear()
                    liveState.selectedAnchors.addAll(endpointSelection)
                    persistState(livePlayer, liveState)
                    refreshAllAnchorsForPlayer(livePlayer)
                    updateAnchorDisplay(livePlayer)
                } catch (e: Exception) {
                    createdRails.forEach { rail ->
                        rail.destroy()
                        liveCoreWorld.removeRail(rail)
                        RailVisualBootstrap.unregisterRail(rail.id)
                        RailRenderMetaStore.removeRail(livePlayer.world, rail.id)
                    }
                    livePlayer.sendActionBar(Component.text("创建轨道失败: ${e.message}", NamedTextColor.RED))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning(
                    "RailWand connect failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(
                    Component.text("轨道连接失败，请重试", NamedTextColor.RED)
                )
            }
        )
    }

    @OptIn(Heavy::class)
    private fun rebuildRailForEndpointEdit(
        player: Player,
        state: WandPlayerState,
        endpointAnchorId: String,
        updatedEndpoint: Anchor
    ) {
        val (editedRailId, editedEnd) = parseRailEndpointAnchorId(endpointAnchorId) ?: return
        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
            return
        }
        val editedRail = coreWorld.getRail(editedRailId) ?: run {
            player.sendActionBar(Component.text("轨道不存在: $editedRailId", NamedTextColor.RED))
            return
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
            return
        }
        if (affectedRails.any { it.trains.isNotEmpty() }) {
            player.sendActionBar(Component.text("不能编辑有列车占用的轨道", NamedTextColor.RED))
            return
        }

        val rebuildInputs = affectedRails.mapNotNull { rail ->
            val moveA = rail.viewAB.startPoint.position.distance(movedPoint) <= Curve.TOLERANCE
            val moveB = rail.viewAB.endPoint.position.distance(movedPoint) <= Curve.TOLERANCE
            val movedEnd = when {
                moveA -> 'A'
                moveB -> 'B'
                else -> return@mapNotNull null
            }
            RailEndpointRebuildInput(
                railId = rail.id,
                movedEnd = movedEnd,
                oldStart = anchorFromRailEndpoint(player.world, rail, 'A'),
                oldEnd = anchorFromRailEndpoint(player.world, rail, 'B'),
                oldMetadata = rail.metadata
            )
        }
        if (rebuildInputs.size != affectedRails.size) {
            player.sendActionBar(Component.text("端点编辑计划不完整，已取消", NamedTextColor.RED))
            return
        }

        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "轨道端点重建")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val settingsSnapshot = snapshotCurveBuildSettings(state)
        val worldId = player.world.uid
        player.sendActionBar(Component.text("正在重建轨道...", NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailWand.rebuildRailForEndpointEdit",
            supplier = {
                val curvesByRailId = linkedMapOf<UUID, BuiltCurve>()
                val derailAcceleration = plugin.mineRailsConfig.derailLateralAccelerationMetersPerSecondSquared
                for (input in rebuildInputs) {
                    val newStart = if (input.movedEnd == 'A') updatedEndpoint else input.oldStart
                    val newEnd = if (input.movedEnd == 'B') updatedEndpoint else input.oldEnd
                    val curve = createCurveBetweenAnchors(newStart, newEnd, settingsSnapshot) ?: return@supply null
                    val limits = RailSpeedCalculator.calculate(
                        curve,
                        settingsSnapshot.designSpeedKmH / 3.6,
                        settingsSnapshot.allowableCantDeficiencyMm / 1000.0,
                        derailAcceleration
                    )
                    curvesByRailId[input.railId] = BuiltCurve(curve, limits)
                }
                curvesByRailId
            },
            onSuccess = { curvesByRailId ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveCoreWorld = getCoreWorld(livePlayer) ?: run {
                    livePlayer.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
                    return@supply
                }
                if (curvesByRailId == null) {
                    livePlayer.sendActionBar(Component.text("无法根据新端点生成轨道", NamedTextColor.RED))
                    return@supply
                }

                val liveAffectedRails = rebuildInputs.map { input ->
                    val rail = liveCoreWorld.getRail(input.railId) ?: run {
                        livePlayer.sendActionBar(Component.text("轨道已变化，请重试", NamedTextColor.YELLOW))
                        return@supply
                    }
                    val currentStart = anchorFromRailEndpoint(livePlayer.world, rail, 'A')
                    val currentEnd = anchorFromRailEndpoint(livePlayer.world, rail, 'B')
                    if (currentStart != input.oldStart || currentEnd != input.oldEnd) {
                        livePlayer.sendActionBar(Component.text("轨道端点已变化，请重试", NamedTextColor.YELLOW))
                        return@supply
                    }
                    rail
                }
                if (liveAffectedRails.any { it.trains.isNotEmpty() }) {
                    livePlayer.sendActionBar(Component.text("不能编辑有列车占用的轨道", NamedTextColor.RED))
                    return@supply
                }

                val liveExistingMetas = RailRenderMetaStore.load(livePlayer.world)
                val liveAllPersistedRails = liveExistingMetas.mapNotNull { liveCoreWorld.getRail(it.railId) }
                val snapshots = liveAllPersistedRails.map { snapshotRailConnections(it) }
                val createdRails = mutableListOf<CoreRail>()
                val createdMetas = mutableListOf<PersistedRailRenderMeta>()
                val oldIds = rebuildInputs.map { it.railId }.toSet()
                val oldToNew = mutableMapOf<UUID, CoreRail>()
                try {
                    rebuildInputs.forEach { input ->
                        val builtCurve = curvesByRailId[input.railId] ?: error("Missing rebuilt curve for ${input.railId}")
                        val newRail = CoreRail(
                            liveCoreWorld,
                            builtCurve.curve,
                            mutableListOf(),
                            mutableListOf(),
                            railMetadata(
                                settingsSnapshot,
                                builtCurve.speedLimits,
                                input.oldMetadata.geometry.parallel,
                                input.oldMetadata.allowedDirection
                            )
                        )
                        val meta = RailVisualBootstrap.registerRail(newRail)
                        createdRails.add(newRail)
                        createdMetas.add(meta)
                        oldToNew[input.railId] = newRail
                    }

                    val nextMetas = liveExistingMetas.filterNot { it.railId in oldIds } + createdMetas
                    RailRenderMetaStore.saveAll(livePlayer.world, nextMetas)

                    detachRailReferences(oldIds, liveAllPersistedRails.filter { it.id !in oldIds })
                    connectCreatedRailsToExisting(
                        createdRails,
                        liveAllPersistedRails.filter { it.id !in oldIds } + createdRails
                    )

                    liveAffectedRails.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        rail.destroy()
                        liveCoreWorld.removeRail(rail)
                    }

                    val replacementForEdited = oldToNew[editedRailId] ?: createdRails.first()
                    val newEndpointId = railEndpointAnchorId(replacementForEdited.id, editedEnd)
                    val liveState = getState(livePlayer)
                    liveState.selectedAnchors.clear()
                    liveState.selectedAnchors.add(newEndpointId)
                    persistState(livePlayer, liveState)
                    refreshAllAnchorsForPlayer(livePlayer)
                    updateAnchorDisplay(livePlayer)
                    livePlayer.sendActionBar(Component.text("轨道端点已更新", NamedTextColor.GREEN))
                } catch (e: Exception) {
                    createdRails.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        rail.destroy()
                        liveCoreWorld.removeRail(rail)
                    }
                    restoreRailConnections(snapshots)
                    RailRenderMetaStore.saveAll(livePlayer.world, liveExistingMetas)
                    liveExistingMetas.forEach { RailVisualBootstrap.fromPersisted(liveCoreWorld, it) }
                    livePlayer.sendActionBar(Component.text("端点编辑失败，已回滚: ${e.message}", NamedTextColor.RED))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning(
                    "RailWand endpoint rebuild failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(
                    Component.text("轨道端点重建失败，请重试", NamedTextColor.RED)
                )
            }
        )
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
        val options = RailAutoConnector.Options(endpointTolerance = Curve.TOLERANCE)
        for (created in createdRails) {
            for (existing in existingRails) {
                if (created == existing) continue
                val key = if (created.id.toString() <= existing.id.toString()) {
                    created.id to existing.id
                } else {
                    existing.id to created.id
                }
                if (!checkedPairs.add(key)) continue
                when (RailAutoConnector.connectIfAllowed(created, existing, options).outcome) {
                    RailAutoConnector.Outcome.CONNECTED -> connected++
                    RailAutoConnector.Outcome.ALREADY_CONNECTED,
                    RailAutoConnector.Outcome.TOO_FAR,
                    RailAutoConnector.Outcome.ANGLE_TOO_SHARP,
                    RailAutoConnector.Outcome.INVALID_TANGENT -> Unit
                }
            }
        }
        createdRails.forEach { created ->
            RailAutoConnector.selectSmoothestActiveRail(created.viewAB)
            RailAutoConnector.selectSmoothestActiveRail(created.viewBA)
        }
        return connected
    }

    private fun createCurveBetweenAnchors(from: Anchor, to: Anchor, state: WandPlayerState): PrimeCurve? {
        return createCurveBetweenAnchors(from, to, snapshotCurveBuildSettings(state))
    }

    private fun createCurveBetweenAnchors(
        from: Anchor,
        to: Anchor,
        settings: CurveBuildSettingsSnapshot
    ): PrimeCurve? {
        val options = railCurveBuildOptions(settings)
        val result = RailCurveFactory.build(from, to, options)
        if (!result.isSuccess) {
            plugin.logger.warning(
                "RailWand curve build failed: template=${result.selectedTemplate ?: settings.curveTemplate}, " +
                    "reason=${result.selectedReason ?: "n/a"}, error=${result.error ?: "unknown error"}"
            )
        }
        return result.curve
    }

    private fun createCurvesBetweenParallelAnchors(
        startAnchors: List<Anchor>,
        endAnchors: List<Anchor>,
        state: WandPlayerState
    ): List<PrimeCurve>? {
        return createCurvesBetweenParallelAnchors(startAnchors, endAnchors, snapshotCurveBuildSettings(state))
    }

    private fun createCurvesBetweenParallelAnchors(
        startAnchors: List<Anchor>,
        endAnchors: List<Anchor>,
        settings: CurveBuildSettingsSnapshot
    ): List<PrimeCurve>? {
        val options = railCurveBuildOptions(settings)
        if (startAnchors.size <= 1) {
            return startAnchors.zip(endAnchors).map { (from, to) ->
                createCurveBetweenAnchors(from, to, settings) ?: return null
            }
        }
        val result = RailCurveFactory.buildParallel(startAnchors, endAnchors, options)
        if (!result.isSuccess) {
            plugin.logger.warning(
                "RailWand parallel curve build failed: template=${result.selectedTemplate ?: settings.curveTemplate}, " +
                    "reason=${result.selectedReason ?: "n/a"}, error=${result.error ?: "unknown error"}"
            )
        }
        return result.curves
    }

    @OptIn(Heavy::class)
    private fun createCurvesWithSpeedLimits(
        startAnchors: List<Anchor>,
        endAnchors: List<Anchor>,
        settings: CurveBuildSettingsSnapshot
    ): List<BuiltCurve>? {
        val curves = createCurvesBetweenParallelAnchors(startAnchors, endAnchors, settings) ?: return null
        val derailAcceleration = plugin.mineRailsConfig.derailLateralAccelerationMetersPerSecondSquared
        return curves.map { curve ->
            BuiltCurve(
                curve,
                RailSpeedCalculator.calculate(
                    curve = curve,
                    requestedLimitMetersPerSecond = settings.designSpeedKmH / 3.6,
                    allowableCantDeficiencyMeters = settings.allowableCantDeficiencyMm / 1000.0,
                    derailLateralAccelerationMetersPerSecondSquared = derailAcceleration
                )
            )
        }
    }

    private fun railCurveBuildOptions(state: WandPlayerState): RailCurveBuildOptions {
        return railCurveBuildOptions(snapshotCurveBuildSettings(state))
    }

    private fun railCurveBuildOptions(settings: CurveBuildSettingsSnapshot): RailCurveBuildOptions {
        return RailCurveBuildOptions(
            template = settings.curveTemplate,
            radius = settings.curveRadius,
            superelevation = if (settings.automaticSuperelevation) {
                SuperelevationConfig(
                    maxSuperelevation = settings.maxSuperelevationMm / 1000.0,
                    designSpeedKmH = (settings.designSpeedKmH - 10.0).coerceAtLeast(0.0)
                )
            } else {
                null
            }
        )
    }

    private fun railMetadata(
        settings: CurveBuildSettingsSnapshot,
        speedLimits: RailSpeedLimits,
        parallel: ParallelRailMetadata? = null,
        allowedDirection: ViewDirection? = null
    ): RailMetadata = RailMetadata(
        designSpeedMetersPerSecond = settings.designSpeedKmH / 3.6,
        speedLimitMetersPerSecond = speedLimits.operatingLimitMetersPerSecond,
        derailSpeedMetersPerSecond = speedLimits.derailSpeedMetersPerSecond,
        allowedDirection = allowedDirection,
        geometry = RailGeometryMetadata(
            curveKind = RailCurveKind.valueOf(settings.curveTemplate.name),
            curveRadiusMeters = settings.curveRadius.takeIf { it > 0.0 },
            automaticSuperelevation = settings.automaticSuperelevation,
            maxSuperelevationMeters = settings.maxSuperelevationMm / 1000.0,
            allowableCantDeficiencyMeters = settings.allowableCantDeficiencyMm / 1000.0,
            parallel = parallel
        )
    )

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

    internal fun editRail(player: Player, rail: CoreRail, state: WandPlayerState) {
        val currentSpeedKmH = rail.metadata.designSpeedMetersPerSecond?.times(3.6) ?: state.designSpeedKmH
        val currentRadius = rail.metadata.geometry.curveRadiusMeters ?: 0.0
        state.selectedAnchors.clear()
        state.selectedRails.clear()
        state.selectedRails.add(rail.id)
        state.designSpeedKmH = currentSpeedKmH
        state.curveRadius = currentRadius
        updateAnchorDisplay(player)

        val session = configUI.getPlayerSession(player.uniqueId)
        session.setValue(railEditKeys.designSpeedKmH, currentSpeedKmH)
        session.setValue(railEditKeys.curveRadius, currentRadius)
        dialogRenderer.showConfigDialog(player, "rail.edit") { p ->
            val liveWorld = getCoreWorld(p) ?: return@showConfigDialog
            val liveRail = liveWorld.getRail(rail.id) ?: run {
                p.sendActionBar(Component.text("轨道已不存在", NamedTextColor.RED))
                return@showConfigDialog
            }
            val values = configUI.getPlayerSession(p.uniqueId)
            val designSpeedKmH = values.getValue(railEditKeys.designSpeedKmH) ?: currentSpeedKmH
            val curveRadius = values.getValue(railEditKeys.curveRadius) ?: currentRadius
            val liveState = getState(p)
            liveState.designSpeedKmH = designSpeedKmH
            liveState.curveRadius = curveRadius
            persistState(p, liveState)

            if (abs(curveRadius - currentRadius) < 1.0e-6 && abs(designSpeedKmH - currentSpeedKmH) < 1.0e-6) {
                p.sendActionBar(Component.text("轨道参数未变化", NamedTextColor.GRAY))
            } else {
                rebuildRailGeometry(p, liveRail, designSpeedKmH, curveRadius)
            }
        }
    }

    private fun railsInParallelGroup(world: CoreWorld, rail: CoreRail): List<CoreRail> {
        val parallel = rail.metadata.geometry.parallel ?: return listOf(rail)
        val rails = world.allRails
            .filter { it.metadata.geometry.parallel?.groupId == parallel.groupId }
            .sortedBy { it.metadata.geometry.parallel!!.index }
        return if (rails.size == parallel.count) rails else emptyList()
    }

    private fun rebuildRailGeometry(
        player: Player,
        selectedRail: CoreRail,
        designSpeedKmH: Double,
        curveRadius: Double
    ) {
        val coreWorld = getCoreWorld(player) ?: return
        val oldRails = railsInParallelGroup(coreWorld, selectedRail)
        if (oldRails.isEmpty()) {
            player.sendActionBar(Component.text("并行线路信息不完整，无法重建", NamedTextColor.RED))
            return
        }
        if (oldRails.any { it.trains.isNotEmpty() }) {
            player.sendActionBar(Component.text("不能重建有列车占用的轨道", NamedTextColor.RED))
            return
        }

        val geometry = selectedRail.metadata.geometry
        if (geometry.curveKind == RailCurveKind.UNKNOWN) {
            player.sendActionBar(Component.text("旧轨道没有曲线模板信息，请重新创建", NamedTextColor.RED))
            return
        }
        val template = RailCurveTemplateType.valueOf(geometry.curveKind.name)
        val parallel = geometry.parallel
        val settings = CurveBuildSettingsSnapshot(
            parallelCount = parallel?.count ?: 1,
            parallelSpacing = parallel?.spacingMeters ?: 4.5,
            curveRadius = curveRadius,
            curveTemplate = template,
            designSpeedKmH = designSpeedKmH,
            allowableCantDeficiencyMm = geometry.allowableCantDeficiencyMeters * 1000.0,
            automaticSuperelevation = geometry.automaticSuperelevation,
            maxSuperelevationMm = geometry.maxSuperelevationMeters * 1000.0
        )
        val starts = oldRails.map { anchorFromRailEndpoint(player.world, it, 'A') }
        val ends = oldRails.map { anchorFromRailEndpoint(player.world, it, 'B') }
        val oldIds = oldRails.map { it.id }.toSet()
        val oldMetadata = oldRails.map { it.metadata }
        val selectedIndex = oldRails.indexOfFirst { it.id == selectedRail.id }
        val worldId = player.world.uid
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "轨道曲率重建")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }
        player.sendActionBar(Component.text("正在重建轨道曲线...", NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailWand.rebuildRailGeometry",
            supplier = { createCurvesWithSpeedLimits(starts, ends, settings) },
            onSuccess = { builtCurves ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                if (builtCurves == null || builtCurves.size != oldRails.size) {
                    livePlayer.sendActionBar(Component.text("无法按新半径生成曲线", NamedTextColor.RED))
                    return@supply
                }
                val liveOldRails = oldRails.map { old -> liveWorld.getRail(old.id) ?: return@supply }
                if (liveOldRails.any { it.trains.isNotEmpty() }) {
                    livePlayer.sendActionBar(Component.text("不能重建有列车占用的轨道", NamedTextColor.RED))
                    return@supply
                }

                val persisted = RailRenderMetaStore.load(livePlayer.world)
                val allRails = persisted.mapNotNull { liveWorld.getRail(it.railId) }
                val connectionSnapshots = allRails.map(::snapshotRailConnections)
                val created = mutableListOf<CoreRail>()
                val createdRenderMeta = mutableListOf<PersistedRailRenderMeta>()
                try {
                    builtCurves.forEachIndexed { index, builtCurve ->
                        val metadata = oldMetadata[index].copy(
                            designSpeedMetersPerSecond = designSpeedKmH / 3.6,
                            speedLimitMetersPerSecond = builtCurve.speedLimits.operatingLimitMetersPerSecond,
                            derailSpeedMetersPerSecond = builtCurve.speedLimits.derailSpeedMetersPerSecond,
                            geometry = oldMetadata[index].geometry.copy(
                                curveRadiusMeters = curveRadius.takeIf { it > 0.0 },
                                allowableCantDeficiencyMeters = settings.allowableCantDeficiencyMm / 1000.0
                            )
                        )
                        val newRail = CoreRail(liveWorld, builtCurve.curve, mutableListOf(), mutableListOf(), metadata)
                        created += newRail
                        createdRenderMeta += RailVisualBootstrap.registerRail(newRail)
                    }

                    RailRenderMetaStore.saveAll(
                        livePlayer.world,
                        persisted.filterNot { it.railId in oldIds } + createdRenderMeta
                    )
                    detachRailReferences(oldIds, allRails.filter { it.id !in oldIds })
                    connectCreatedRailsToExisting(created, allRails.filter { it.id !in oldIds } + created)
                    liveOldRails.forEach { old ->
                        RailVisualBootstrap.unregisterRail(old.id)
                        old.destroy()
                        liveWorld.removeRail(old)
                    }

                    val replacement = created[selectedIndex.coerceAtLeast(0)]
                    val state = getState(livePlayer)
                    state.selectedRails.clear()
                    state.selectedRails.add(replacement.id)
                    persistState(livePlayer, state)
                    refreshAllAnchorsForPlayer(livePlayer)
                    updateAnchorDisplay(livePlayer)
                    livePlayer.sendActionBar(Component.text("轨道曲线半径已更新", NamedTextColor.GREEN))
                } catch (error: Exception) {
                    created.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        rail.destroy()
                        liveWorld.removeRail(rail)
                    }
                    restoreRailConnections(connectionSnapshots)
                    RailRenderMetaStore.saveAll(livePlayer.world, persisted)
                    persisted.forEach { RailVisualBootstrap.fromPersisted(liveWorld, it) }
                    livePlayer.sendActionBar(Component.text("轨道重建失败，已回滚: ${error.message}", NamedTextColor.RED))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail radius rebuild failed: ${error.message ?: error::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(
                    Component.text("轨道重建失败", NamedTextColor.RED)
                )
            }
        )
    }

    internal fun selectRail(player: Player, rail: CoreRail, state: WandPlayerState) {
        state.selectedAnchors.clear()
        state.selectedRails.clear()
        state.selectedRails.add(rail.id)
        persistState(player, state)
        updateAnchorDisplay(player)
        val speed = rail.metadata.designSpeedMetersPerSecond?.times(3.6)
        val speedLabel = speed?.let { "%.0f km/h".format(it) } ?: "未设置"
        val limitLabel = rail.metadata.speedLimitMetersPerSecond?.times(3.6)?.let { "%.0f km/h".format(it) } ?: "未设置"
        val derailLabel = rail.metadata.derailSpeedMetersPerSecond?.times(3.6)?.let { "%.0f km/h".format(it) } ?: "∞"
        val radiusLabel = rail.metadata.geometry.curveRadiusMeters?.let { "%.0f m".format(it) } ?: "自动"
        val parallelLabel = rail.metadata.geometry.parallel?.let { "并行 ${it.index + 1}/${it.count}" } ?: "单线"
        player.sendActionBar(
            Component.text(
                "已选择轨道 ${rail.id}，设计 $speedLabel，限速 $limitLabel，脱轨 $derailLabel，半径 $radiusLabel，$parallelLabel",
                NamedTextColor.YELLOW
            )
        )
    }

    internal fun editAnchor(player: Player, anchor: Anchor, state: WandPlayerState) {
        if (parseSplitAnchorId(anchor.id) != null) {
            player.sendActionBar(Component.text("分割锚点不能移动；请在分割模式左键尝试合并", NamedTextColor.YELLOW))
            return
        }
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
                rebuildRailForEndpointEdit(p, state, anchor.id, updated)
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
    // 分割 / 合并模式
    // ═══════════════════════════════════════════

    private fun updateSplitPreviews() {
        plugin.server.onlinePlayers.forEach { player ->
            if (!isWandItem(player.inventory.itemInMainHand)) return@forEach
            val state = getState(player)
            if (state.mode != WandMode.SPLIT) {
                splitPreviewTargets.remove(player.uniqueId)
                return@forEach
            }

            val coreWorld = getCoreWorld(player) ?: return@forEach
            val pendingRail = state.pendingSplitRail?.let(coreWorld::getRail)
            val pendingArc = state.pendingSplitArcLength
            if (pendingRail != null && pendingArc != null && pendingArc in 0.0..pendingRail.curve.totalArcLength) {
                val point = pendingRail.curve.getPointAt(pendingArc)
                spawnSplitParticle(player, point.position, Color.LIME, 1.25f)
            }
            requestSplitPreview(player)
        }
    }

    private fun requestSplitPreview(player: Player) {
        if (!splitPreviewRequests.add(player.uniqueId)) return
        val coreWorld = getCoreWorld(player)
        if (coreWorld == null) {
            splitPreviewRequests.remove(player.uniqueId)
            return
        }
        val eye = player.eyeLocation
        val origin = Vector3d(eye.x, eye.y, eye.z)
        val direction = Vector3d(eye.direction.x, eye.direction.y, eye.direction.z)
        val worldId = player.world.uid
        val candidates = coreWorld.getRailsAlongRay(origin, direction, 96.0, 1.5).toList()
        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailWand.updateSplitPreview",
            supplier = { HeavyRailQueries.findLookedAtRailPoint(candidates, origin, direction, 96.0, 1.5) },
            onSuccess = { match ->
                splitPreviewRequests.remove(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId || getState(livePlayer).mode != WandMode.SPLIT) return@supply
                if (match == null || getCoreWorld(livePlayer)?.getRail(match.railId) == null) {
                    splitPreviewTargets.remove(player.uniqueId)
                    return@supply
                }
                val target = SplitPreviewTarget(worldId, match.railId, match.arcLength, Vector3d(match.position))
                splitPreviewTargets[player.uniqueId] = target
                spawnSplitParticle(livePlayer, target.position, Color.AQUA, 1.0f)
            },
            onFailure = { error ->
                splitPreviewRequests.remove(player.uniqueId)
                plugin.logger.warning(
                    "RailWand split preview failed for ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
            }
        )
    }

    private fun spawnSplitParticle(player: Player, position: Vector3dc, color: Color, size: Float) {
        player.spawnParticle(
            Particle.DUST,
            Location(player.world, position.x(), position.y(), position.z()),
            1,
            0.0,
            0.0,
            0.0,
            0.0,
            Particle.DustOptions(color, size)
        )
    }

    internal fun selectOrConfirmSplit(player: Player, state: WandPlayerState) {
        if (state.pendingSplitRail != null && state.pendingSplitArcLength != null) {
            confirmSplit(player, state)
            return
        }
        val target = splitPreviewTargets[player.uniqueId]
        if (target == null || target.worldId != player.world.uid) {
            requestSplitPreview(player)
            player.sendActionBar(Component.text("尚未指向可分割的轨道点", NamedTextColor.YELLOW))
            return
        }
        val rail = getCoreWorld(player)?.getRail(target.railId)
        if (rail == null || target.arcLength <= Curve.TOLERANCE ||
            target.arcLength >= rail.curve.totalArcLength - Curve.TOLERANCE
        ) {
            player.sendActionBar(Component.text("分割点不能位于轨道端点", NamedTextColor.RED))
            return
        }
        state.pendingSplitRail = target.railId
        state.pendingSplitArcLength = target.arcLength
        state.selectedRails.clear()
        state.selectedRails.add(target.railId)
        persistState(player, state)
        player.sendActionBar(Component.text(
            "已选择分割点 ${"%.3f".format(target.arcLength)}m；再次右键确认",
            NamedTextColor.YELLOW
        ))
    }

    private fun confirmSplit(player: Player, state: WandPlayerState) {
        val railId = state.pendingSplitRail ?: return
        val arcLength = state.pendingSplitArcLength ?: return
        val coreWorld = getCoreWorld(player) ?: return
        val rail = coreWorld.getRail(railId) ?: run {
            clearPendingSplit(state)
            player.sendActionBar(Component.text("待分割轨道已不存在", NamedTextColor.RED))
            return
        }
        splitSafetyError(coreWorld, listOf(rail))?.let { error ->
            player.sendActionBar(Component.text(error, NamedTextColor.RED))
            return
        }
        if (arcLength <= Curve.TOLERANCE || arcLength >= rail.curve.totalArcLength - Curve.TOLERANCE) {
            player.sendActionBar(Component.text("分割点过于接近轨道端点", NamedTextColor.RED))
            return
        }
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "轨道分割")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val curveSnapshot = rail.curve
        val metadataSnapshot = rail.metadata
        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailWand.confirmSplit",
            supplier = { curveSnapshot.splitAt(arcLength) },
            onSuccess = { curves ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                val liveRail = liveWorld.getRail(railId)
                if (liveRail == null || liveRail.curve !== curveSnapshot || liveRail.metadata != metadataSnapshot) {
                    livePlayer.sendActionBar(Component.text("轨道已变化，请重新选择分割点", NamedTextColor.YELLOW))
                    clearPendingSplit(getState(livePlayer))
                    return@supply
                }
                splitSafetyError(liveWorld, listOf(liveRail))?.let { error ->
                    livePlayer.sendActionBar(Component.text(error, NamedTextColor.RED))
                    return@supply
                }

                val renderMetas = RailRenderMetaStore.load(livePlayer.world)
                val originalRenderMeta = renderMetas.firstOrNull { it.railId == railId }
                try {
                    val result = RailTopologyEditor.splitWithPreparedCurves(liveRail, curves.first, curves.second)
                    RailVisualBootstrap.unregisterRail(railId)
                    val beforeMeta = registerRailWithTemplate(result.before, originalRenderMeta?.templateSnapshot)
                    val afterMeta = registerRailWithTemplate(result.after, originalRenderMeta?.templateSnapshot)
                    RailRenderMetaStore.saveAll(
                        livePlayer.world,
                        renderMetas.filterNot { it.railId == railId } + beforeMeta + afterMeta
                    )
                    val liveState = getState(livePlayer)
                    clearPendingSplit(liveState)
                    liveState.selectedRails.clear()
                    liveState.selectedRails.addAll(listOf(result.before.id, result.after.id))
                    persistState(livePlayer, liveState)
                    splitPreviewTargets.remove(livePlayer.uniqueId)
                    refreshAnchorsForAllViewers()
                    livePlayer.sendActionBar(Component.text("轨道已分割为两段", NamedTextColor.GREEN))
                } catch (error: Throwable) {
                    plugin.logger.warning("Rail split failed: ${error::class.simpleName}: ${error.message ?: ""}")
                    livePlayer.sendActionBar(Component.text("轨道分割失败: ${error.message ?: error::class.simpleName}", NamedTextColor.RED))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail curve split failed: ${error::class.simpleName}: ${error.message ?: ""}")
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(Component.text("轨道曲线分割失败", NamedTextColor.RED))
            }
        )
    }

    internal fun mergeSplitAnchor(player: Player, anchor: Anchor) {
        val operationId = parseSplitAnchorId(anchor.id) ?: run {
            player.sendActionBar(Component.text("请左键由分割操作创建的锚点", NamedTextColor.YELLOW))
            return
        }
        val coreWorld = getCoreWorld(player) ?: return
        val pair = findSplitPair(coreWorld, operationId) ?: run {
            player.sendActionBar(Component.text("分割锚点对应的轨道已变化", NamedTextColor.RED))
            return
        }
        val (before, after) = pair
        RailTopologyEditor.mergeEligibilityError(before, after)?.let { error ->
            player.sendActionBar(Component.text(error, NamedTextColor.RED))
            return
        }
        splitSafetyError(coreWorld, listOf(before, after))?.let { error ->
            player.sendActionBar(Component.text(error, NamedTextColor.RED))
            return
        }
        val renderMetas = RailRenderMetaStore.load(player.world)
        val beforeRender = renderMetas.firstOrNull { it.railId == before.id }
        val afterRender = renderMetas.firstOrNull { it.railId == after.id }
        if (beforeRender?.templateSnapshot != afterRender?.templateSnapshot) {
            player.sendActionBar(Component.text("两段轨道的渲染元数据不一致", NamedTextColor.RED))
            return
        }
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "轨道合并")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val beforeCurve = before.curve
        val afterCurve = after.curve
        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailWand.mergeSplitAnchor",
            supplier = { beforeCurve.concatenate(afterCurve) },
            onSuccess = { mergedCurve ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                val livePair = findSplitPair(liveWorld, operationId)
                if (livePair == null || livePair.first.curve !== beforeCurve || livePair.second.curve !== afterCurve) {
                    livePlayer.sendActionBar(Component.text("分割轨道已变化，请重试", NamedTextColor.YELLOW))
                    return@supply
                }
                RailTopologyEditor.mergeEligibilityError(livePair.first, livePair.second)?.let { error ->
                    livePlayer.sendActionBar(Component.text(error, NamedTextColor.RED))
                    return@supply
                }
                splitSafetyError(liveWorld, livePair.toList())?.let { error ->
                    livePlayer.sendActionBar(Component.text(error, NamedTextColor.RED))
                    return@supply
                }

                try {
                    val merged = RailTopologyEditor.mergeWithPreparedCurve(livePair.first, livePair.second, mergedCurve)
                    RailVisualBootstrap.unregisterRail(livePair.first.id)
                    RailVisualBootstrap.unregisterRail(livePair.second.id)
                    val mergedMeta = registerRailWithTemplate(merged, beforeRender?.templateSnapshot)
                    RailRenderMetaStore.saveAll(
                        livePlayer.world,
                        renderMetas.filterNot { it.railId == livePair.first.id || it.railId == livePair.second.id } + mergedMeta
                    )
                    val liveState = getState(livePlayer)
                    clearPendingSplit(liveState)
                    liveState.selectedRails.clear()
                    liveState.selectedRails.add(merged.id)
                    persistState(livePlayer, liveState)
                    refreshAnchorsForAllViewers()
                    livePlayer.sendActionBar(Component.text("已重新合并分割轨道", NamedTextColor.GREEN))
                } catch (error: Throwable) {
                    plugin.logger.warning("Rail merge failed: ${error::class.simpleName}: ${error.message ?: ""}")
                    livePlayer.sendActionBar(Component.text("轨道合并失败: ${error.message ?: error::class.simpleName}", NamedTextColor.RED))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail curve merge failed: ${error::class.simpleName}: ${error.message ?: ""}")
                plugin.server.getPlayer(player.uniqueId)?.sendActionBar(Component.text("轨道曲线合并失败", NamedTextColor.RED))
            }
        )
    }

    private fun splitSafetyError(coreWorld: CoreWorld, rails: List<CoreRail>): String? {
        if (rails.any { it.trains.isNotEmpty() }) return "不能操作有列车占用的轨道"
        if (rails.any { it.signals.isNotEmpty() }) return "不能操作包含信号机的轨道"
        val railIds = rails.mapTo(hashSetOf()) { it.id }
        val basicData = coreWorld.basicWorldDataOrNull ?: return null
        if (basicData.wayPoints.all().any { it.railId in railIds }) return "不能操作包含路径点或运营目的地的轨道"
        if (railIds.any { basicData.railObjects.objectsOnRail(it).isNotEmpty() }) return "不能操作包含运营对象的轨道"
        return null
    }

    private fun registerRailWithTemplate(
        rail: CoreRail,
        templateSnapshot: work.art1st.mineRails.bukkit.extension.rail.RailTemplateSnapshot?
    ): PersistedRailRenderMeta = if (templateSnapshot != null) {
        RailVisualBootstrap.registerRailWithTemplate(rail, templateSnapshot)
    } else {
        RailVisualBootstrap.registerRail(rail)
    }

    private fun clearPendingSplit(state: WandPlayerState) {
        state.pendingSplitRail = null
        state.pendingSplitArcLength = null
    }

    // ═══════════════════════════════════════════
    // 删除模式
    // ═══════════════════════════════════════════

    internal fun deleteAnchor(player: Player, anchorId: String, state: WandPlayerState) {
        if (isDerivedRailAnchorId(anchorId)) {
            val message = if (parseSplitAnchorId(anchorId) != null) {
                "分割锚点不能删除；请在分割模式左键尝试合并"
            } else {
                "轨道端点锚点不能单独删除，请删除轨道"
            }
            player.sendActionBar(Component.text(message, NamedTextColor.YELLOW))
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

    internal fun railIdFromEndpointAnchor(anchorId: String): UUID? = parseRailEndpointAnchorId(anchorId)?.first

    internal fun deleteRail(player: Player, railId: UUID, state: WandPlayerState) {
        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 MineRails 世界", NamedTextColor.RED))
            return
        }
        val rail = coreWorld.getRail(railId) ?: run {
            RailVisualBootstrap.unregisterRail(railId)
            RailRenderMetaStore.removeRail(player.world, railId)
            refreshAllAnchorsForPlayer(player)
            player.sendActionBar(Component.text("轨道不存在，已清理残留渲染数据: $railId", NamedTextColor.YELLOW))
            return
        }
        if (rail.trains.isNotEmpty()) {
            player.sendActionBar(Component.text("不能删除有列车占用的轨道", NamedTextColor.RED))
            return
        }

        val persistedRails = RailRenderMetaStore.load(player.world).mapNotNull { coreWorld.getRail(it.railId) }
        detachRailReferences(setOf(railId), persistedRails.filter { it.id != railId })
        RailVisualBootstrap.unregisterRail(railId)
        RailRenderMetaStore.removeRail(player.world, railId)
        rail.destroy()
        coreWorld.removeRail(rail)

        state.selectedRails.remove(railId)
        state.selectedAnchors.removeIf { railIdFromEndpointAnchor(it) == railId }
        removeAnchorDisplay(player, railEndpointAnchorId(railId, 'A'))
        removeAnchorDisplay(player, railEndpointAnchorId(railId, 'B'))
        refreshAllAnchorsForPlayer(player)
        pushUndo(player, WandAction.DeleteRail(player.world.uid, railId))
        player.sendActionBar(Component.text("已删除轨道 $railId", NamedTextColor.GREEN))
    }

    internal fun findLookedAtRailAsync(
        player: Player,
        maxDistance: Double = 64.0,
        hitRadius: Double = 1.5,
        onResolved: (CoreRail?) -> Unit
    ) {
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "轨道选取")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }

        val coreWorld = getCoreWorld(player)
        if (coreWorld == null) {
            heavyOperationGate.finish(player.uniqueId)
            onResolved(null)
            return
        }

        val eye = player.eyeLocation
        val origin = Vector3d(eye.x, eye.y, eye.z)
        val direction = Vector3d(eye.direction.x, eye.direction.y, eye.direction.z)
        if (direction.lengthSquared() < 1e-12) {
            heavyOperationGate.finish(player.uniqueId)
            onResolved(null)
            return
        }

        val worldId = player.world.uid
        val candidates = coreWorld.getRailsAlongRay(origin, direction, maxDistance, hitRadius)
            .ifEmpty { coreWorld.allRails }
            .toList()
        player.sendActionBar(Component.text("正在查找所指轨道...", NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            operation = "RailWand.findLookedAtRail",
            supplier = { HeavyRailQueries.findLookedAtRail(candidates, origin, direction, maxDistance, hitRadius) },
            onSuccess = { railId ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveRail = railId?.let { getCoreWorld(livePlayer)?.getRail(it) }
                onResolved(liveRail)
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning(
                    "RailWand look-at rail search failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                livePlayer.sendActionBar(Component.text("轨道查找失败，请重试", NamedTextColor.RED))
                onResolved(null)
            }
        )
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
        anchorsFor(world)[id] ?: getRailEndpointAnchor(world, id) ?: getSplitAnchor(world, id)

    private fun getAllAnchors(world: World): List<Anchor> =
        anchorsFor(world).values.toList() + getRailEndpointAnchors(world) + getSplitAnchors(world)

    private fun updateAnchor(world: World, anchor: Anchor) {
        if (!isDerivedRailAnchorId(anchor.id)) {
            anchorsFor(world)[anchor.id] = anchor
        }
    }

    private fun removeAnchor(world: World, id: String) {
        if (!isDerivedRailAnchorId(id)) {
            anchorsFor(world).remove(id)
        }
    }

    private fun railEndpointAnchorId(railId: UUID, end: Char): String = "rail:$railId:$end"

    private fun splitAnchorId(operationId: String): String = "split:$operationId"

    private fun parseSplitAnchorId(id: String): String? = id
        .takeIf { it.startsWith("split:") }
        ?.removePrefix("split:")
        ?.takeIf { it.isNotBlank() }

    private fun isRailEndpointAnchorId(id: String): Boolean = parseRailEndpointAnchorId(id) != null

    private fun isDerivedRailAnchorId(id: String): Boolean =
        isRailEndpointAnchorId(id) || parseSplitAnchorId(id) != null

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
            val split = rail.metadata.createdBySplit
            val hasSiblingPair = split != null && findSplitPair(coreWorld, split.operationId) != null
            listOfNotNull(
                getRailEndpointAnchor(world, railEndpointAnchorId(rail.id, 'A'))
                    .takeUnless { hasSiblingPair && split.segmentIndex == 1 },
                getRailEndpointAnchor(world, railEndpointAnchorId(rail.id, 'B'))
                    .takeUnless { hasSiblingPair && split.segmentIndex == 0 }
            )
        }
    }

    private fun findSplitPair(coreWorld: CoreWorld, operationId: String): Pair<CoreRail, CoreRail>? {
        val siblings = coreWorld.allRails.filter { it.metadata.createdBySplit?.operationId == operationId }
        val before = siblings.singleOrNull { it.metadata.createdBySplit?.segmentIndex == 0 } ?: return null
        val after = siblings.singleOrNull { it.metadata.createdBySplit?.segmentIndex == 1 } ?: return null
        return before to after
    }

    private fun getSplitAnchor(world: World, id: String): Anchor? {
        val operationId = parseSplitAnchorId(id) ?: return null
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[world] as? CoreWorld ?: return null
        val (before, after) = findSplitPair(coreWorld, operationId) ?: return null
        val point = before.viewAB.endPoint
        if (point.position.distance(after.viewAB.startPoint.position) > Curve.TOLERANCE) return null
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

    private fun getSplitAnchors(world: World): List<Anchor> {
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[world] as? CoreWorld ?: return emptyList()
        return coreWorld.allRails.mapNotNull { it.metadata.createdBySplit?.operationId }
            .distinct()
            .mapNotNull { getSplitAnchor(world, splitAnchorId(it)) }
    }

    internal fun findNearestAnchor(player: Player, maxDistance: Double): Anchor? {
        val playerPos = player.location.toVector().toVector3d()
        return getAllAnchors(player.world)
            .filter { it.position.distance(playerPos) <= maxDistance }
            .minByOrNull { it.position.distance(playerPos) }
    }

    internal fun findLookedAtAnchor(player: Player, maxDistance: Double, hitRadius: Double): Anchor? {
        val origin = player.eyeLocation.toVector().toVector3d()
        val direction = player.eyeLocation.direction.toVector3d().normalize()
        return getAllAnchors(player.world).mapNotNull { anchor ->
            val offset = Vector3d(anchor.position).sub(origin)
            val distanceAlongRay = offset.dot(direction)
            if (distanceAlongRay !in 0.0..maxDistance) return@mapNotNull null
            val distanceToRay = offset.sub(Vector3d(direction).mul(distanceAlongRay)).length()
            anchor.takeIf { distanceToRay <= hitRadius }?.let { it to distanceAlongRay }
        }.minByOrNull { it.second }?.first
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
        val yawRad = Math.toRadians(player.location.yaw.toDouble())
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
        val entityIds = anchorEntities
            .getOrPut(player.uniqueId) { mutableMapOf() }
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
        val entries = anchorEntities[player.uniqueId]?.remove(anchorId) ?: return
        entries.forEach { entityId ->
            player.world.getEntity(entityId)?.remove()
        }
    }

    private fun removeAllAnchorDisplays(player: Player) {
        anchorEntities.remove(player.uniqueId)?.forEach { (_, entityIds) ->
            entityIds.forEach { entityId ->
                player.world.getEntity(entityId)?.remove()
            }
        }
    }

    /** 更新锚点展示效果：选中 = Glowing */
    internal fun updateAnchorDisplay(player: Player) {
        val state = getState(player)
        anchorEntities[player.uniqueId]?.forEach { (anchorId, entityIds) ->
            entityIds.forEach { entityId ->
                val entity = player.world.getEntity(entityId) ?: return@forEach
                entity.isGlowing = anchorId in state.selectedAnchors
            }
        }
    }

    private fun refreshAnchorsForPlayer(player: Player) {
        removeAllAnchorDisplays(player)
        if (!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) return
        getAllAnchors(player.world).forEach { spawnAnchorDisplay(player, it) }
        updateAnchorDisplay(player)
    }

    private fun refreshAnchorsForAllViewers() {
        plugin.server.onlinePlayers.forEach { refreshAnchorsForPlayer(it) }
    }

    private fun scheduleAnchorVisibilityRefresh(player: Player) {
        plugin.server.scheduler.runTask(plugin, Runnable { refreshAnchorsForPlayer(player) })
    }

    @EventHandler
    fun onRailWandHeldChange(event: PlayerItemHeldEvent) {
        scheduleAnchorVisibilityRefresh(event.player)
    }

    @EventHandler
    fun onRailWandSwapHands(event: PlayerSwapHandItemsEvent) {
        scheduleAnchorVisibilityRefresh(event.player)
    }

    @EventHandler
    fun onRailWandPlayerJoin(event: PlayerJoinEvent) {
        scheduleAnchorVisibilityRefresh(event.player)
    }

    /** 加载世界时刷新所有锚点展示 */
    fun refreshAllAnchorsForPlayer(player: Player) {
        removeAllAnchorDisplays(player)
        getAllAnchors(player.world).forEach { spawnAnchorDisplay(player, it) }
    }

    // ═══════════════════════════════════════════
    // Undo / Redo
    // ═══════════════════════════════════════════

    private fun actionHistory(player: Player): WandActionHistory =
        actionHistories.getOrPut(player.uniqueId) { WandActionHistory() }

    private fun pushUndo(player: Player, action: WandAction) {
        actionHistory(player).record(action)
    }

    fun undo(player: Player): Boolean {
        val action = actionHistories[player.uniqueId]?.takeUndo()
        if (action == null) {
            player.sendActionBar(Component.text("没有可撤销的操作", NamedTextColor.GRAY))
            return false
        }

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
        persistState(player, getState(player))
        player.sendActionBar(Component.text("已撤销", NamedTextColor.GREEN))
        return true
    }

    fun redo(player: Player): Boolean {
        val action = actionHistories[player.uniqueId]?.takeRedo()
        if (action == null) {
            player.sendActionBar(Component.text("没有可重做的操作", NamedTextColor.GRAY))
            return false
        }

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
        persistState(player, getState(player))
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
        val state = getState(player)
        state.selectedRails.remove(action.railId)
        state.selectedAnchors.removeIf { railIdFromEndpointAnchor(it) == action.railId }
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

    /** Shift+右键 → 打开分页设置界面。 */
    private fun showModeSettings(player: Player, state: WandPlayerState) {
        syncStateToConfig(player)
        fun openPage(pagePlayer: Player, groupId: String) {
            dialogRenderer.showConfigDialog(pagePlayer, groupId) { confirmedPlayer ->
                syncConfigToState(confirmedPlayer)
                confirmedPlayer.sendActionBar(Component.text("RailWand 设置已更新", NamedTextColor.GREEN))
                showModeSettings(confirmedPlayer, getState(confirmedPlayer))
            }
        }
        dialogRenderer.showMenu(
            player,
            "RailWand 设置",
            "请选择设置分页",
            listOf(
                DialogRenderer.MenuEntry("1/2 锚点与并行线路") { openPage(it, "wand.settings.general") },
                DialogRenderer.MenuEntry("2/2 曲线、道岔与限速") { openPage(it, "wand.settings.geometry") }
            )
        )
    }

    // ═══════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════

    private fun clearSelection(state: WandPlayerState) {
        state.selectedAnchors.clear()
        state.selectedRails.clear()
        state.pendingDeleteAnchor = null
        state.pendingDeleteRail = null
        state.pendingSplitRail = null
        state.pendingSplitArcLength = null
    }

    internal fun persistPlayerState(player: Player, state: WandPlayerState) {
        persistState(player, state)
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
