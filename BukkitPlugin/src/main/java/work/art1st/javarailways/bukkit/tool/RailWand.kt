package work.art1st.javarailways.bukkit.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
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
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.ui.config.*
import work.art1st.javarailways.basic.curve.Anchor
import work.art1st.javarailways.basic.curve.AnchorGeometry
import work.art1st.javarailways.basic.curve.ParallelAnchorGeometry
import work.art1st.javarailways.basic.curve.ParallelAnchorMatch
import work.art1st.javarailways.basic.curve.RailCurveBuildOptions
import work.art1st.javarailways.basic.curve.RailCurveFactory
import work.art1st.javarailways.basic.curve.RailCurvePartitioner
import work.art1st.javarailways.basic.curve.RailCurveTemplateType
import work.art1st.javarailways.basic.curve.StandardTurnout
import work.art1st.javarailways.basic.curve.StandardTurnoutFactory
import work.art1st.javarailways.basic.curve.SuperelevationConfig
import work.art1st.javarailways.basic.curve.VerticalCurveRebuilder
import work.art1st.javarailways.basic.impl.config.ConfigUIRegistry
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.pathFinding.basicWorldDataOrNull
import work.art1st.javarailways.basic.rail.CurveSafetyMetadata
import work.art1st.javarailways.basic.rail.ParallelRailMetadata
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailAssemblyKind
import work.art1st.javarailways.basic.rail.RailAssemblyMetadata
import work.art1st.javarailways.basic.rail.RailAssemblyRole
import work.art1st.javarailways.basic.rail.RailAutoConnector
import work.art1st.javarailways.basic.rail.RailCurveKind
import work.art1st.javarailways.basic.rail.RailGeometryMetadata
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignalMaterializer
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadataTopology
import work.art1st.javarailways.basic.rail.RailMetadata
import work.art1st.javarailways.basic.rail.RailOperatingMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.TurnoutTopology
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.basic.safety.RailSpeedCalculator
import work.art1st.javarailways.basic.safety.RailSpeedLimitPolicy
import work.art1st.javarailways.basic.safety.RailSpeedLimits
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.operation.OperationResult
import work.art1st.javarailways.bukkit.api.operation.UndoableOperation
import work.art1st.javarailways.bukkit.extension.rail.RailDecorationLayout
import work.art1st.javarailways.bukkit.logic.RailVisualBootstrap
import work.art1st.javarailways.bukkit.persistence.PersistedRailRenderMeta
import work.art1st.javarailways.bukkit.persistence.RailRenderMetaStore
import work.art1st.javarailways.bukkit.ui.dialog.DialogRenderer
import work.art1st.javarailways.bukkit.util.BukkitAsyncTasks
import work.art1st.javarailways.bukkit.util.HeavyRailQueries
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.PlayerOperationGate
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailTopologyEditor
import work.art1st.javarailways.core.physics.rail.RailView
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * 终点的水平位置投影到起点 bearing，垂直高度直接采用右键位置。
 */
internal fun straightTerminalPosition(
    startPosition: Vector3dc,
    startTangent: Vector3dc,
    clickedPoint: Vector3dc
): Vector3d {
    val horizontalTangent = Vector3d(startTangent.x(), 0.0, startTangent.z())
    require(horizontalTangent.lengthSquared() >= 1.0e-12) { "起点缺少可用的水平方向" }
    horizontalTangent.normalize()
    val horizontalOffset = Vector3d(clickedPoint).sub(startPosition).also { it.y = 0.0 }
    val distance = horizontalOffset.dot(horizontalTangent)
    return Vector3d(startPosition)
        .fma(distance, horizontalTangent)
        .also { it.y = clickedPoint.y() }
}

/** 直线的实际三维切线由投影后的终点位置和右键高度共同确定。 */
internal fun straightTerminalTangent(startPosition: Vector3dc, terminalPosition: Vector3dc): Vector3d {
    val chord = Vector3d(terminalPosition).sub(startPosition)
    require(chord.lengthSquared() >= 1.0e-12) { "直线起终点距离过短" }
    return chord.normalize()
}

/** Returns the chord tangent, or null when a connected endpoint is not already collinear. */
internal fun twoPointStraightTangent(
    from: Vector3dc,
    to: Vector3dc,
    currentTangent: Vector3dc,
    endpointIsEmpty: Boolean
): Vector3d? {
    val chord = Vector3d(to).sub(from)
    if (chord.lengthSquared() <= 1.0e-12) return null
    chord.normalize()
    if (!endpointIsEmpty) {
        val current = Vector3d(currentTangent)
        if (current.lengthSquared() <= 1.0e-12) return null
        current.normalize()
        if (abs(current.dot(chord)) < 1.0 - 1.0e-6) return null
        if (current.dot(chord) < 0.0) chord.negate()
    }
    return chord
}

internal fun requiresRailWandVerticalSmoothing(curves: List<PrimeCurve>): Boolean =
    curves.zipWithNext().any { (before, after) ->
        val beforeTangent = before.getPointAt(before.totalArcLength).tangent
        val afterTangent = after.getPointAt(0.0).tangent
        abs(railWandVerticalGrade(beforeTangent) - railWandVerticalGrade(afterTangent)) > 1.0e-6
    }

private fun railWandVerticalGrade(tangent: Vector3dc): Double {
    val horizontal = hypot(tangent.x(), tangent.z())
    return if (horizontal <= 1.0e-12) 0.0 else tangent.y() / horizontal
}

// ═══════════════════════════════════════════════════════════════
// 工作模式
// ═══════════════════════════════════════════════════════════════

enum class WandMode(override val displayName: String, override val description: String) : WandModeSpec {
    CREATE("创建模式", "右键创建锚点 | 左键选择锚点 | Shift+F 切换模式"),
    CONNECT("连接模式", "选择两端锚点后右键连接 | Shift+F 切换模式"),
    GRADE_SMOOTH("坡度平滑", "左键选择既有锚点 | 右键平滑 | Shift+F 切换模式"),
    TURNOUT("道岔模式", "靠近锚点右键创建标准道岔侧线 | Shift+F 切换模式"),
    EDIT("编辑模式", "右键编辑锚点/轨道 | 左键选择 | Shift+F 切换模式"),
    SPLIT("分割模式", "右键选择/确认分割 | 左键分割锚点合并 | Shift+F 切换模式"),
    DELETE("删除模式", "左键锚点/轨道进入待删除 | 再次左键确认 | Shift+F 切换模式"),
    DECORATE("装饰模式", "右键添加装饰 | 左键两次清除装饰 | Shift+F 切换模式");
}

// ═══════════════════════════════════════════════════════════════
// 撤销操作记录
// ═══════════════════════════════════════════════════════════════

sealed class WandAction {
    data class CreateAnchor(val worldId: UUID, val anchorId: String) : WandAction()
    data class DeleteAnchor(val worldId: UUID, val anchor: SavedAnchorData) : WandAction()
    data class CreateRail(val worldId: UUID, val railId: UUID, val railType: String) : WandAction()
    data class DeleteRail(val worldId: UUID, val railId: UUID) : WandAction()
    data class ReplaceRails(
        val worldId: UUID,
        val createdRailIds: Set<UUID>,
        val removedRails: List<CoreRail.SavedRailV1>,
        val worldTopologyBefore: List<CoreRail.SavedRailV1>,
        val renderMetasBefore: List<PersistedRailRenderMeta>,
        val railMetadataBefore: Map<UUID, RailMetadata>,
        val createdToRemovedIds: Map<UUID, UUID>
    ) : WandAction()
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
    /** 装饰模式下等待第二次左键确认清除装饰的轨道。 */
    var pendingDecorationDeleteRail: UUID? = null,
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
    /** 新增装饰和自动装饰使用的已加载轨道模型名。 */
    var decorationModel: String = "standard",
    /** 曲线半径 (m)。0 = 自动选择 */
    var curveRadius: Double = 0.0,
    /** 曲线模板。AUTO 会按端点几何自动分类；STRAIGHT 保持水平朝向并采用右键高度。 */
    var curveTemplate: RailCurveTemplateType = RailCurveTemplateType.AUTO,
    /** 标准道岔号数，同时确定侧线半径和限速。 */
    var turnoutStandard: StandardTurnout = StandardTurnout.N9,
    /** 新建轨道的 SUBGRADE 上级限速。 */
    var designSpeedKmH: Double = 80.0,
    /** CURVE 运营限速；0 表示按曲率和超高自动计算。 */
    var curveSpeedLimitKmH: Double = 0.0,
    /** 运营限速计算允许的欠超高。 */
    var allowableCantDeficiencyMm: Double = 75.0,
    /** 五段式曲线是否按设计时速生成外轨超高。 */
    var automaticSuperelevation: Boolean = true,
    /** 自动超高上限。 */
    var maxSuperelevationMm: Double = 150.0
) : WandState<WandMode>

@Serializable
internal data class SavedRailWandStateV1(
    val mode: String,
    val selectedAnchors: List<String>,
    val selectedRails: List<String>,
    val pendingDeleteAnchor: String?,
    val pendingDeleteRail: String?,
    val pendingSplitRail: String?,
    val pendingSplitArcLength: Double?,
    val pendingDecorationDeleteRail: String?,
    val parallelCount: Int,
    val parallelSpacing: Double,
    val snapToAngle: Boolean,
    val snapToBlock: Boolean,
    val openConfigAfterCreate: Boolean,
    val autoDecorate: Boolean,
    val decorationModel: String,
    val curveRadius: Double,
    val curveTemplate: String,
    val turnoutStandard: String,
    val designSpeedKmH: Double,
    val allowableCantDeficiencyMm: Double,
    val automaticSuperelevation: Boolean,
    val maxSuperelevationMm: Double,
    val curveSpeedLimitKmH: Double = 0.0
)

internal object RailWandStateCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(state: WandPlayerState): String = json.encodeToString(
        SavedRailWandStateV1(
            mode = state.mode.name,
            selectedAnchors = state.selectedAnchors.toList(),
            selectedRails = state.selectedRails.map(UUID::toString),
            pendingDeleteAnchor = state.pendingDeleteAnchor,
            pendingDeleteRail = state.pendingDeleteRail?.toString(),
            pendingSplitRail = state.pendingSplitRail?.toString(),
            pendingSplitArcLength = state.pendingSplitArcLength,
            pendingDecorationDeleteRail = state.pendingDecorationDeleteRail?.toString(),
            parallelCount = state.parallelCount,
            parallelSpacing = state.parallelSpacing,
            snapToAngle = state.snapToAngle,
            snapToBlock = state.snapToBlock,
            openConfigAfterCreate = state.openConfigAfterCreate,
            autoDecorate = state.autoDecorate,
            decorationModel = state.decorationModel,
            curveRadius = state.curveRadius,
            curveTemplate = state.curveTemplate.name,
            turnoutStandard = state.turnoutStandard.name,
            designSpeedKmH = state.designSpeedKmH,
            allowableCantDeficiencyMm = state.allowableCantDeficiencyMm,
            automaticSuperelevation = state.automaticSuperelevation,
            maxSuperelevationMm = state.maxSuperelevationMm,
            curveSpeedLimitKmH = state.curveSpeedLimitKmH
        )
    )

    fun decode(encoded: String): WandPlayerState? = runCatching {
        val saved = json.decodeFromString<SavedRailWandStateV1>(encoded)
        require(saved.parallelCount in 1..10)
        require(saved.parallelSpacing in 1.0..20.0)
        require(saved.pendingSplitArcLength == null || saved.pendingSplitArcLength.isFinite() && saved.pendingSplitArcLength > 0.0)
        require(saved.curveRadius.isFinite() && saved.curveRadius >= 0.0)
        require(saved.designSpeedKmH in 5.0..500.0)
        require(saved.curveSpeedLimitKmH in 0.0..500.0)
        require(saved.allowableCantDeficiencyMm in 0.0..300.0)
        require(saved.maxSuperelevationMm in 0.0..300.0)
        WandPlayerState(
            mode = enumValueOf<WandMode>(saved.mode),
            selectedAnchors = saved.selectedAnchors.toMutableList(),
            selectedRails = saved.selectedRails.map(UUID::fromString).toMutableList(),
            pendingDeleteAnchor = saved.pendingDeleteAnchor,
            pendingDeleteRail = saved.pendingDeleteRail?.let(UUID::fromString),
            pendingSplitRail = saved.pendingSplitRail?.let(UUID::fromString),
            pendingSplitArcLength = saved.pendingSplitArcLength,
            pendingDecorationDeleteRail = saved.pendingDecorationDeleteRail?.let(UUID::fromString),
            parallelCount = saved.parallelCount,
            parallelSpacing = saved.parallelSpacing,
            snapToAngle = saved.snapToAngle,
            snapToBlock = saved.snapToBlock,
            openConfigAfterCreate = saved.openConfigAfterCreate,
            autoDecorate = saved.autoDecorate,
            decorationModel = saved.decorationModel.trim().also { require(it.isNotEmpty()) },
            curveRadius = saved.curveRadius,
            curveTemplate = enumValueOf<RailCurveTemplateType>(saved.curveTemplate),
            turnoutStandard = enumValueOf<StandardTurnout>(saved.turnoutStandard),
            designSpeedKmH = saved.designSpeedKmH,
            curveSpeedLimitKmH = saved.curveSpeedLimitKmH,
            allowableCantDeficiencyMm = saved.allowableCantDeficiencyMm,
            automaticSuperelevation = saved.automaticSuperelevation,
            maxSuperelevationMm = saved.maxSuperelevationMm
        )
    }.getOrNull()
}

// ═══════════════════════════════════════════════════════════════
private const val GRADE_LOCKED_METADATA_KEY = "javarailways.gradeLocked"
private const val SPLIT_OBJECT_CLEARANCE_METERS = 1.0

// RailWand 主类
// ═══════════════════════════════════════════════════════════════

class RailWand(plugin: BukkitPlugin) : AbstractWand<WandPlayerState, WandMode>(
    plugin = plugin,
    wandKey = "javarailways_rail_wand",
    material = Material.STICK,
    itemDisplayName = Component.text("RailWand", NamedTextColor.GOLD),
    itemLore = listOf(
        Component.text("Shift+F 切换模式", NamedTextColor.GRAY),
        Component.text("Shift+右键 打开设置", NamedTextColor.GRAY)
    ),
    modes = WandMode.entries.toList()
) {

    companion object {
        private const val WAND_KEY = "javarailways_rail_wand"
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
    private val transientAnchors = ConcurrentHashMap<UUID, MutableMap<String, Anchor>>()

    /** ConfigAPI 注册中心 + Dialog 渲染器 */
    private val configUI = ConfigUIRegistry()
    private val dialogRenderer = DialogRenderer(configUI)

    /** Wand 设置 ConfigKeys (per-setting, 每个玩家独立 session) */
    private lateinit var wandKeys: WandConfigKeys

    /** Per-player transient anchor marker entities: player UUID -> (anchorId -> Set<entityId>). */
    private val anchorEntities =
        ConcurrentHashMap<UUID, ConcurrentHashMap<String, MutableSet<ItemDisplay>>>()
    private val speedLimitEntities = ConcurrentHashMap<UUID, MutableSet<TextDisplay>>()
    private val heavyOperationGate = PlayerOperationGate()
    private val anchorCreationOperations =
        ConcurrentHashMap<UUID, ConcurrentHashMap<String, RailWandOperation>>()

    private data class SplitPreviewTarget(
        val worldId: UUID,
        val railId: UUID,
        val arcLength: Double,
        val position: Vector3d
    )

    private val splitPreviewTargets = ConcurrentHashMap<UUID, SplitPreviewTarget>()
    private val splitPreviewRequests = ConcurrentHashMap.newKeySet<UUID>()
    private val splitPreviewViewers = ConcurrentHashMap.newKeySet<UUID>()
    private val decorationPreviewViewers = ConcurrentHashMap.newKeySet<UUID>()
    private val anchorViewers = ConcurrentHashMap.newKeySet<UUID>()

    private val createMode = RailWandCreateMode(this)
    private val connectMode = RailWandConnectMode(this)
    private val gradeSmoothMode = RailWandGradeSmoothMode(this)
    private val turnoutMode = RailWandTurnoutMode(this)
    private val editMode = RailWandEditMode(this)
    private val splitMode = RailWandSplitMode(this)
    private val deleteMode = RailWandDeleteMode(this)
    private val decorateMode = RailWandDecorateMode(this)

    override fun createDefaultState(): WandPlayerState = WandPlayerState()

    override fun encodeState(state: WandPlayerState): String = RailWandStateCodec.encode(state)

    override fun decodeState(encoded: String): WandPlayerState? = RailWandStateCodec.decode(encoded)

    override fun onBeforeRegisterEvents() {
        registerWandConfig()
    }

    override fun onAfterRegisterEvents() {
        plugin.platformDispatcher.runGlobalAtFixedRate(1L, 4L) {
            BukkitThreadGuardBridge.runInSimulationContext {
                updateSplitPreviews()
                updateDecorationPreviews()
            }
        }
        plugin.logger.info("RailWand initialized")
    }

    // ═══════════════════════════════════════════
    // ConfigAPI 集成 — Wand 设置
    // ═══════════════════════════════════════════

    /** 每个玩家独立持有 wand 设置，但通过 PlayerConfigSession 隔离值 */
    class WandConfigKeys(
        turnoutLabels: Map<StandardTurnout, String>
    ) {
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
            description = "自动/直线/五段式/S弯。直线保持起点水平朝向，终点采用右键位置的高度。"
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
                turnoutLabels
            )
            group = "wand.settings"
            order = 8
        }
        val designSpeedKmH = ConfigKey.build<Double>("wand.designSpeedKmH") {
            label = "路基限速 SUBGRADE"
            description = "新建轨道的上级基础设施限速，并用于自动计算曲线超高。"
            defaultValue(80.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(5.0, 500.0, 5.0, "km/h")
            group = "wand.settings"
            order = 9
        }
        val curveSpeedLimitKmH = ConfigKey.build<Double>("wand.curveSpeedLimitKmH") {
            label = "曲线限速 CURVE"
            description = "0 表示按曲率和超高自动计算；人工值仍受曲线安全阈值和 SUBGRADE 限制。"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 500.0, 5.0, "km/h")
            group = "wand.settings"
            order = 10
        }
        val allowableCantDeficiencyMm = ConfigKey.build<Double>("wand.allowableCantDeficiencyMm") {
            label = "允许欠超高"
            description = "根据最终曲率和实际超高计算运营限速。"
            defaultValue(75.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 300.0, 5.0, "mm")
            group = "wand.settings"
            order = 11
        }
        val automaticSuperelevation = ConfigKey.build<Boolean>("wand.automaticSuperelevation") {
            label = "自动外轨超高"
            description = "五段式曲线按设计时速和半径自动生成外轨超高。"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 12
        }
        val maxSuperelevationMm = ConfigKey.build<Double>("wand.maxSuperelevationMm") {
            label = "最大外轨超高"
            description = "自动外轨超高的上限。"
            defaultValue(150.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 300.0, 5.0, "mm")
            group = "wand.settings"
            order = 13
        }
    }

    class RailEditKeys {
        val designSpeedKmH = ConfigKey.build<Double>("rail.edit.designSpeedKmH") {
            label = "路基限速 SUBGRADE"
            description = "该逻辑轨道的上级基础设施限速。"
            defaultValue(80.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(5.0, 500.0, 5.0, "km/h")
            group = "rail.edit"
            order = 0
        }
        val curveSpeedLimitKmH = ConfigKey.build<Double>("rail.edit.curveSpeedLimitKmH") {
            label = "曲线限速 CURVE"
            description = "0 表示自动计算；人工值仍受物理安全阈值和 SUBGRADE 限制。"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 500.0, 5.0, "km/h")
            group = "rail.edit"
            order = 1
        }
        val curveRadius = ConfigKey.build<Double>("rail.edit.curveRadius") {
            label = "曲线半径"
            description = "0 表示自动选择；S弯中作为最小半径。并行线路会整组重建。"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 10000.0, 10.0, "m")
            group = "rail.edit"
            order = 2
        }
        val maxSuperelevationMm = ConfigKey.build<Double>("rail.edit.maxSuperelevationMm") {
            label = "最大超高"
            description = "该曲线允许的最大外轨超高；并行线路会整组重建。"
            defaultValue(150.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 300.0, 5.0, "mm")
            group = "rail.edit"
            order = 3
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
        val gradeLocked = ConfigKey.build<Boolean>("anchor.gradeLocked") {
            label = "锁定坡度"; description = "纵断面平滑时保持该锚点当前坡度。"
            defaultValue(false); valueType = ConfigValueType.BOOLEAN; group = "anchor.edit"; order = 7
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
        wandKeys = WandConfigKeys(
            StandardTurnout.entries.associateWith { plugin.physicsConfig.turnoutGeometry(it).displayName }
        )
        editKeys = AnchorEditKeys()
        railEditKeys = RailEditKeys()

        configUI.register(ConfigGroup(
            "wand.settings.general",
            "RailWand 设置 1/3",
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
            "RailWand 设置 2/3",
            description = "普通曲线、标准道岔与基础设施参数",
            items = mutableListOf(
                ConfigItemImpl(wandKeys.curveRadius),
                ConfigItemImpl(wandKeys.curveTemplate),
                ConfigItemImpl(wandKeys.turnoutStandard),
                ConfigItemImpl(wandKeys.designSpeedKmH),
                ConfigItemImpl(wandKeys.curveSpeedLimitKmH),
                ConfigItemImpl(wandKeys.allowableCantDeficiencyMm),
                ConfigItemImpl(wandKeys.automaticSuperelevation),
                ConfigItemImpl(wandKeys.maxSuperelevationMm)
            )
        ))

        // 锚点编辑分组 (单锚点)
        val editGroup = ConfigGroup("anchor.edit", "编辑锚点", description = "修改锚点位置和朝向").apply {
            items.add(ConfigItemImpl(editKeys.posX))
            items.add(ConfigItemImpl(editKeys.posY))
            items.add(ConfigItemImpl(editKeys.posZ))
            items.add(ConfigItemImpl(editKeys.tangentYaw))
            items.add(ConfigItemImpl(editKeys.tangentPitch))
            items.add(ConfigItemImpl(editKeys.roll))
            items.add(ConfigItemImpl(editKeys.gradeLocked))
        }
        configUI.register(editGroup)

        configUI.register(ConfigGroup(
            id = "rail.edit",
            label = "编辑轨道",
            description = "修改轨道段的基础设施属性。修改曲线半径会重建轨道。",
            items = mutableListOf(
                ConfigItemImpl(railEditKeys.designSpeedKmH),
                ConfigItemImpl(railEditKeys.curveSpeedLimitKmH),
                ConfigItemImpl(railEditKeys.curveRadius),
                ConfigItemImpl(railEditKeys.maxSuperelevationMm)
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
        state.curveSpeedLimitKmH = session.getValue(wandKeys.curveSpeedLimitKmH)
            ?: (configUI.get(wandKeys.curveSpeedLimitKmH) ?: 0.0)
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
        session.setValue(wandKeys.curveSpeedLimitKmH, state.curveSpeedLimitKmH)
        session.setValue(wandKeys.allowableCantDeficiencyMm, state.allowableCantDeficiencyMm)
        session.setValue(wandKeys.automaticSuperelevation, state.automaticSuperelevation)
        session.setValue(wandKeys.maxSuperelevationMm, state.maxSuperelevationMm)
    }

    override fun clearSelection(player: Player, state: WandPlayerState) {
        clearSelection(state)
        updatePreviewSubscriptions(player, state)
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
        removeSpeedLimitDisplays(player)
        splitPreviewTargets.remove(player.uniqueId)
        splitPreviewRequests.remove(player.uniqueId)
        splitPreviewViewers.remove(player.uniqueId)
        decorationPreviewViewers.remove(player.uniqueId)
        anchorViewers.remove(player.uniqueId)
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
            WandMode.GRADE_SMOOTH -> gradeSmoothMode.handle(player, state, event)
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
        val twoPointStarts = getCreateModeTwoPointStraightStarts(player, state)
        val newAnchors = try {
            when {
                straightStarts != null -> createStraightTerminalAnchorGroup(player, position, straightStarts)
                twoPointStarts != null -> createTwoPointStraightTerminalAnchorGroup(player, position, twoPointStarts)
                else -> createParallelAnchorGroup(player, position, getSnappedTangent(player, state), state)
            }
        } catch (error: IllegalArgumentException) {
            player.sendActionBar(Component.text(error.message ?: "两点直线不符合既有端点切线", NamedTextColor.RED))
            return
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
     * 直线的第二次右键确定终点：水平位置沿各起点 bearing 投影，
     * 所有终点的垂直高度直接采用右键位置。
     */
    private fun getCreateModeStraightStarts(player: Player, state: WandPlayerState): List<Anchor>? {
        if (state.curveTemplate != RailCurveTemplateType.STRAIGHT) return null
        val count = state.parallelCount.coerceAtLeast(1)
        if (state.selectedAnchors.size != count) return null
        return state.selectedAnchors.map { getAnchor(player.world, it) ?: return null }
    }

    private fun getCreateModeTwoPointStraightStarts(player: Player, state: WandPlayerState): List<Anchor>? {
        if (state.curveTemplate != RailCurveTemplateType.TWO_POINT_STRAIGHT) return null
        val count = state.parallelCount.coerceAtLeast(1)
        if (state.selectedAnchors.size != count) return null
        return state.selectedAnchors.map { getAnchor(player.world, it) ?: return null }
    }

    private fun createTwoPointStraightTerminalAnchorGroup(
        player: Player,
        centerTarget: Vector3dc,
        starts: List<Anchor>
    ): List<Anchor> {
        val centerStart = Vector3d()
        starts.forEach { centerStart.add(it.position) }
        centerStart.div(starts.size.toDouble())
        val displacement = Vector3d(centerTarget).sub(centerStart)
        val plans = starts.map { start ->
            val target = Vector3d(start.position).add(displacement)
            val empty = isAnchorEmpty(player.world, start)
            val startTangent = twoPointStraightTangent(start.position, target, start.tangent, empty)
                ?: throw IllegalArgumentException("两点直线不符合非空端点 ${start.id} 的既有切线")
            Triple(start, target, startTangent)
        }
        plans.forEach { (start, _, tangent) ->
            if (!isDerivedRailAnchorId(start.id)) {
                val updated = start.withTangent(tangent)
                updateAnchor(player.world, updated)
                removeAnchorDisplay(player, start.id)
                spawnAnchorDisplay(player, updated)
            }
        }
        return plans.map { (_, target, tangent) ->
            Anchor(nextAnchorId(), target, Vector3d(tangent), worldId = player.world.uid.toString()).also { anchor ->
                addAnchor(player.world, anchor)
                spawnAnchorDisplay(player, anchor)
            }
        }
    }

    private fun createStraightTerminalAnchorGroup(
        player: Player,
        clickedPoint: Vector3dc,
        starts: List<Anchor>
    ): List<Anchor> = starts.map { start ->
        val terminalPosition = straightTerminalPosition(start.position, start.tangent, clickedPoint)
        val anchor = Anchor(
            id = nextAnchorId(),
            position = terminalPosition,
            tangent = straightTerminalTangent(start.position, terminalPosition),
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
        val curveSpeedLimitKmH: Double,
        val allowableCantDeficiencyMm: Double,
        val automaticSuperelevation: Boolean,
        val maxSuperelevationMm: Double,
        val autoDecorate: Boolean = true,
        val decorationModel: String = "standard"
    )

    private data class BuiltCurve(
        val curve: PrimeCurve,
        val speedLimits: RailSpeedLimits,
        val logicalBreakFractions: List<Double> = emptyList()
    )

    private data class CurvesBuild(
        val curves: List<PrimeCurve>,
        val logicalBreakFractions: List<Double>
    )

    private data class BuiltRailPart(
        val curve: PrimeCurve,
        val speedLimits: RailSpeedLimits,
        val role: RailAssemblyRole
    )

    private data class AdjacentRailBuildInput(
        val railId: UUID,
        val orientedCurve: PrimeCurve,
        val orientedAsAb: Boolean,
        val originalCurve: PrimeCurve,
        val metadata: RailMetadata
    )

    private data class ConnectionCurveBuild(
        val newLanes: List<List<BuiltRailPart>>,
        val replacementCurves: Map<UUID, BuiltCurve>
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
        val decorationModel = if (state.autoDecorate) selectedDecorationModel(player, state) ?: return else null
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "道岔创建")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }
        val worldId = player.world.uid
        val standard = state.turnoutStandard
        val turnoutGeometry = plugin.physicsConfig.turnoutGeometry(standard)
        val cantDeficiencyMeters = state.allowableCantDeficiencyMm / 1000.0
        val derailAcceleration = plugin.physicsConfig.overspeed.derailLateralAccelerationMetersPerSecondSquared
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.createTurnoutAtAnchor",
            supplier = {
                StandardTurnoutFactory.build(
                    anchor,
                    towards,
                    turnoutGeometry,
                    plugin.physicsConfig.geometrySampling.turnoutStepMeters
                )?.let { result ->
                    result to RailSpeedCalculator.calculate(
                        curve = result.curve,
                        requestedLimitMetersPerSecond = turnoutGeometry.divergingSpeedKmH / 3.6,
                        allowableCantDeficiencyMeters = cantDeficiencyMeters,
                        derailLateralAccelerationMetersPerSecondSquared = derailAcceleration,
                        physics = plugin.physicsConfig.railSpeedPhysics()
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
                val existingRails = coreWorld.allRails.toList()
                val rail = CoreRail(
                    coreWorld,
                    result.curve,
                    mutableListOf(),
                    mutableListOf()
                ).also {
                    it.metadata = RailMetadata(
                        designSpeedMetersPerSecond = turnoutGeometry.divergingSpeedKmH / 3.6,
                        speedLimitMetersPerSecond = speedLimits.operatingLimitMetersPerSecond,
                        derailSpeedMetersPerSecond = speedLimits.derailSpeedMetersPerSecond,
                        geometry = RailGeometryMetadata(
                            curveKind = RailCurveKind.FIVE_SEGMENT,
                            curveRadiusMeters = turnoutGeometry.radiusMeters,
                            automaticSuperelevation = false,
                            allowableCantDeficiencyMeters = cantDeficiencyMeters
                        )
                    )
                }
                decorationModel?.let { modelName ->
                    val renderMeta = RailVisualBootstrap.registerRail(rail, modelName)
                    RailRenderMetaStore.upsertRail(livePlayer.world, renderMeta)
                }
                val connectionSnapshots = existingRails.map(::snapshotRailConnections)
                val autoConnected = try {
                    connectCreatedRailsToExisting(listOf(rail), existingRails)
                } catch (failure: Exception) {
                    rail.destroy()
                    coreWorld.removeRail(rail)
                    coreWorld.basicWorldData.railMetadata.remove(rail.id)
                    RailVisualBootstrap.unregisterRail(rail.id)
                    RailRenderMetaStore.removeRail(livePlayer.world, rail.id)
                    restoreRailConnections(connectionSnapshots)
                    livePlayer.sendMessage(Component.text("道岔创建失败：${railWandFailureReason(failure)}", NamedTextColor.RED))
                    return@supply
                }
                pushUndo(livePlayer, WandAction.CreateRail(livePlayer.world.uid, rail.id, decorationModel ?: "none"))
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
                plugin.server.getPlayer(player.uniqueId)?.sendMessage(
                    Component.text("道岔创建失败：${railWandFailureReason(error)}", NamedTextColor.RED)
                )
            }
        )
    }

    private data class RailConnectionSnapshot(
        val rail: CoreRail,
        val abNext: List<RailView>,
        val baNext: List<RailView>,
        val abActive: RailView?,
        val baActive: RailView?,
        val metadata: RailMetadata
    )

    private fun snapshotCurveBuildSettings(state: WandPlayerState): CurveBuildSettingsSnapshot {
        return CurveBuildSettingsSnapshot(
            parallelCount = state.parallelCount,
            parallelSpacing = state.parallelSpacing,
            curveRadius = state.curveRadius,
            curveTemplate = state.curveTemplate,
            designSpeedKmH = state.designSpeedKmH,
            curveSpeedLimitKmH = state.curveSpeedLimitKmH,
            allowableCantDeficiencyMm = state.allowableCantDeficiencyMm,
            automaticSuperelevation = state.automaticSuperelevation,
            maxSuperelevationMm = state.maxSuperelevationMm,
            autoDecorate = state.autoDecorate,
            decorationModel = state.decorationModel
        )
    }

    /** 连接已选择的锚点组，使用 ConstraintSolver 创建曲线 → CoreRail → 渲染 */
    internal fun connectSelectedAnchors(player: Player, state: WandPlayerState) {
        if (state.mode != WandMode.CREATE && state.curveTemplate == RailCurveTemplateType.TWO_POINT_STRAIGHT) {
            player.sendActionBar(Component.text("两点直线模板仅用于创建模式", NamedTextColor.RED))
            return
        }
        val expectedCount = state.parallelCount * 2
        if (state.selectedAnchors.size != expectedCount) {
            player.sendActionBar(Component.text(
                "需要选择 $expectedCount 个锚点 (当前: ${state.selectedAnchors.size})", NamedTextColor.RED
            ))
            return
        }

        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(Component.text("错误: 无法获取 JavaRailways 世界", NamedTextColor.RED))
            return
        }

        val anchors = state.selectedAnchors.mapNotNull { getAnchor(player.world, it) }
        if (anchors.size != expectedCount) {
            player.sendActionBar(Component.text("错误: 部分锚点不存在", NamedTextColor.RED))
            return
        }

        val parallelMatch = ParallelAnchorGeometry.match(
            anchors.take(state.parallelCount),
            anchors.drop(state.parallelCount),
            state.parallelSpacing
        )
        if (parallelMatch is ParallelAnchorMatch.Failure) {
            player.sendActionBar(Component.text(parallelMatch.reason, NamedTextColor.RED))
            return
        }
        parallelMatch as ParallelAnchorMatch.Success
        val startAnchors = parallelMatch.start
        val endAnchors = parallelMatch.end
        val startAdjacent = startAnchors.map { adjacentRailBuildInput(coreWorld, it, connectionAtStart = true) }
        val endAdjacent = endAnchors.map { adjacentRailBuildInput(coreWorld, it, connectionAtStart = false) }
        val adjacentIds = (startAdjacent + endAdjacent).mapNotNull { it?.railId }
        if (adjacentIds.size != adjacentIds.distinct().size) {
            player.sendActionBar(Component.text("同一条相邻轨道不能在一次连接中重复重算", NamedTextColor.RED))
            return
        }
        if (state.autoDecorate && selectedDecorationModel(player, state) == null) return

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
            completionEntity = player,
            operation = "RailWand.connectSelectedAnchors",
            supplier = {
                createConnectionCurves(
                    startAnchors,
                    endAnchors,
                    startAdjacent,
                    endAdjacent,
                    settingsSnapshot
                )
            },
            onSuccess = { connectionBuild ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveCoreWorld = getCoreWorld(livePlayer) ?: run {
                    livePlayer.sendActionBar(Component.text("错误: 无法获取 JavaRailways 世界", NamedTextColor.RED))
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
                if (connectionBuild == null) {
                    livePlayer.sendActionBar(Component.text("无法创建并平滑轨道曲线", NamedTextColor.RED))
                    return@supply
                }
                val builtLanes = connectionBuild.newLanes
                val liveAdjacentRails = connectionBuild.replacementCurves.keys.associateWith { railId ->
                    val rail = liveCoreWorld.getRail(railId) ?: run {
                        livePlayer.sendActionBar(Component.text("相邻轨道已变化，请重试", NamedTextColor.YELLOW))
                        return@supply
                    }
                    val expected = (startAdjacent + endAdjacent).first { it?.railId == railId }!!
                    if (rail.curve !== expected.originalCurve || rail.trains.isNotEmpty()) {
                        livePlayer.sendActionBar(Component.text("相邻轨道已变化，请重试", NamedTextColor.YELLOW))
                        return@supply
                    }
                    rail
                }

                val existingRails = liveCoreWorld.allRails.toList()
                val topologyBefore = existingRails.map(CoreRail::save)
                val renderMetasBefore = RailRenderMetaStore.load(livePlayer.world)
                val railMetadataBefore = liveCoreWorld.basicWorldData.railMetadata.snapshot()
                val renderMetaByRail = renderMetasBefore.associateBy { it.railId }
                val removedIds = liveAdjacentRails.keys
                val removedSaved = liveAdjacentRails.values.map(CoreRail::save)
                val createdRails = mutableListOf<CoreRail>()
                val newRails = mutableListOf<CoreRail>()
                val persistedMetas = mutableListOf<PersistedRailRenderMeta>()
                val oldToNew = linkedMapOf<UUID, UUID>()
                val existingConnectionSnapshots = existingRails.map(::snapshotRailConnections)
                var destinationsMigrated = false
                liveCoreWorld.railTopologyTransaction {
                try {
                    connectionBuild.replacementCurves.forEach { (oldId, builtCurve) ->
                        val oldRail = liveAdjacentRails.getValue(oldId)
                        val metadata = oldRail.metadata.copy(
                            speedLimitMetersPerSecond = builtCurve.speedLimits.operatingLimitMetersPerSecond,
                            derailSpeedMetersPerSecond = builtCurve.speedLimits.derailSpeedMetersPerSecond
                        )
                        val replacement = CoreRail(
                            liveCoreWorld,
                            builtCurve.curve,
                            mutableListOf(),
                            mutableListOf()
                        ).also { it.metadata = metadata }
                        createdRails += replacement
                        oldToNew[oldId] = replacement.id
                        renderMetaByRail[oldId]?.let { oldMeta ->
                            persistedMetas += registerRailWithTemplate(replacement, oldMeta.templateSnapshot)
                        }
                    }

                    val segmentCount = builtLanes.firstOrNull()?.size ?: 0
                    require(segmentCount > 0 && builtLanes.all { it.size == segmentCount }) {
                        "并行逻辑曲线的内部段数量不一致"
                    }
                    val assemblyGroupId = UUID.randomUUID().toString().takeIf { segmentCount > 1 }
                    val parallelGroupIds = List(segmentCount) {
                        UUID.randomUUID().toString().takeIf { builtLanes.size > 1 }
                    }
                    val newLaneRails = builtLanes.mapIndexed { laneIndex, parts ->
                        parts.mapIndexed { segmentIndex, part ->
                            val parallel = parallelGroupIds[segmentIndex]?.let {
                                ParallelRailMetadata(it, laneIndex, builtLanes.size, settingsSnapshot.parallelSpacing)
                            }
                            val assembly = assemblyGroupId?.let {
                                RailAssemblyMetadata(
                                    groupId = it,
                                    kind = RailAssemblyKind.FIVE_SEGMENT_CURVE,
                                    laneIndex = laneIndex,
                                    laneCount = builtLanes.size,
                                    segmentIndex = segmentIndex,
                                    segmentCount = segmentCount,
                                    role = part.role
                                )
                            }
                            val metadata = railMetadata(settingsSnapshot, part.speedLimits, parallel).copy(
                                assembly = assembly,
                                gradeLockedAtA = segmentIndex == 0 && isGradeLocked(startAnchors[laneIndex]),
                                gradeLockedAtB = segmentIndex == segmentCount - 1 && isGradeLocked(endAnchors[laneIndex])
                            )
                            CoreRail(
                                liveCoreWorld,
                                part.curve,
                                mutableListOf(),
                                mutableListOf()
                            ).also { rail ->
                                rail.metadata = metadata
                                createdRails += rail
                                newRails += rail
                            }
                        }
                    }
                    if (settingsSnapshot.autoDecorate) {
                        newLaneRails.flatten().forEach { rail ->
                            persistedMetas += RailVisualBootstrap.registerRail(rail, settingsSnapshot.decorationModel)
                        }
                    }

                    val unchangedRails = existingRails.filter { it.id !in removedIds }
                    detachRailReferences(removedIds, unchangedRails)
                    val autoConnected = connectCreatedRailsToExisting(createdRails, unchangedRails + createdRails)
                    RailRenderMetaStore.saveAll(
                        livePlayer.world,
                        renderMetasBefore.filterNot { it.railId in removedIds } + persistedMetas
                    )
                    if (oldToNew.isNotEmpty()) {
                        OperationalDestinationService(liveCoreWorld).migrateAllOnRails(oldToNew)
                        destinationsMigrated = true
                    }
                    liveAdjacentRails.values.forEach { oldRail ->
                        RailVisualBootstrap.unregisterRail(oldRail.id)
                        oldRail.destroy()
                        liveCoreWorld.removeRail(oldRail)
                        liveCoreWorld.basicWorldData.railMetadata.remove(oldRail.id)
                    }

                    discardCreatedAnchorOperations(livePlayer, selectionSnapshot.toSet())
                    pushUndo(livePlayer, WandAction.ReplaceRails(
                        worldId = livePlayer.world.uid,
                        createdRailIds = createdRails.mapTo(linkedSetOf(), CoreRail::id),
                        removedRails = removedSaved,
                        worldTopologyBefore = topologyBefore,
                        renderMetasBefore = renderMetasBefore,
                        railMetadataBefore = railMetadataBefore,
                        createdToRemovedIds = oldToNew.entries.associate { (oldId, newId) -> newId to oldId }
                    ))
                    livePlayer.sendMessage(Component.text(
                        "✓ 创建 ${builtLanes.size} 条曲线（${newRails.size} 个内部段）并平滑 ${oldToNew.size} 段相邻轨道，自动连接 $autoConnected 条",
                        NamedTextColor.GREEN
                    ))
                    val endpointSelection = newLaneRails.map { lane -> railEndpointAnchorId(lane.last().id, 'B') }
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
                    if (destinationsMigrated && oldToNew.values.all { liveCoreWorld.getRail(it) != null }) {
                        runCatching {
                            OperationalDestinationService(liveCoreWorld).migrateAllOnRails(
                                oldToNew.entries.associate { (oldId, newId) -> newId to oldId }
                            )
                        }
                    }
                    createdRails.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        rail.destroy()
                        liveCoreWorld.removeRail(rail)
                        liveCoreWorld.basicWorldData.railMetadata.remove(rail.id)
                    }
                    restoreRailConnections(existingConnectionSnapshots)
                    RailRenderMetaStore.saveAll(livePlayer.world, renderMetasBefore)
                    livePlayer.sendMessage(Component.text("创建轨道失败：${railWandFailureReason(e)}", NamedTextColor.RED))
                }
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning(
                    "RailWand connect failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                plugin.server.getPlayer(player.uniqueId)?.sendMessage(
                    Component.text("轨道连接失败：${railWandFailureReason(error)}", NamedTextColor.RED)
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
            player.sendActionBar(Component.text("错误: 无法获取 JavaRailways 世界", NamedTextColor.RED))
            return
        }
        val editedRail = coreWorld.getRail(editedRailId) ?: run {
            player.sendActionBar(Component.text("轨道不存在: $editedRailId", NamedTextColor.RED))
            return
        }
        val movedPoint = if (editedEnd == 'A') editedRail.viewAB.startPoint.position else editedRail.viewAB.endPoint.position
        val affectedRails = coreWorld.allRails.filter { rail ->
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
        if (affectedRails.any(TurnoutTopology::hasTurnoutEndpoint)) {
            player.sendActionBar(Component.text("不能修改道岔节点相邻轨道的几何", NamedTextColor.RED))
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
            completionEntity = player,
            operation = "RailWand.rebuildRailForEndpointEdit",
            supplier = {
                val curvesByRailId = linkedMapOf<UUID, BuiltCurve>()
                val derailAcceleration = plugin.physicsConfig.overspeed.derailLateralAccelerationMetersPerSecondSquared
                for (input in rebuildInputs) {
                    val newStart = if (input.movedEnd == 'A') updatedEndpoint else input.oldStart
                    val newEnd = if (input.movedEnd == 'B') updatedEndpoint else input.oldEnd
                    val inputSettings = if (input.oldMetadata.assembly?.role == RailAssemblyRole.LEADING_TANGENT ||
                        input.oldMetadata.assembly?.role == RailAssemblyRole.TRAILING_TANGENT
                    ) {
                        settingsSnapshot.copy(
                            curveRadius = 0.0,
                            curveTemplate = RailCurveTemplateType.STRAIGHT,
                            automaticSuperelevation = false
                        )
                    } else settingsSnapshot
                    val curve = createCurveBetweenAnchors(newStart, newEnd, inputSettings) ?: return@supply null
                    val limits = RailSpeedCalculator.calculate(
                        curve,
                        settingsSnapshot.designSpeedKmH / 3.6,
                        settingsSnapshot.allowableCantDeficiencyMm / 1000.0,
                        derailAcceleration,
                        plugin.physicsConfig.railSpeedPhysics()
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
                    livePlayer.sendActionBar(Component.text("错误: 无法获取 JavaRailways 世界", NamedTextColor.RED))
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
                val liveExistingMetaByRail = liveExistingMetas.associateBy { it.railId }
                val liveAllRails = liveCoreWorld.allRails.toList()
                val snapshots = liveAllRails.map { snapshotRailConnections(it) }
                val createdRails = mutableListOf<CoreRail>()
                val createdMetas = mutableListOf<PersistedRailRenderMeta>()
                val oldIds = rebuildInputs.map { it.railId }.toSet()
                val oldToNew = mutableMapOf<UUID, CoreRail>()
                try {
                    rebuildInputs.forEach { input ->
                        val builtCurve = curvesByRailId[input.railId] ?: error("Missing rebuilt curve for ${input.railId}")
                        val newMetadata = input.oldMetadata.copy(
                            speedLimitMetersPerSecond = builtCurve.speedLimits.operatingLimitMetersPerSecond,
                            derailSpeedMetersPerSecond = builtCurve.speedLimits.derailSpeedMetersPerSecond,
                            gradeLockedAtA = if (input.movedEnd == 'A') {
                                isGradeLocked(updatedEndpoint)
                            } else input.oldMetadata.gradeLockedAtA,
                            gradeLockedAtB = if (input.movedEnd == 'B') {
                                isGradeLocked(updatedEndpoint)
                            } else input.oldMetadata.gradeLockedAtB
                        )
                        val newRail = CoreRail(
                            liveCoreWorld,
                            builtCurve.curve,
                            mutableListOf(),
                            mutableListOf()
                        ).also { it.metadata = newMetadata }
                        createdRails.add(newRail)
                        liveExistingMetaByRail[input.railId]?.let { oldMeta ->
                            createdMetas.add(registerRailWithTemplate(newRail, oldMeta.templateSnapshot))
                        }
                        oldToNew[input.railId] = newRail
                    }

                    val nextMetas = liveExistingMetas.filterNot { it.railId in oldIds } + createdMetas
                    RailRenderMetaStore.saveAll(livePlayer.world, nextMetas)

                    detachRailReferences(oldIds, liveAllRails.filter { it.id !in oldIds })
                    connectCreatedRailsToExisting(
                        createdRails,
                        liveAllRails.filter { it.id !in oldIds } + createdRails
                    )

                    OperationalDestinationService(liveCoreWorld).migrateAllOnRails(
                        oldToNew.mapValues { (_, replacement) -> replacement.id }
                    )
                    liveAffectedRails.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        rail.destroy()
                        liveCoreWorld.removeRail(rail)
                        liveCoreWorld.basicWorldData.railMetadata.remove(rail.id)
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
                        liveCoreWorld.basicWorldData.railMetadata.remove(rail.id)
                    }
                    restoreRailConnections(snapshots)
                    RailRenderMetaStore.saveAll(livePlayer.world, liveExistingMetas)
                    liveExistingMetas.forEach { RailVisualBootstrap.fromPersisted(liveCoreWorld, it) }
                    livePlayer.sendMessage(Component.text("端点编辑失败，已回滚：${railWandFailureReason(e)}", NamedTextColor.RED))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning(
                    "RailWand endpoint rebuild failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                plugin.server.getPlayer(player.uniqueId)?.sendMessage(
                    Component.text("轨道端点重建失败：${railWandFailureReason(error)}", NamedTextColor.RED)
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
                worldId = world.uid.toString(),
                metadata = railEndpointMetadata(rail, end)
            ),
            AnchorGeometry.rollDegreesFromNormal(point.tangent, point.normal)
        )
    }

    private fun snapshotRailConnections(rail: CoreRail): RailConnectionSnapshot = RailConnectionSnapshot(
        rail = rail,
        abNext = rail.viewAB.mutableNextRails.toList(),
        baNext = rail.viewBA.mutableNextRails.toList(),
        abActive = rail.viewAB.activeRail,
        baActive = rail.viewBA.activeRail,
        metadata = rail.metadata
    )

    private fun restoreRailConnections(snapshots: List<RailConnectionSnapshot>) {
        snapshots.forEach { snapshot ->
            snapshot.rail.metadata = snapshot.metadata
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

    /**
     * 将本次新建轨道与既有轨道中端点重合的部分自动连接。
     * 创建模式每次通常只创建一段 A->B；下一次从 B->C 时，新段不会进入同一批 createdRails，
     * 因此必须显式把新段连接到之前已经存在的 B 端轨道。
     */
    private fun connectCreatedRailsToExisting(createdRails: List<CoreRail>, existingRails: List<CoreRail>): Int {
        var connected = 0
        val checkedPairs = HashSet<Pair<UUID, UUID>>()
        val options = RailAutoConnector.Options(
            endpointTolerance = Curve.TOLERANCE,
            topologyRails = existingRails
        )
        for (created in createdRails) {
            for (existing in existingRails) {
                if (created == existing) continue
                val key = if (created.id.toString() <= existing.id.toString()) {
                    created.id to existing.id
                } else {
                    existing.id to created.id
                }
                if (!checkedPairs.add(key)) continue
                val result = RailAutoConnector.connectIfAllowed(created, existing, options)
                when (result.outcome) {
                    RailAutoConnector.Outcome.CONNECTED -> connected++
                    RailAutoConnector.Outcome.ALREADY_CONNECTED,
                    RailAutoConnector.Outcome.TOO_FAR,
                    RailAutoConnector.Outcome.ANGLE_TOO_SHARP,
                    RailAutoConnector.Outcome.INVALID_TANGENT -> Unit
                    RailAutoConnector.Outcome.TOO_MANY_ENDPOINTS ->
                        throw IllegalArgumentException("一个锚点最多只能连接三条轨道")
                    RailAutoConnector.Outcome.AMBIGUOUS_TURNOUT ->
                        throw IllegalArgumentException("三腿节点无法唯一确定道岔 stem，请调整轨道方向")
                    RailAutoConnector.Outcome.TURNOUT_ANGLE_TOO_SHARP ->
                        throw IllegalArgumentException("三腿节点的道岔转向角过大")
                    RailAutoConnector.Outcome.TURNOUT_INVALID_TANGENT ->
                        throw IllegalArgumentException("三腿节点包含无效的轨道方向")
                    RailAutoConnector.Outcome.TURNOUT_LOCKING_DISTANCE_UNAVAILABLE ->
                        throw IllegalArgumentException(
                            "无法创建道岔：轨道 ${result.railId} 可用锁定长度 " +
                                "${"%.3f".format(result.availableLengthMeters)}m，小于默认要求 " +
                                "${"%.3f".format(result.requiredLengthMeters)}m；请延长轨道或更换接入位置"
                        )
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
            val detail = listOfNotNull(result.selectedReason, result.error)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("；")
                .ifBlank { "无法根据当前锚点和模板生成曲线" }
            plugin.logger.warning(
                "RailWand curve build failed: template=${result.selectedTemplate ?: settings.curveTemplate}, " +
                    "reason=${result.selectedReason ?: "n/a"}, error=${result.error ?: "unknown error"}"
            )
            throw IllegalArgumentException(detail)
        }
        return result.curve
    }

    private fun createCurvesBetweenParallelAnchors(
        startAnchors: List<Anchor>,
        endAnchors: List<Anchor>,
        settings: CurveBuildSettingsSnapshot
    ): CurvesBuild? {
        val options = railCurveBuildOptions(settings)
        val result = RailCurveFactory.buildParallel(startAnchors, endAnchors, options)
        if (!result.isSuccess) {
            val detail = listOfNotNull(result.selectedReason, result.error)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("；")
                .ifBlank { "无法根据当前平行锚点和模板生成曲线" }
            plugin.logger.warning(
                "RailWand parallel curve build failed: template=${result.selectedTemplate ?: settings.curveTemplate}, " +
                    "reason=${result.selectedReason ?: "n/a"}, error=${result.error ?: "unknown error"}"
            )
            throw IllegalArgumentException(detail)
        }
        return CurvesBuild(result.curves!!, result.logicalBreakFractions)
    }

    private fun adjacentRailBuildInput(
        world: CoreWorld,
        anchor: Anchor,
        connectionAtStart: Boolean
    ): AdjacentRailBuildInput? {
        val (railId, end) = parseRailEndpointAnchorId(anchor.id) ?: return null
        val rail = world.getRail(railId) ?: return null
        if (TurnoutTopology.hasTurnoutEndpoint(rail) || rail.trains.isNotEmpty()) return null
        val orientedAsAb = if (connectionAtStart) end == 'B' else end == 'A'
        return AdjacentRailBuildInput(
            railId = rail.id,
            orientedCurve = if (orientedAsAb) rail.curve else rail.curve.reversed,
            orientedAsAb = orientedAsAb,
            originalCurve = rail.curve,
            metadata = rail.metadata
        )
    }

    @OptIn(Heavy::class)
    private fun createConnectionCurves(
        startAnchors: List<Anchor>,
        endAnchors: List<Anchor>,
        startAdjacent: List<AdjacentRailBuildInput?>,
        endAdjacent: List<AdjacentRailBuildInput?>,
        settings: CurveBuildSettingsSnapshot
    ): ConnectionCurveBuild? {
        val newBuilt = createCurvesWithSpeedLimits(startAnchors, endAnchors, settings)?.toMutableList() ?: return null
        val replacements = linkedMapOf<UUID, BuiltCurve>()
        for (index in newBuilt.indices) {
            val start = startAdjacent[index]
            val end = endAdjacent[index]
            val chain = buildList {
                start?.let { add(it.orientedCurve) }
                add(newBuilt[index].curve)
                end?.let { add(it.orientedCurve) }
            }
            if (!requiresRailWandVerticalSmoothing(chain)) continue
            val fixedGrades = buildMap {
                if (isGradeLocked(startAnchors[index])) {
                    val node = if (start == null) 0 else 1
                    put(node, railWandVerticalGrade(startAnchors[index].tangent))
                }
                if (isGradeLocked(endAnchors[index])) {
                    val node = if (end == null) chain.size else chain.lastIndex
                    put(node, railWandVerticalGrade(endAnchors[index].tangent))
                }
            }
            val rebuilt = VerticalCurveRebuilder.smooth(chain, fixedGrades = fixedGrades)
            var rebuiltIndex = 0
            if (start != null) {
                val curve = rebuilt[rebuiltIndex++]
                val canonical = if (start.orientedAsAb) curve else VerticalCurveRebuilder.reversedCopy(curve)
                replacements[start.railId] = BuiltCurve(canonical, calculateSpeedLimits(canonical, start.metadata, settings))
            }
            val rebuiltNew = rebuilt[rebuiltIndex++]
            newBuilt[index] = BuiltCurve(
                rebuiltNew,
                calculateSpeedLimits(rebuiltNew, null, settings),
                newBuilt[index].logicalBreakFractions
            )
            if (end != null) {
                val curve = rebuilt[rebuiltIndex]
                val canonical = if (end.orientedAsAb) curve else VerticalCurveRebuilder.reversedCopy(curve)
                require(replacements.put(end.railId, BuiltCurve(
                        canonical,
                        calculateSpeedLimits(canonical, end.metadata, settings)
                    )) == null
                ) { "同一条相邻轨道被多个连接端重复重建" }
            }
        }
        return ConnectionCurveBuild(
            newBuilt.map { partitionBuiltCurve(it, settings) },
            replacements
        )
    }

    @OptIn(Heavy::class)
    private fun calculateSpeedLimits(
        curve: PrimeCurve,
        metadata: RailMetadata?,
        settings: CurveBuildSettingsSnapshot
    ): RailSpeedLimits {
        val subgradeLimit = metadata?.speedLimits?.value(work.art1st.javarailways.basic.rail.SpeedLimitType.SUBGRADE)
            ?: metadata?.designSpeedMetersPerSecond
            ?: settings.designSpeedKmH / 3.6
        val manualCurveLimit = if (metadata == null) {
            settings.curveSpeedLimitKmH.takeIf { it > 0.0 }?.div(3.6)
        } else {
            metadata.speedLimits.value(work.art1st.javarailways.basic.rail.SpeedLimitType.CURVE)
                ?.takeIf { metadata.authoring?.curveSpeedLimitAutomatic == false }
        }
        val geometryLimits = RailSpeedCalculator.calculate(
            curve = curve,
            requestedLimitMetersPerSecond = subgradeLimit,
            allowableCantDeficiencyMeters = metadata?.geometry?.allowableCantDeficiencyMeters
                ?: settings.allowableCantDeficiencyMm / 1000.0,
            derailLateralAccelerationMetersPerSecondSquared =
                plugin.physicsConfig.overspeed.derailLateralAccelerationMetersPerSecondSquared,
            physics = plugin.physicsConfig.railSpeedPhysics()
        )
        return RailSpeedLimitPolicy.apply(geometryLimits, subgradeLimit, manualCurveLimit)
    }

    @OptIn(Heavy::class)
    private fun partitionBuiltCurve(
        built: BuiltCurve,
        settings: CurveBuildSettingsSnapshot,
        metadata: RailMetadata? = null
    ): List<BuiltRailPart> {
        if (built.logicalBreakFractions.size != 2) {
            return listOf(BuiltRailPart(built.curve, built.speedLimits, RailAssemblyRole.CURVE))
        }
        return RailCurvePartitioner.partitionFiveSegment(built.curve, built.logicalBreakFractions).map { part ->
            BuiltRailPart(
                part.curve,
                calculateSpeedLimits(part.curve, metadata, settings),
                part.role
            )
        }
    }

    @OptIn(Heavy::class)
    private fun createCurvesWithSpeedLimits(
        startAnchors: List<Anchor>,
        endAnchors: List<Anchor>,
        settings: CurveBuildSettingsSnapshot
    ): List<BuiltCurve>? {
        val build = createCurvesBetweenParallelAnchors(startAnchors, endAnchors, settings) ?: return null
        return build.curves.map { curve ->
            BuiltCurve(
                curve,
                calculateSpeedLimits(curve, null, settings),
                build.logicalBreakFractions
            )
        }
    }

    private fun railCurveBuildOptions(state: WandPlayerState): RailCurveBuildOptions {
        return railCurveBuildOptions(snapshotCurveBuildSettings(state))
    }

    private fun railCurveBuildOptions(settings: CurveBuildSettingsSnapshot): RailCurveBuildOptions {
        val physics = plugin.physicsConfig
        return RailCurveBuildOptions(
            template = settings.curveTemplate,
            radius = settings.curveRadius,
            superelevation = if (settings.automaticSuperelevation) {
                SuperelevationConfig(
                    maxSuperelevation = settings.maxSuperelevationMm / 1000.0,
                    designSpeedKmH = (
                        settings.designSpeedKmH - physics.track.cantBalanceSpeedOffsetKmH
                    ).coerceAtLeast(0.0),
                    maxCantGradient = physics.track.maximumCantGradient,
                    gaugeMeters = physics.track.gaugeMeters,
                    gravityMetersPerSecondSquared = physics.track.gravityMetersPerSecondSquared
                )
            } else {
                null
            },
            geometry = physics.railGeometryParameters()
        )
    }

    private fun railMetadata(
        settings: CurveBuildSettingsSnapshot,
        speedLimits: RailSpeedLimits,
        parallel: ParallelRailMetadata? = null,
        allowedDirection: ViewDirection? = null
    ): RailMetadata {
        val subgrade = settings.designSpeedKmH / 3.6
        val limits = buildList {
            add(SpeedLimitEntry(subgrade, SpeedLimitType.SUBGRADE))
            if (speedLimits.derailSpeedMetersPerSecond != null) {
                add(SpeedLimitEntry(speedLimits.operatingLimitMetersPerSecond, SpeedLimitType.CURVE))
            }
        }
        return RailMetadata(
            authoring = RailAuthoringMetadata(
                designSpeedMetersPerSecond = subgrade,
                geometry = RailGeometryMetadata(
                    curveKind = RailCurveKind.valueOf(settings.curveTemplate.name),
                    curveRadiusMeters = settings.curveRadius.takeIf { it > 0.0 },
                    automaticSuperelevation = settings.automaticSuperelevation,
                    maxSuperelevationMeters = settings.maxSuperelevationMm / 1000.0,
                    allowableCantDeficiencyMeters = settings.allowableCantDeficiencyMm / 1000.0,
                    parallel = parallel
                ),
                curveSpeedLimitAutomatic = settings.curveSpeedLimitKmH <= 0.0
            ),
            operating = RailOperatingMetadata(allowedDirection = allowedDirection),
            speedLimits = SpeedLimitMetadata(limits),
            curveSafety = CurveSafetyMetadata(speedLimits.derailSpeedMetersPerSecond)
        )
    }

    // ═══════════════════════════════════════════
    // 坡度平滑模式
    // ═══════════════════════════════════════════

    private data class GradeChainRail(
        val rail: CoreRail,
        val orientedCurve: PrimeCurve,
        val orientedAsAb: Boolean
    )

    private fun lockedGrades(chain: List<GradeChainRail>): Map<Int, Double> = buildMap {
        chain.forEachIndexed { index, input ->
            val startLocked = if (input.orientedAsAb) input.rail.metadata.gradeLockedAtA
                else input.rail.metadata.gradeLockedAtB
            val endLocked = if (input.orientedAsAb) input.rail.metadata.gradeLockedAtB
                else input.rail.metadata.gradeLockedAtA
            if (startLocked) {
                put(index, railWandVerticalGrade(input.orientedCurve.getPointAt(0.0).tangent))
            }
            if (endLocked) {
                put(index + 1, railWandVerticalGrade(
                    input.orientedCurve.getPointAt(input.orientedCurve.totalArcLength).tangent
                ))
            }
        }
    }

    internal fun smoothSelectedGrades(player: Player, state: WandPlayerState) {
        val count = state.parallelCount.coerceAtLeast(1)
        if (state.selectedAnchors.isEmpty() || state.selectedAnchors.size % count != 0) {
            player.sendActionBar(Component.text("请选择完整的并行锚点横断面", NamedTextColor.RED))
            return
        }
        val world = getCoreWorld(player) ?: return
        val rawGroups = state.selectedAnchors.chunked(count).map { ids ->
            ids.map { id -> getAnchor(player.world, id) ?: run {
                player.sendActionBar(Component.text("选中的锚点已不存在", NamedTextColor.RED))
                return
            } }
        }
        val groups = mutableListOf<List<Anchor>>()
        if (rawGroups.size == 1) {
            groups += rawGroups.single()
        } else {
            val first = ParallelAnchorGeometry.match(rawGroups[0], rawGroups[1], state.parallelSpacing)
            if (first is ParallelAnchorMatch.Failure) {
                player.sendActionBar(Component.text(first.reason, NamedTextColor.RED))
                return
            }
            first as ParallelAnchorMatch.Success
            groups += first.start
            groups += first.end
            for (index in 2 until rawGroups.size) {
                val next = ParallelAnchorGeometry.match(groups.last(), rawGroups[index], state.parallelSpacing)
                if (next is ParallelAnchorMatch.Failure) {
                    player.sendActionBar(Component.text(next.reason, NamedTextColor.RED))
                    return
                }
                groups += (next as ParallelAnchorMatch.Success).end
            }
        }

        val chains = mutableListOf<List<GradeChainRail>>()
        for (lane in 0 until count) {
            val controls = groups.map { it[lane] }
            val chain = findGradeSmoothingChain(world, controls) ?: run {
                player.sendActionBar(Component.text("控制锚点之间不是唯一连续的普通轨道", NamedTextColor.RED))
                return
            }
            chains += chain
        }
        val affectedIds = chains.flatten().map { it.rail.id }
        if (affectedIds.size != affectedIds.distinct().size) {
            player.sendActionBar(Component.text("并行平滑路径发生交叉或重复", NamedTextColor.RED))
            return
        }
        if (chains.map(List<GradeChainRail>::size).distinct().size != 1) {
            player.sendActionBar(Component.text("并行线路的曲线段数量不一致", NamedTextColor.RED))
            return
        }
        if (chains.flatten().any { it.rail.trains.isNotEmpty() }) {
            player.sendActionBar(Component.text("不能平滑有列车占用的轨道", NamedTextColor.RED))
            return
        }

        val pending = heavyOperationGate.tryStart(player.uniqueId, "坡度平滑")
        if (pending != null) {
            player.sendActionBar(Component.text("正在执行$pending，请稍候", NamedTextColor.YELLOW))
            return
        }
        val settings = snapshotCurveBuildSettings(state)
        val worldId = player.world.uid
        val selectedSnapshot = state.selectedAnchors.toList()
        player.sendActionBar(Component.text("正在联合求解纵断面...", NamedTextColor.GRAY))
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.smoothSelectedGrades",
            supplier = {
                val rebuilt = linkedMapOf<UUID, BuiltCurve>()
                chains.forEach { chain ->
                    val curves = VerticalCurveRebuilder.smooth(
                        chain.map(GradeChainRail::orientedCurve),
                        fixedGrades = lockedGrades(chain)
                    )
                    chain.zip(curves).forEach { (input, oriented) ->
                        val canonical = if (input.orientedAsAb) oriented else VerticalCurveRebuilder.reversedCopy(oriented)
                        rebuilt[input.rail.id] = BuiltCurve(
                            canonical,
                            calculateSpeedLimits(canonical, input.rail.metadata, settings)
                        )
                    }
                }
                rebuilt
            },
            onSuccess = { rebuilt ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                if (getState(livePlayer).selectedAnchors != selectedSnapshot) {
                    livePlayer.sendActionBar(Component.text("锚点选择已变化，已取消坡度平滑", NamedTextColor.YELLOW))
                    return@supply
                }
                val oldRails = affectedIds.map { id -> liveWorld.getRail(id) ?: return@supply }
                if (oldRails.any { it.trains.isNotEmpty() } || oldRails.zip(chains.flatten()).any { (live, planned) ->
                        live.curve !== planned.rail.curve
                    }
                ) {
                    livePlayer.sendActionBar(Component.text("轨道已变化，请重试", NamedTextColor.YELLOW))
                    return@supply
                }
                commitGradeSmoothing(livePlayer, oldRails, rebuilt, selectedSnapshot)
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail grade smoothing failed: ${error.message ?: error::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.sendMessage(
                    Component.text("坡度平滑失败：${railWandFailureReason(error)}", NamedTextColor.RED)
                )
            }
        )
    }

    private fun findGradeSmoothingChain(world: CoreWorld, controls: List<Anchor>): List<GradeChainRail>? {
        val positions = controls.map(Anchor::position)
        if (positions.size == 1) {
            val incident = ordinaryRailsAt(world, positions.single())
            if (incident.size != 2) return null
            return listOf(
                orientRailEndingAt(incident[0], positions.single()),
                orientRailStartingAt(incident[1], positions.single())
            )
        }
        val internal = positions.zipWithNext().map { (from, to) ->
            val matches = world.allRails.filter { rail -> connectsPositions(rail, from, to) && isOrdinaryRail(rail) }
            if (matches.size != 1) return null
            orientRail(matches.single(), from)
        }.toMutableList()
        val firstExtra = ordinaryRailsAt(world, positions.first()).filter { candidate ->
            candidate.id != internal.first().rail.id
        }
        if (firstExtra.size == 1) internal.add(0, orientRailEndingAt(firstExtra.single(), positions.first()))
        else if (firstExtra.size > 1) return null
        val lastExtra = ordinaryRailsAt(world, positions.last()).filter { candidate ->
            candidate.id != internal.last().rail.id
        }
        if (lastExtra.size == 1) internal += orientRailStartingAt(lastExtra.single(), positions.last())
        else if (lastExtra.size > 1) return null
        return internal
    }

    private fun ordinaryRailsAt(world: CoreWorld, position: Vector3dc): List<CoreRail> = world.allRails.filter { rail ->
        isOrdinaryRail(rail) && (
            rail.viewAB.startPoint.position.distance(position) <= Curve.TOLERANCE ||
                rail.viewAB.endPoint.position.distance(position) <= Curve.TOLERANCE
            )
    }

    private fun isOrdinaryRail(rail: CoreRail): Boolean =
        !TurnoutTopology.hasTurnoutEndpoint(rail)

    private fun connectsPositions(rail: CoreRail, first: Vector3dc, second: Vector3dc): Boolean {
        val a = rail.viewAB.startPoint.position
        val b = rail.viewAB.endPoint.position
        return (a.distance(first) <= Curve.TOLERANCE && b.distance(second) <= Curve.TOLERANCE) ||
            (a.distance(second) <= Curve.TOLERANCE && b.distance(first) <= Curve.TOLERANCE)
    }

    private fun orientRail(rail: CoreRail, start: Vector3dc): GradeChainRail {
        val asAb = rail.viewAB.startPoint.position.distance(start) <= Curve.TOLERANCE
        return GradeChainRail(rail, if (asAb) rail.curve else rail.curve.reversed, asAb)
    }

    private fun orientRailEndingAt(rail: CoreRail, end: Vector3dc): GradeChainRail {
        val asAb = rail.viewAB.endPoint.position.distance(end) <= Curve.TOLERANCE
        return GradeChainRail(rail, if (asAb) rail.curve else rail.curve.reversed, asAb)
    }

    private fun orientRailStartingAt(rail: CoreRail, start: Vector3dc): GradeChainRail = orientRail(rail, start)

    private fun commitGradeSmoothing(
        player: Player,
        oldRails: List<CoreRail>,
        rebuilt: Map<UUID, BuiltCurve>,
        selectedSnapshot: List<String>
    ) {
        val world = getCoreWorld(player) ?: return
        val allBefore = world.allRails.toList()
        val topologyBefore = allBefore.map(CoreRail::save)
        val renderBefore = RailRenderMetaStore.load(player.world)
        val railMetadataBefore = world.basicWorldData.railMetadata.snapshot()
        val renderByRail = renderBefore.associateBy { it.railId }
        val oldIds = oldRails.mapTo(linkedSetOf(), CoreRail::id)
        val created = mutableListOf<CoreRail>()
        val renderAfter = mutableListOf<PersistedRailRenderMeta>()
        val oldToNew = linkedMapOf<UUID, UUID>()
        val connectionSnapshots = allBefore.map(::snapshotRailConnections)
        try {
            oldRails.forEach { old ->
                val built = rebuilt.getValue(old.id)
                val replacementMetadata = old.metadata.copy(
                    speedLimitMetersPerSecond = built.speedLimits.operatingLimitMetersPerSecond,
                    derailSpeedMetersPerSecond = built.speedLimits.derailSpeedMetersPerSecond
                )
                val replacement = CoreRail(
                    world,
                    built.curve,
                    mutableListOf(),
                    mutableListOf()
                ).also { it.metadata = replacementMetadata }
                created += replacement
                oldToNew[old.id] = replacement.id
                renderByRail[old.id]?.let { renderAfter += registerRailWithTemplate(replacement, it.templateSnapshot) }
            }
            val unchanged = allBefore.filter { it.id !in oldIds }
            detachRailReferences(oldIds, unchanged)
            connectCreatedRailsToExisting(created, unchanged + created)
            RailRenderMetaStore.saveAll(player.world, renderBefore.filterNot { it.railId in oldIds } + renderAfter)
            OperationalDestinationService(world).migrateAllOnRails(oldToNew)
            oldRails.forEach { old ->
                RailVisualBootstrap.unregisterRail(old.id)
                old.destroy()
                world.removeRail(old)
                world.basicWorldData.railMetadata.remove(old.id)
            }
            pushUndo(player, WandAction.ReplaceRails(
                player.world.uid,
                created.mapTo(linkedSetOf(), CoreRail::id),
                topologyBefore.filter { UUID.fromString(it.id) in oldIds },
                topologyBefore,
                renderBefore,
                railMetadataBefore,
                oldToNew.entries.associate { (oldId, newId) -> newId to oldId }
            ))
            val state = getState(player)
            state.selectedAnchors.clear()
            state.selectedAnchors += selectedSnapshot.mapNotNull { id ->
                val parsed = parseRailEndpointAnchorId(id) ?: return@mapNotNull id
                val replacementId = oldToNew[parsed.first] ?: return@mapNotNull id
                railEndpointAnchorId(replacementId, parsed.second)
            }
            persistState(player, state)
            refreshAllAnchorsForPlayer(player)
            updateAnchorDisplay(player)
            player.sendActionBar(Component.text("已平滑 ${created.size} 段轨道纵断面", NamedTextColor.GREEN))
        } catch (failure: Exception) {
            created.forEach { rail ->
                RailVisualBootstrap.unregisterRail(rail.id)
                rail.destroy()
                world.removeRail(rail)
                world.basicWorldData.railMetadata.remove(rail.id)
            }
            restoreRailConnections(connectionSnapshots)
            RailRenderMetaStore.saveAll(player.world, renderBefore)
            player.sendMessage(Component.text("坡度平滑失败，已回滚：${railWandFailureReason(failure)}", NamedTextColor.RED))
        }
    }

    // ═══════════════════════════════════════════
    // 编辑模式
    // ═══════════════════════════════════════════

    private fun formatEditNumber(value: Double, digits: Int = 4): String =
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
        val selectedLogicalRails = logicalRails(player, rail)
        val currentSpeedKmH = selectedLogicalRails.firstNotNullOfOrNull {
            it.metadata.speedLimits.value(SpeedLimitType.SUBGRADE)
        }?.times(3.6) ?: state.designSpeedKmH
        val curveMetadata = selectedLogicalRails.firstOrNull {
            it.metadata.speedLimits.value(SpeedLimitType.CURVE) != null
        }?.metadata
        val currentCurveSpeedKmH = curveMetadata
            ?.takeIf { it.authoring?.curveSpeedLimitAutomatic == false }
            ?.speedLimits?.value(SpeedLimitType.CURVE)?.times(3.6) ?: 0.0
        val currentRadius = rail.metadata.geometry.curveRadiusMeters ?: 0.0
        val currentMaxSuperelevationMm = rail.metadata.geometry.maxSuperelevationMeters * 1000.0
        state.selectedAnchors.clear()
        state.selectedRails.clear()
        state.selectedRails.add(rail.id)
        state.designSpeedKmH = currentSpeedKmH
        state.curveSpeedLimitKmH = currentCurveSpeedKmH
        state.curveRadius = currentRadius
        state.maxSuperelevationMm = currentMaxSuperelevationMm
        updateAnchorDisplay(player)

        val session = configUI.getPlayerSession(player.uniqueId)
        session.setValue(railEditKeys.designSpeedKmH, currentSpeedKmH)
        session.setValue(railEditKeys.curveSpeedLimitKmH, currentCurveSpeedKmH)
        session.setValue(railEditKeys.curveRadius, currentRadius)
        session.setValue(railEditKeys.maxSuperelevationMm, currentMaxSuperelevationMm)
        dialogRenderer.showConfigDialog(player, "rail.edit") { p ->
            val liveWorld = getCoreWorld(p) ?: return@showConfigDialog
            val liveRail = liveWorld.getRail(rail.id) ?: run {
                p.sendActionBar(Component.text("轨道已不存在", NamedTextColor.RED))
                return@showConfigDialog
            }
            val values = configUI.getPlayerSession(p.uniqueId)
            val designSpeedKmH = values.getValue(railEditKeys.designSpeedKmH) ?: currentSpeedKmH
            val curveSpeedLimitKmH = values.getValue(railEditKeys.curveSpeedLimitKmH)
                ?: currentCurveSpeedKmH
            val curveRadius = values.getValue(railEditKeys.curveRadius) ?: currentRadius
            val maxSuperelevationMm = values.getValue(railEditKeys.maxSuperelevationMm)
                ?: currentMaxSuperelevationMm
            val liveState = getState(p)
            liveState.designSpeedKmH = designSpeedKmH
            liveState.curveSpeedLimitKmH = curveSpeedLimitKmH
            liveState.curveRadius = curveRadius
            liveState.maxSuperelevationMm = maxSuperelevationMm
            persistState(p, liveState)

            if (abs(curveRadius - currentRadius) < 1.0e-6 &&
                abs(designSpeedKmH - currentSpeedKmH) < 1.0e-6 &&
                abs(curveSpeedLimitKmH - currentCurveSpeedKmH) < 1.0e-6 &&
                abs(maxSuperelevationMm - currentMaxSuperelevationMm) < 1.0e-6
            ) {
                p.sendActionBar(Component.text("轨道参数未变化", NamedTextColor.GRAY))
            } else {
                rebuildRailGeometry(
                    p,
                    liveRail,
                    designSpeedKmH,
                    curveSpeedLimitKmH,
                    curveRadius,
                    maxSuperelevationMm
                )
            }
        }
    }

    internal fun logicalRails(player: Player, rail: CoreRail): List<CoreRail> =
        getCoreWorld(player)?.let { logicalRails(it, rail) } ?: listOf(rail)

    internal fun logicalRailSelectionId(player: Player, railId: UUID): UUID {
        val world = getCoreWorld(player) ?: return railId
        val rail = world.getRail(railId) ?: return railId
        return logicalRails(world, rail).first().id
    }

    internal fun logicalRails(world: CoreWorld, rail: CoreRail): List<CoreRail> {
        val assembly = rail.metadata.assembly ?: return listOf(rail)
        return world.allRails
            .filter { it.metadata.assembly?.groupId == assembly.groupId }
            .sortedWith(compareBy({ it.metadata.assembly!!.laneIndex }, { it.metadata.assembly!!.segmentIndex }))
    }

    private fun railAssemblyLanes(world: CoreWorld, rail: CoreRail): List<List<CoreRail>> {
        val assembly = rail.metadata.assembly
        if (assembly != null) {
            val lanes = logicalRails(world, rail).groupBy { it.metadata.assembly!!.laneIndex }
            if (lanes.size != assembly.laneCount) return emptyList()
            return (0 until assembly.laneCount).map { laneIndex ->
                lanes[laneIndex].orEmpty().sortedBy { it.metadata.assembly!!.segmentIndex }
                    .takeIf { it.size == assembly.segmentCount } ?: return emptyList()
            }
        }
        val parallel = rail.metadata.geometry.parallel ?: return listOf(listOf(rail))
        val rails = world.allRails
            .filter { it.metadata.geometry.parallel?.groupId == parallel.groupId }
            .sortedBy { it.metadata.geometry.parallel!!.index }
        return if (rails.size == parallel.count) rails.map(::listOf) else emptyList()
    }

    private fun rebuildRailGeometry(
        player: Player,
        selectedRail: CoreRail,
        designSpeedKmH: Double,
        curveSpeedLimitKmH: Double,
        curveRadius: Double,
        maxSuperelevationMm: Double
    ) {
        val coreWorld = getCoreWorld(player) ?: return
        val oldLanes = railAssemblyLanes(coreWorld, selectedRail)
        val oldRails = oldLanes.flatten()
        if (oldRails.isEmpty()) {
            player.sendActionBar(Component.text("逻辑曲线或并行线路信息不完整，无法重建", NamedTextColor.RED))
            return
        }
        if (oldRails.any { it.trains.isNotEmpty() }) {
            player.sendActionBar(Component.text("不能重建有列车占用的轨道", NamedTextColor.RED))
            return
        }
        if (oldRails.any(TurnoutTopology::hasTurnoutEndpoint)) {
            player.sendActionBar(Component.text("逻辑线路包含道岔节点，不能修改几何", NamedTextColor.RED))
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
            parallelCount = oldLanes.size,
            parallelSpacing = parallel?.spacingMeters ?: 4.5,
            curveRadius = curveRadius,
            curveTemplate = template,
            designSpeedKmH = designSpeedKmH,
            curveSpeedLimitKmH = curveSpeedLimitKmH,
            allowableCantDeficiencyMm = geometry.allowableCantDeficiencyMeters * 1000.0,
            automaticSuperelevation = geometry.automaticSuperelevation,
            maxSuperelevationMm = maxSuperelevationMm
        )
        val starts = oldLanes.map { anchorFromRailEndpoint(player.world, it.first(), 'A') }
        val ends = oldLanes.map { anchorFromRailEndpoint(player.world, it.last(), 'B') }
        val oldIds = oldRails.mapTo(linkedSetOf()) { it.id }
        val selectedLane = selectedRail.metadata.assembly?.laneIndex
            ?: selectedRail.metadata.geometry.parallel?.index ?: 0
        val selectedRole = selectedRail.metadata.assembly?.role ?: RailAssemblyRole.CURVE
        val worldId = player.world.uid
        val pendingOperation = heavyOperationGate.tryStart(player.uniqueId, "轨道曲率重建")
        if (pendingOperation != null) {
            player.sendActionBar(Component.text("正在执行$pendingOperation，请稍候", NamedTextColor.YELLOW))
            return
        }
        player.sendActionBar(Component.text("正在重建轨道曲线...", NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.rebuildRailGeometry",
            supplier = {
                createCurvesWithSpeedLimits(starts, ends, settings)?.map { built ->
                    partitionBuiltCurve(built, settings)
                }
            },
            onSuccess = { builtLanes ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                if (builtLanes == null || builtLanes.size != oldLanes.size ||
                    builtLanes.map { it.size }.distinct().size != 1
                ) {
                    livePlayer.sendActionBar(Component.text("无法按新参数生成逻辑曲线", NamedTextColor.RED))
                    return@supply
                }
                val liveOldRails = oldRails.map { old -> liveWorld.getRail(old.id) ?: return@supply }
                if (liveOldRails.any { it.trains.isNotEmpty() }) {
                    livePlayer.sendActionBar(Component.text("不能重建有列车占用的轨道", NamedTextColor.RED))
                    return@supply
                }

                val persisted = RailRenderMetaStore.load(livePlayer.world)
                val renderMetaByRail = persisted.associateBy { it.railId }
                val allRails = liveWorld.allRails.toList()
                val connectionSnapshots = allRails.map(::snapshotRailConnections)
                val created = mutableListOf<CoreRail>()
                val createdRenderMeta = mutableListOf<PersistedRailRenderMeta>()
                val createdLanes = mutableListOf<List<CoreRail>>()
                val oldAssemblyId = selectedRail.metadata.assembly?.groupId
                val segmentCount = builtLanes.first().size
                val assemblyId = if (segmentCount > 1) oldAssemblyId ?: UUID.randomUUID().toString() else null
                val parallelIds = List(segmentCount) { UUID.randomUUID().toString().takeIf { oldLanes.size > 1 } }
                liveWorld.railTopologyTransaction {
                try {
                    builtLanes.forEachIndexed { laneIndex, parts ->
                        val oldLane = oldLanes[laneIndex]
                        val newLane = parts.mapIndexed { segmentIndex, part ->
                            val oldForRole = oldLane.firstOrNull { it.metadata.assembly?.role == part.role }
                                ?: oldLane[minOf(segmentIndex, oldLane.lastIndex)]
                            val parallelMeta = parallelIds[segmentIndex]?.let {
                                ParallelRailMetadata(it, laneIndex, oldLanes.size, settings.parallelSpacing)
                            }
                            val assemblyMeta = assemblyId?.let {
                                RailAssemblyMetadata(
                                    it,
                                    RailAssemblyKind.FIVE_SEGMENT_CURVE,
                                    laneIndex,
                                    oldLanes.size,
                                    segmentIndex,
                                    segmentCount,
                                    part.role
                                )
                            }
                            val rebuiltMetadata = railMetadata(
                                settings,
                                part.speedLimits,
                                parallelMeta,
                                oldForRole.metadata.allowedDirection
                            )
                            val metadata = rebuiltMetadata.copy(
                                authoring = requireNotNull(rebuiltMetadata.authoring).copy(
                                    assembly = assemblyMeta,
                                    createdBySplit = oldForRole.metadata.createdBySplit,
                                    gradeLockedAtA = oldLane.first().metadata.gradeLockedAtA && segmentIndex == 0,
                                    gradeLockedAtB = oldLane.last().metadata.gradeLockedAtB && segmentIndex == segmentCount - 1
                                ),
                                operating = rebuiltMetadata.operating.copy(
                                    turnoutAtA = oldLane.first().metadata.turnoutAtA.takeIf { segmentIndex == 0 },
                                    turnoutAtB = oldLane.last().metadata.turnoutAtB.takeIf { segmentIndex == segmentCount - 1 }
                                )
                            )
                            CoreRail(liveWorld, part.curve, mutableListOf(), mutableListOf()).also {
                                it.metadata = metadata
                                created += it
                            }
                        }
                        createdLanes += newLane
                    }
                    detachRailReferences(oldIds, allRails.filter { it.id !in oldIds })
                    connectCreatedRailsToExisting(created, allRails.filter { it.id !in oldIds } + created)
                    val oldToNew = oldLanes.flatMapIndexed { laneIndex, oldLane ->
                        oldLane.mapIndexed { oldIndex, old ->
                            val replacement = createdLanes[laneIndex].firstOrNull {
                                it.metadata.assembly?.role == old.metadata.assembly?.role
                            } ?: createdLanes[laneIndex][minOf(oldIndex, createdLanes[laneIndex].lastIndex)]
                            old.id to replacement.id
                        }
                    }.toMap()
                    OperationalDestinationService(liveWorld).migrateAllOnRails(oldToNew)
                    liveOldRails.forEach { old ->
                        RailVisualBootstrap.unregisterRail(old.id)
                        old.destroy()
                        liveWorld.removeRail(old)
                        liveWorld.basicWorldData.railMetadata.remove(old.id)
                    }
                    createdLanes.forEachIndexed { laneIndex, lane ->
                        val oldTemplate = oldLanes[laneIndex].firstNotNullOfOrNull {
                            renderMetaByRail[it.id]?.templateSnapshot
                        }
                        if (oldTemplate != null) lane.forEach { newRail ->
                            createdRenderMeta += registerRailWithTemplate(newRail, oldTemplate)
                        }
                    }
                    RailRenderMetaStore.saveAll(
                        livePlayer.world,
                        persisted.filterNot { it.railId in oldIds } + createdRenderMeta
                    )

                    val replacementLane = createdLanes[selectedLane.coerceIn(createdLanes.indices)]
                    val replacement = replacementLane.firstOrNull { it.metadata.assembly?.role == selectedRole }
                        ?: replacementLane.first()
                    val state = getState(livePlayer)
                    state.selectedRails.clear()
                    state.selectedRails.add(replacement.id)
                    persistState(livePlayer, state)
                    refreshAllAnchorsForPlayer(livePlayer)
                    updateAnchorDisplay(livePlayer)
                    livePlayer.sendActionBar(Component.text("轨道曲线参数已更新", NamedTextColor.GREEN))
                } catch (error: Exception) {
                    created.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        rail.destroy()
                        liveWorld.removeRail(rail)
                        liveWorld.basicWorldData.railMetadata.remove(rail.id)
                    }
                    restoreRailConnections(connectionSnapshots)
                    RailRenderMetaStore.saveAll(livePlayer.world, persisted)
                    persisted.forEach { RailVisualBootstrap.fromPersisted(liveWorld, it) }
                    livePlayer.sendMessage(Component.text("轨道重建失败，已回滚：${railWandFailureReason(error)}", NamedTextColor.RED))
                }
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail radius rebuild failed: ${error.message ?: error::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.sendMessage(
                    Component.text("轨道重建失败：${railWandFailureReason(error)}", NamedTextColor.RED)
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
        val selectedLogicalRails = logicalRails(getCoreWorld(player) ?: rail.world, rail)
        val subgradeLabel = selectedLogicalRails.firstNotNullOfOrNull {
            it.metadata.speedLimits.value(SpeedLimitType.SUBGRADE)
        }?.times(3.6)?.let { "%.0f km/h".format(it) } ?: "未设置"
        val curveLabel = selectedLogicalRails.firstNotNullOfOrNull {
            it.metadata.speedLimits.value(SpeedLimitType.CURVE)
        }?.times(3.6)?.let { "%.0f km/h".format(it) } ?: "无"
        val derailLabel = selectedLogicalRails.mapNotNull { it.metadata.derailSpeedMetersPerSecond }
            .minOrNull()?.times(3.6)?.let { "%.0f km/h".format(it) } ?: "∞"
        val radiusLabel = rail.metadata.geometry.curveRadiusMeters?.let { "%.0f m".format(it) } ?: "自动"
        val parallelLabel = rail.metadata.geometry.parallel?.let { "并行 ${it.index + 1}/${it.count}" } ?: "单线"
        player.sendActionBar(
            Component.text(
                "已选择轨道 ${rail.id}，SUBGRADE $subgradeLabel，CURVE $curveLabel，脱轨 $derailLabel，半径 $radiusLabel，$parallelLabel",
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
        session.setValue(editKeys.tangentYaw, formatEditNumber(yaw))
        session.setValue(editKeys.tangentPitch, formatEditNumber(pitch))
        session.setValue(editKeys.roll, formatEditNumber(roll))
        session.setValue(editKeys.gradeLocked, isGradeLocked(anchor))
        dialogRenderer.showConfigDialog(player, "anchor.edit") { p ->
            // 确认回调: 读取新值并更新锚点
            val s = configUI.getPlayerSession(p.uniqueId)
            val x = parseEditDouble(s.getValue(editKeys.posX), anchor.position.x(), "X 坐标", p) ?: return@showConfigDialog
            val y = parseEditDouble(s.getValue(editKeys.posY), anchor.position.y(), "Y 坐标", p) ?: return@showConfigDialog
            val z = parseEditDouble(s.getValue(editKeys.posZ), anchor.position.z(), "Z 坐标", p) ?: return@showConfigDialog
            val newYaw = parseEditDouble(s.getValue(editKeys.tangentYaw), yaw, "Yaw", p) ?: return@showConfigDialog
            val newPitch = parseEditDouble(s.getValue(editKeys.tangentPitch), pitch, "Pitch", p) ?: return@showConfigDialog
            val newRoll = parseEditDouble(s.getValue(editKeys.roll), roll, "Roll", p) ?: return@showConfigDialog
            val gradeLocked = s.getValue(editKeys.gradeLocked) ?: isGradeLocked(anchor)
            val newPos = Vector3d(x, y, z)
            val newTan = AnchorGeometry.tangentFromYawPitch(newYaw, newPitch)
            val geometryUpdated = AnchorGeometry.withRoll(anchor.withPosition(newPos).withTangent(newTan), newRoll)
            val updatedMetadata = geometryUpdated.metadata.toMutableMap().apply {
                if (gradeLocked) put(GRADE_LOCKED_METADATA_KEY, true.toString())
                else remove(GRADE_LOCKED_METADATA_KEY)
            }
            val updated = geometryUpdated.copy(metadata = updatedMetadata)
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
        splitPreviewViewers.forEach { playerId ->
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                splitPreviewViewers.remove(playerId)
            } else {
                plugin.platformDispatcher.runForEntity(player, action = { updateSplitPreview(player) })
            }
        }
    }

    private fun updateSplitPreview(player: Player) {
        if (!isWandItem(player.inventory.itemInMainHand)) {
            splitPreviewViewers.remove(player.uniqueId)
            return
        }
        val state = getState(player)
        if (state.mode != WandMode.SPLIT) {
            splitPreviewViewers.remove(player.uniqueId)
            splitPreviewTargets.remove(player.uniqueId)
            return
        }

        val coreWorld = getCoreWorld(player) ?: return
        val pendingRail = state.pendingSplitRail?.let(coreWorld::getRail)
        val pendingArc = state.pendingSplitArcLength
        if (pendingRail != null && pendingArc != null && pendingArc in 0.0..pendingRail.curve.totalArcLength) {
            val point = pendingRail.curve.getPointAt(pendingArc)
            spawnSplitParticle(player, point.position, Color.LIME, 1.25f)
        }
        requestSplitPreview(player)
    }

    private fun updateDecorationPreviews() {
        decorationPreviewViewers.forEach { playerId ->
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                decorationPreviewViewers.remove(playerId)
            } else {
                plugin.platformDispatcher.runForEntity(player, action = { updateDecorationPreview(player) })
            }
        }
    }

    private fun updateDecorationPreview(player: Player) {
        if (!isWandItem(player.inventory.itemInMainHand)) {
            decorationPreviewViewers.remove(player.uniqueId)
            return
        }
        val state = getState(player)
        if (state.mode != WandMode.DECORATE) {
            decorationPreviewViewers.remove(player.uniqueId)
            return
        }
        val coreWorld = getCoreWorld(player) ?: return
        val decoratedRailIds = RailRenderMetaStore.decoratedRailIds(player.world)
        val pendingRailId = state.pendingDecorationDeleteRail
        val playerPosition = Vector3d(player.location.x, player.location.y, player.location.z)
        val rails = coreWorld.getRailsNear(playerPosition, 64.0)
            .asSequence()
            .filter { it.id !in decoratedRailIds || it.id == pendingRailId }
            .sortedWith(
                compareBy<CoreRail> { it.id != pendingRailId }
                    .thenBy { it.curve.getPointAt(it.curve.totalArcLength / 2.0).position.distanceSquared(playerPosition) }
            )
            .toList()

        var remainingParticles = 320
        for (rail in rails) {
            if (remainingParticles <= 0) break
            val pendingDelete = rail.id == pendingRailId
            val spacing = if (pendingDelete) 0.75 else 2.5
            val color = if (pendingDelete) Color.RED else Color.fromRGB(255, 190, 0)
            val size = if (pendingDelete) 1.0f else 0.65f
            for (arc in RailDecorationLayout.endpointInclusiveArcs(rail.curve.totalArcLength, spacing)) {
                if (remainingParticles-- <= 0) break
                val point = rail.curve.getPointAt(arc)
                spawnRailParticle(player, point.position, color, size)
            }
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
        val candidates = coreWorld.getRailsAlongRay(
            origin,
            direction,
            HeavyRailQueries.DEFAULT_TARGET_MAX_DISTANCE,
            HeavyRailQueries.DEFAULT_TARGET_HIT_RADIUS
        ).toList()
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.updateSplitPreview",
            supplier = {
                HeavyRailQueries.findLookedAtRailPoint(
                    candidates,
                    origin,
                    direction,
                    HeavyRailQueries.DEFAULT_TARGET_MAX_DISTANCE,
                    HeavyRailQueries.DEFAULT_TARGET_HIT_RADIUS
                )
            },
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
        spawnRailParticle(player, position, color, size)
    }

    private fun spawnRailParticle(player: Player, position: Vector3dc, color: Color, size: Float) {
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
        val coreWorld = getCoreWorld(player)
        val rail = coreWorld?.getRail(target.railId)
        if (rail == null || target.arcLength <= Curve.TOLERANCE ||
            target.arcLength >= rail.curve.totalArcLength - Curve.TOLERANCE
        ) {
            player.sendActionBar(Component.text("分割点不能位于轨道端点", NamedTextColor.RED))
            return
        }
        splitPointSafetyError(coreWorld, rail, target.arcLength)?.let { error ->
            player.sendActionBar(Component.text(error, NamedTextColor.RED))
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
        splitPointSafetyError(coreWorld, rail, arcLength)?.let { error ->
            player.sendActionBar(Component.text(error, NamedTextColor.RED))
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
            completionEntity = player,
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
                splitPointSafetyError(liveWorld, liveRail, arcLength)?.let { error ->
                    livePlayer.sendActionBar(Component.text(error, NamedTextColor.RED))
                    return@supply
                }

                val renderMetas = RailRenderMetaStore.load(livePlayer.world)
                val renderMetaByRail = renderMetas.associateBy { it.railId }
                val originalRenderMeta = renderMetaByRail[railId]
                val railMetadataBefore = liveWorld.basicWorldData.railMetadata.snapshot()
                try {
                    val result = RailTopologyEditor.splitWithPreparedCurves(
                        liveRail,
                        curves.first,
                        curves.second,
                        signalCanBeRebuilt = { it is OperationalSpeedLimitSignal }
                    )
                    RailInfrastructureMetadataTopology.split(
                        liveWorld.basicWorldData.railMetadata,
                        railId,
                        result.before.id,
                        result.after.id,
                        result.operationId
                    )
                    OperationalSpeedLimitSignalMaterializer.materializeRail(result.before)
                    OperationalSpeedLimitSignalMaterializer.materializeRail(result.after)
                    OperationalDestinationService(liveWorld).migrateAfterRailSplit(
                        oldRailId = railId,
                        beforeRailId = result.before.id,
                        afterRailId = result.after.id,
                        splitArcLengthInAbMeters = arcLength
                    )
                    val railMetadataAfter = liveWorld.basicWorldData.railMetadata.snapshot()
                    val changedExistingIds = railMetadataBefore.keys.intersect(railMetadataAfter.keys)
                        .filterTo(linkedSetOf()) { railMetadataBefore[it] != railMetadataAfter[it] }
                    RailVisualBootstrap.unregisterRail(railId)
                    val replacementMetas = buildList {
                        originalRenderMeta?.let { oldMeta ->
                            add(registerRailWithTemplate(result.before, oldMeta.templateSnapshot))
                            add(registerRailWithTemplate(result.after, oldMeta.templateSnapshot))
                        }
                        changedExistingIds.forEach { changedId ->
                            val changedRail = liveWorld.getRail(changedId) ?: return@forEach
                            val oldMeta = renderMetaByRail[changedId] ?: return@forEach
                            RailVisualBootstrap.unregisterRail(changedId)
                            add(registerRailWithTemplate(changedRail, oldMeta.templateSnapshot))
                        }
                    }
                    RailRenderMetaStore.saveAll(
                        livePlayer.world,
                        renderMetas.filterNot { it.railId == railId || it.railId in changedExistingIds } +
                            replacementMetas
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
                    livePlayer.sendMessage(Component.text("轨道分割失败：${railWandFailureReason(error)}", NamedTextColor.RED))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail curve split failed: ${error::class.simpleName}: ${error.message ?: ""}")
                plugin.server.getPlayer(player.uniqueId)?.sendMessage(
                    Component.text("轨道曲线分割失败：${railWandFailureReason(error)}", NamedTextColor.RED)
                )
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
        RailTopologyEditor.mergeEligibilityError(
            before,
            after,
            signalCanBeRebuilt = { it is OperationalSpeedLimitSignal }
        )?.let { error ->
            player.sendActionBar(Component.text(error, NamedTextColor.RED))
            return
        }
        RailInfrastructureMetadataTopology.mergeEligibilityError(
            coreWorld.basicWorldData.railMetadata,
            before.id,
            after.id
        )?.let { error ->
            player.sendActionBar(Component.text(error, NamedTextColor.RED))
            return
        }
        splitSafetyError(coreWorld, listOf(before, after))?.let { error ->
            player.sendActionBar(Component.text(error, NamedTextColor.RED))
            return
        }
        val renderMetas = RailRenderMetaStore.load(player.world)
        val renderMetaByRail = renderMetas.associateBy { it.railId }
        val beforeRender = renderMetaByRail[before.id]
        val afterRender = renderMetaByRail[after.id]
        if ((beforeRender == null) != (afterRender == null) ||
            beforeRender?.templateSnapshot != afterRender?.templateSnapshot
        ) {
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
            completionEntity = player,
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
                RailTopologyEditor.mergeEligibilityError(
                    livePair.first,
                    livePair.second,
                    signalCanBeRebuilt = { it is OperationalSpeedLimitSignal }
                )?.let { error ->
                    livePlayer.sendActionBar(Component.text(error, NamedTextColor.RED))
                    return@supply
                }
                RailInfrastructureMetadataTopology.mergeEligibilityError(
                    liveWorld.basicWorldData.railMetadata,
                    livePair.first.id,
                    livePair.second.id
                )?.let { error ->
                    livePlayer.sendActionBar(Component.text(error, NamedTextColor.RED))
                    return@supply
                }
                splitSafetyError(liveWorld, livePair.toList())?.let { error ->
                    livePlayer.sendActionBar(Component.text(error, NamedTextColor.RED))
                    return@supply
                }

                try {
                    val beforeRailId = livePair.first.id
                    val afterRailId = livePair.second.id
                    val beforeLengthMeters = livePair.first.curve.totalArcLength
                    val railMetadataBefore = liveWorld.basicWorldData.railMetadata.snapshot()
                    val merged = RailTopologyEditor.mergeWithPreparedCurve(
                        livePair.first,
                        livePair.second,
                        mergedCurve,
                        signalCanBeRebuilt = { it is OperationalSpeedLimitSignal }
                    )
                    RailInfrastructureMetadataTopology.merge(
                        liveWorld.basicWorldData.railMetadata,
                        beforeRailId,
                        afterRailId,
                        merged.id
                    )
                    OperationalSpeedLimitSignalMaterializer.materializeRail(merged)
                    OperationalDestinationService(liveWorld).migrateAfterRailMerge(
                        beforeRailId = beforeRailId,
                        afterRailId = afterRailId,
                        mergedRailId = merged.id,
                        beforeLengthMeters = beforeLengthMeters
                    )
                    val railMetadataAfter = liveWorld.basicWorldData.railMetadata.snapshot()
                    val changedExistingIds = railMetadataBefore.keys.intersect(railMetadataAfter.keys)
                        .filterTo(linkedSetOf()) { railMetadataBefore[it] != railMetadataAfter[it] }
                    RailVisualBootstrap.unregisterRail(livePair.first.id)
                    RailVisualBootstrap.unregisterRail(livePair.second.id)
                    val replacementMetas = buildList {
                        beforeRender?.let { oldMeta ->
                            add(registerRailWithTemplate(merged, oldMeta.templateSnapshot))
                        }
                        changedExistingIds.forEach { changedId ->
                            val changedRail = liveWorld.getRail(changedId) ?: return@forEach
                            val oldMeta = renderMetaByRail[changedId] ?: return@forEach
                            RailVisualBootstrap.unregisterRail(changedId)
                            add(registerRailWithTemplate(changedRail, oldMeta.templateSnapshot))
                        }
                    }
                    RailRenderMetaStore.saveAll(
                        livePlayer.world,
                        renderMetas.filterNot {
                            it.railId == livePair.first.id || it.railId == livePair.second.id ||
                                it.railId in changedExistingIds
                        } + replacementMetas
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
                    livePlayer.sendMessage(Component.text("轨道合并失败：${railWandFailureReason(error)}", NamedTextColor.RED))
                }
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail curve merge failed: ${error::class.simpleName}: ${error.message ?: ""}")
                plugin.server.getPlayer(player.uniqueId)?.sendMessage(
                    Component.text("轨道曲线合并失败：${railWandFailureReason(error)}", NamedTextColor.RED)
                )
            }
        )
    }

    private fun splitSafetyError(coreWorld: CoreWorld, rails: List<CoreRail>): String? {
        val railIds = rails.mapTo(hashSetOf()) { it.id }
        if (coreWorld.occupyingTrainIds(railIds).isNotEmpty()) return "不能操作有列车占用的轨道"
        if (rails.any { rail -> rail.signals.any { it !is OperationalSpeedLimitSignal } }) {
            return "轨道包含需要玩家先处理的信号机"
        }
        val basicData = coreWorld.basicWorldDataOrNull ?: return null
        if (basicData.dispatcher.hasRouteAllocationOn(railIds)) return "不能操作有活动进路锁的轨道"
        val migratableRailObjectRefs = basicData.destinations.all().asSequence()
            .filter { it.railId in railIds }
            .mapNotNull { (it.role as? DestinationRole.StationBerthRole)?.berthRef?.railObjectRef }
            .toSet()
        if (railIds.any { railId ->
                basicData.railObjects.objectsOnRail(railId).any { it.ref !in migratableRailObjectRefs }
            }
        ) return "轨道包含不支持迁移的运营对象"
        return null
    }

    private fun splitPointSafetyError(coreWorld: CoreWorld, rail: CoreRail, arcLength: Double): String? {
        if (rail.metadata.assembly != null && railAssemblyLanes(coreWorld, rail).isEmpty()) {
            return "逻辑曲线或并行线路信息不完整，不能分割"
        }
        val basicData = coreWorld.basicWorldDataOrNull
        if (basicData?.wayPoints?.all()?.any {
                it.railId == rail.id && abs(it.arcLength - arcLength) < SPLIT_OBJECT_CLEARANCE_METERS
            } == true
        ) return "分割点距离路径点或运营目的地不足 ${SPLIT_OBJECT_CLEARANCE_METERS.toInt()}m"
        if (rail.signals.any { signal ->
                signal !is OperationalSpeedLimitSignal &&
                    abs(signal.location.arcLengthIn(ViewDirection.AB) - arcLength) < SPLIT_OBJECT_CLEARANCE_METERS
            }
        ) return "分割点距离信号机不足 ${SPLIT_OBJECT_CLEARANCE_METERS.toInt()}m"
        val lockingDistance = basicData?.turnoutSafetyDefaults?.lockingDistanceMeters
            ?: plugin.physicsConfig.turnoutSafety.defaultLockingDistanceMeters
        return TurnoutTopology.splitLockingClearanceError(rail, arcLength, lockingDistance)
    }

    private fun registerRailWithTemplate(
        rail: CoreRail,
        templateSnapshot: work.art1st.javarailways.bukkit.extension.rail.RailTemplateSnapshot?
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
            player.sendActionBar(Component.text("错误: 无法获取 JavaRailways 世界", NamedTextColor.RED))
            return
        }
        val rail = coreWorld.getRail(railId) ?: run {
            RailVisualBootstrap.unregisterRail(railId)
            RailRenderMetaStore.removeRail(player.world, railId)
            coreWorld.basicWorldData.railMetadata.remove(railId)
            refreshAllAnchorsForPlayer(player)
            player.sendActionBar(Component.text("轨道不存在，已清理残留渲染数据: $railId", NamedTextColor.YELLOW))
            return
        }
        val targetRails = logicalRails(coreWorld, rail)
        if (targetRails.any { it.trains.isNotEmpty() }) {
            player.sendActionBar(Component.text("不能删除有列车占用的逻辑曲线", NamedTextColor.RED))
            return
        }

        val targetIds = targetRails.mapTo(linkedSetOf()) { it.id }
        val allRails = coreWorld.allRails.toList()
        var removedDestinations = 0
        coreWorld.railTopologyTransaction {
            detachRailReferences(targetIds, allRails.filter { it.id !in targetIds })
            val destinations = OperationalDestinationService(coreWorld)
            removedDestinations = targetIds.sumOf { destinations.removeAllOnRail(it).size }
            val renderAfter = RailRenderMetaStore.load(player.world).filterNot { it.railId in targetIds }
            targetRails.forEach { target ->
                RailVisualBootstrap.unregisterRail(target.id)
                target.destroy()
                coreWorld.removeRail(target)
                coreWorld.basicWorldData.railMetadata.remove(target.id)
            }
            RailRenderMetaStore.saveAll(player.world, renderAfter)
        }

        state.selectedRails.removeAll(targetIds)
        state.selectedAnchors.removeIf { railIdFromEndpointAnchor(it) in targetIds }
        targetIds.forEach { removedId ->
            removeAnchorDisplay(player, railEndpointAnchorId(removedId, 'A'))
            removeAnchorDisplay(player, railEndpointAnchorId(removedId, 'B'))
        }
        refreshAllAnchorsForPlayer(player)
        pushUndo(player, WandAction.DeleteRail(player.world.uid, railId))
        player.sendActionBar(Component.text(
            "已删除逻辑曲线（${targetRails.size} 个内部段）" +
                if (removedDestinations > 0) "（同步删除 $removedDestinations 个 destination）" else "",
            NamedTextColor.GREEN
        ))
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
            completionEntity = player,
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
                livePlayer.sendMessage(Component.text("轨道查找失败：${railWandFailureReason(error)}", NamedTextColor.RED))
                onResolved(null)
            }
        )
    }

    // ═══════════════════════════════════════════
    // 辅助方法 — 临时锚点存储/查找
    // ═══════════════════════════════════════════

    private fun anchorsFor(world: World): MutableMap<String, Anchor> =
        transientAnchors.computeIfAbsent(world.uid) { ConcurrentHashMap() }

    private fun addAnchor(world: World, anchor: Anchor) {
        anchorsFor(world)[anchor.id] = anchor
    }

    private fun getAnchor(world: World, id: String): Anchor? =
        anchorsFor(world)[id] ?: getRailEndpointAnchor(world, id) ?: getSplitAnchor(world, id)

    private fun getAllAnchors(world: World): List<Anchor> =
        anchorsFor(world).values.toList() + getRailEndpointAnchors(world) + getSplitAnchors(world)

    private fun isAnchorEmpty(world: World, anchor: Anchor): Boolean {
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[world] as? CoreWorld ?: return true
        return coreWorld.allRails.none { rail ->
            rail.viewAB.startPoint.position.distance(anchor.position) <= Curve.TOLERANCE ||
                rail.viewAB.endPoint.position.distance(anchor.position) <= Curve.TOLERANCE
        }
    }

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
                worldId = world.uid.toString(),
                metadata = railEndpointMetadata(rail, end)
            ),
            AnchorGeometry.rollDegreesFromNormal(point.tangent, point.normal)
        )
    }

    private fun railEndpointMetadata(rail: CoreRail, end: Char): Map<String, String> = buildMap {
        if (if (end == 'A') rail.metadata.gradeLockedAtA else rail.metadata.gradeLockedAtB) {
            put(GRADE_LOCKED_METADATA_KEY, true.toString())
        }
    }

    private fun isGradeLocked(anchor: Anchor): Boolean =
        anchor.metadata[GRADE_LOCKED_METADATA_KEY].toBoolean()

    private fun getRailEndpointAnchors(world: World): List<Anchor> {
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[world] as? CoreWorld ?: return emptyList()
        return RailRenderMetaStore.load(world).flatMap { meta ->
            val rail = coreWorld.getRail(meta.railId) ?: return@flatMap emptyList()
            val split = rail.metadata.createdBySplit
            val assembly = rail.metadata.assembly
            val hasSiblingPair = split != null && findSplitPair(coreWorld, split.operationId) != null
            listOfNotNull(
                getRailEndpointAnchor(world, railEndpointAnchorId(rail.id, 'A'))
                    .takeUnless {
                        (hasSiblingPair && split.segmentIndex == 1) ||
                            (assembly != null && assembly.segmentIndex > 0)
                    },
                getRailEndpointAnchor(world, railEndpointAnchorId(rail.id, 'B'))
                    .takeUnless {
                        (hasSiblingPair && split.segmentIndex == 0) ||
                            (assembly != null && assembly.segmentIndex < assembly.segmentCount - 1)
                    }
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

    private fun anchorDisplayLabel(player: Player, anchor: Anchor): String? {
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[player.world] as? CoreWorld
        val endpointCount = coreWorld?.allRails?.sumOf { rail ->
            listOf(rail.viewAB.startPoint, rail.viewAB.endPoint).count {
                it.position.distance(anchor.position) <= Curve.TOLERANCE
            }
        } ?: 0
        val turnout = endpointCount >= 3
        return buildString {
            if (turnout) append("🔀")
            if (isGradeLocked(anchor)) append("🔒")
        }.ifEmpty { null }
    }

    private fun spawnAnchorDisplay(player: Player, anchor: Anchor) {
        val entities = anchorEntities
            .computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
            .computeIfAbsent(anchor.id) { ConcurrentHashMap.newKeySet() }

        spawnAnchorDisplayEntity(
            player = player,
            anchor = anchor,
            position = anchor.position,
            material = ANCHOR_DISPLAY_MATERIAL,
            scale = 1.1f,
            displayName = anchorDisplayLabel(player, anchor),
            entities = entities
        )

        val direction = Vector3d(anchor.tangent)
        if (direction.lengthSquared() > 1e-9) {
            direction.normalize()
            spawnAnchorDisplayEntity(
                player = player,
                anchor = anchor,
                position = Vector3d(anchor.position).fma(1.35, direction),
                material = ANCHOR_DIRECTION_MATERIAL,
                scale = 0.8f,
                displayName = null,
                entities = entities
            )
        }
    }

    private fun spawnAnchorDisplayEntity(
        player: Player,
        anchor: Anchor,
        position: Vector3dc,
        material: Material,
        scale: Float,
        displayName: String?,
        entities: MutableSet<ItemDisplay>
    ) {
        val playerId = player.uniqueId
        val world = player.world
        val location = Location(world, position.x(), position.y(), position.z())
        plugin.platformDispatcher.runAt(location) spawnTask@{
            if (anchorEntities[playerId]?.get(anchor.id) !== entities) return@spawnTask
            val displayEntity = world.spawn(location, ItemDisplay::class.java) { entity ->
                entity.setItemStack(ItemStack(material))
                entity.isPersistent = false
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
                entity.customName(displayName?.let { Component.text(it) })
                entity.isCustomNameVisible = displayName != null
                entity.transformation = Transformation(
                    entity.transformation.translation,
                    entity.transformation.leftRotation,
                    org.joml.Vector3f(scale, scale, scale),
                    entity.transformation.rightRotation
                )
                entity.isVisibleByDefault = false
            }
            entities.add(displayEntity)
            plugin.platformDispatcher.runForEntity(
                entity = player,
                action = {
                    if (anchorEntities[playerId]?.get(anchor.id) === entities) {
                        player.showEntity(plugin, displayEntity)
                    } else {
                        removeAnchorEntity(displayEntity)
                    }
                },
                retired = { removeAnchorEntity(displayEntity) }
            )
        }
    }

    private fun removeAnchorDisplay(player: Player, anchorId: String) {
        anchorEntities[player.uniqueId]?.remove(anchorId)?.forEach(::removeAnchorEntity)
    }

    private fun removeAllAnchorDisplays(player: Player) {
        anchorEntities.remove(player.uniqueId)?.values?.forEach { entities ->
            entities.forEach(::removeAnchorEntity)
        }
    }

    private fun removeSpeedLimitDisplays(player: Player) {
        speedLimitEntities.remove(player.uniqueId)?.forEach { entity ->
            plugin.platformDispatcher.runForEntity(entity, action = { entity.remove() })
        }
    }

    private fun spawnSpeedLimitDisplays(player: Player) {
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[player.world] as? CoreWorld ?: return
        val entities = ConcurrentHashMap.newKeySet<TextDisplay>()
        speedLimitEntities[player.uniqueId] = entities
        coreWorld.allRails.forEach { rail ->
            val limit = rail.metadata.speedLimits.value(SpeedLimitType.CURVE) ?: return@forEach
            val subgrade = rail.metadata.speedLimits.value(SpeedLimitType.SUBGRADE)
            if (!limit.isFinite() || subgrade == null || limit >= subgrade - 1.0e-6) return@forEach
            val point = rail.curve.getPointAt(rail.curve.totalArcLength / 2.0)
            val location = Location(player.world, point.position.x(), point.position.y() + 1.8, point.position.z())
            plugin.platformDispatcher.runAt(location) spawnTask@{
                if (speedLimitEntities[player.uniqueId] !== entities) return@spawnTask
                val display = player.world.spawn(location, TextDisplay::class.java) { entity ->
                    entity.text(Component.text(
                        "CURVE ${limit.times(3.6).roundToInt()} / SUBGRADE ${subgrade.times(3.6).roundToInt()} km/h",
                        NamedTextColor.YELLOW
                    ))
                    entity.billboard = Display.Billboard.CENTER
                    entity.isPersistent = false
                    entity.isVisibleByDefault = false
                    entity.isShadowed = true
                }
                entities.add(display)
                plugin.platformDispatcher.runForEntity(player, action = {
                    if (speedLimitEntities[player.uniqueId] === entities) player.showEntity(plugin, display)
                    else display.remove()
                }, retired = { display.remove() })
            }
        }
    }

    private fun removeAnchorEntity(entity: ItemDisplay) {
        plugin.platformDispatcher.runForEntity(entity, action = { entity.remove() })
    }

    /** 更新锚点展示效果：选中 = Glowing */
    internal fun updateAnchorDisplay(player: Player) {
        val selectedAnchors = getState(player).selectedAnchors.toSet()
        anchorEntities[player.uniqueId]?.forEach { (anchorId, entities) ->
            entities.forEach { entity ->
                plugin.platformDispatcher.runForEntity(entity, action = {
                    entity.isGlowing = anchorId in selectedAnchors
                })
            }
        }
    }

    private fun refreshAnchorsForPlayer(player: Player) {
        removeAllAnchorDisplays(player)
        removeSpeedLimitDisplays(player)
        if (!isWandItem(player.inventory.itemInMainHand) && !isWandItem(player.inventory.itemInOffHand)) {
            splitPreviewViewers.remove(player.uniqueId)
            decorationPreviewViewers.remove(player.uniqueId)
            anchorViewers.remove(player.uniqueId)
            return
        }
        anchorViewers.add(player.uniqueId)
        updatePreviewSubscriptions(player, getState(player))
        getAllAnchors(player.world).forEach { spawnAnchorDisplay(player, it) }
        spawnSpeedLimitDisplays(player)
        updateAnchorDisplay(player)
    }

    private fun updatePreviewSubscriptions(player: Player, state: WandPlayerState) {
        if (state.mode == WandMode.SPLIT && isWandItem(player.inventory.itemInMainHand)) {
            splitPreviewViewers.add(player.uniqueId)
        } else {
            splitPreviewViewers.remove(player.uniqueId)
        }
        if (state.mode == WandMode.DECORATE && isWandItem(player.inventory.itemInMainHand)) {
            decorationPreviewViewers.add(player.uniqueId)
        } else {
            decorationPreviewViewers.remove(player.uniqueId)
        }
    }

    private fun refreshAnchorsForAllViewers() {
        anchorViewers.forEach { playerId ->
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                anchorViewers.remove(playerId)
            } else {
                plugin.platformDispatcher.runForEntity(player, action = {
                    refreshAnchorsForPlayer(player)
                })
            }
        }
    }

    private fun scheduleAnchorVisibilityRefresh(player: Player) {
        plugin.platformDispatcher.runForEntityLater(player, 1L, action = {
            refreshAnchorsForPlayer(player)
        })
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
        removeSpeedLimitDisplays(player)
        getAllAnchors(player.world).forEach { spawnAnchorDisplay(player, it) }
        spawnSpeedLimitDisplays(player)
    }

    // ═══════════════════════════════════════════
    // Undo / Redo
    // ═══════════════════════════════════════════

    private fun pushUndo(player: Player, action: WandAction) {
        // Even operations that currently lack a reverse snapshot are recorded as chronological
        // barriers, so /jr undo never skips them and mutates an older unrelated action.
        val operation = RailWandOperation(action)
        operation.sequence = plugin.playerOperationService.record(
            player.uniqueId,
            plugin,
            operation
        )
        val anchorIds = createdAnchorIds(action)
        if (anchorIds.isNotEmpty()) {
            val playerOperations = anchorCreationOperations.computeIfAbsent(player.uniqueId) {
                ConcurrentHashMap()
            }
            anchorIds.forEach { playerOperations[it] = operation }
        }
    }

    private inner class RailWandOperation(
        initialAction: WandAction
    ) : UndoableOperation {
        @Volatile
        private var action: WandAction = initialAction
        var sequence: Long = -1L

        override fun description(): Component = Component.text("RailWand 操作")

        override fun undo(player: Player): CompletionStage<OperationResult> =
            CompletableFuture.completedFuture(applyUndo(player, action))

        override fun redo(player: Player): CompletionStage<OperationResult> =
            CompletableFuture.completedFuture(applyRedo(player, action))

        @Synchronized
        fun discardCreatedAnchors(anchorIds: Set<String>): Boolean {
            val retained = action.withoutCreatedAnchors(anchorIds) ?: return false
            action = retained
            return true
        }
    }

    private fun discardCreatedAnchorOperations(player: Player, anchorIds: Set<String>) {
        val indexed = anchorCreationOperations[player.uniqueId] ?: return
        val affected = anchorIds.mapNotNull(indexed::remove).toSet()
        affected.forEach { operation ->
            if (!operation.discardCreatedAnchors(anchorIds)) {
                plugin.playerOperationService.discard(player.uniqueId, plugin, operation.sequence)
            }
        }
        if (indexed.isEmpty()) anchorCreationOperations.remove(player.uniqueId, indexed)
    }

    private fun forgetCreatedAnchorOperations(player: Player, action: WandAction) {
        val indexed = anchorCreationOperations[player.uniqueId] ?: return
        createdAnchorIds(action).forEach(indexed::remove)
        if (indexed.isEmpty()) anchorCreationOperations.remove(player.uniqueId, indexed)
    }

    private fun createdAnchorIds(action: WandAction): Set<String> = when (action) {
        is WandAction.CreateAnchor -> setOf(action.anchorId)
        is WandAction.MultiAction -> action.actions.flatMapTo(linkedSetOf(), ::createdAnchorIds)
        else -> emptySet()
    }

    private fun WandAction.withoutCreatedAnchors(anchorIds: Set<String>): WandAction? = when (this) {
        is WandAction.CreateAnchor -> takeUnless { anchorId in anchorIds }
        is WandAction.MultiAction -> actions.mapNotNull { it.withoutCreatedAnchors(anchorIds) }
            .takeIf { it.isNotEmpty() }
            ?.let(WandAction::MultiAction)
        else -> this
    }

    private fun applyUndo(player: Player, action: WandAction): OperationResult {
        if (!canUndo(action)) {
            return OperationResult.unsupported(Component.text("该 RailWand 操作无法撤销", NamedTextColor.RED))
        }
        if (!isInActionWorld(player, action)) {
            return OperationResult.failed(Component.text("请回到执行该操作的世界后再撤销", NamedTextColor.RED))
        }
        val applied = when (action) {
            is WandAction.CreateAnchor -> { undoCreateAnchor(player, action); true }
            is WandAction.DeleteAnchor -> { undoDeleteAnchor(player, action); true }
            is WandAction.CreateRail -> undoCreateRail(player, action)
            is WandAction.DeleteRail -> false
            is WandAction.ReplaceRails -> undoReplaceRails(player, action)
            is WandAction.MultiAction -> action.actions.asReversed().all { applyUndoPart(player, it) }
        }
        if (!applied) {
            return OperationResult.failed(Component.text("RailWand 撤销失败", NamedTextColor.RED))
        }
        forgetCreatedAnchorOperations(player, action)
        persistState(player, getState(player))
        return OperationResult.success(Component.text("已撤销 RailWand 操作", NamedTextColor.GREEN))
    }

    private fun applyRedo(player: Player, action: WandAction): OperationResult {
        if (!canRedo(action)) {
            return OperationResult.unsupported(Component.text("该 RailWand 操作不支持重做", NamedTextColor.RED))
        }
        if (!isInActionWorld(player, action)) {
            return OperationResult.failed(Component.text("请回到执行该操作的世界后再重做", NamedTextColor.RED))
        }
        val applied = when (action) {
            is WandAction.DeleteAnchor -> { redoDeleteAnchor(player, action); true }
            is WandAction.MultiAction -> action.actions.all { applyRedoPart(player, it) }
            else -> false
        }
        if (!applied) {
            return OperationResult.failed(Component.text("RailWand 重做失败", NamedTextColor.RED))
        }
        persistState(player, getState(player))
        return OperationResult.success(Component.text("已重做 RailWand 操作", NamedTextColor.GREEN))
    }

    private fun applyUndoPart(player: Player, action: WandAction): Boolean = when (action) {
        is WandAction.CreateAnchor -> { undoCreateAnchor(player, action); true }
        is WandAction.DeleteAnchor -> { undoDeleteAnchor(player, action); true }
        is WandAction.CreateRail -> undoCreateRail(player, action)
        is WandAction.DeleteRail -> false
        is WandAction.ReplaceRails -> undoReplaceRails(player, action)
        is WandAction.MultiAction -> action.actions.asReversed().all { applyUndoPart(player, it) }
    }

    private fun applyRedoPart(player: Player, action: WandAction): Boolean = when (action) {
        is WandAction.DeleteAnchor -> { redoDeleteAnchor(player, action); true }
        is WandAction.MultiAction -> action.actions.all { applyRedoPart(player, it) }
        else -> false
    }

    private fun canUndo(action: WandAction): Boolean = when (action) {
        is WandAction.CreateAnchor, is WandAction.DeleteAnchor, is WandAction.CreateRail,
        is WandAction.ReplaceRails -> true
        is WandAction.DeleteRail -> false
        is WandAction.MultiAction -> action.actions.isNotEmpty() && action.actions.all(::canUndo)
    }

    private fun canRedo(action: WandAction): Boolean = when (action) {
        is WandAction.DeleteAnchor -> true
        is WandAction.MultiAction -> action.actions.isNotEmpty() && action.actions.all(::canRedo)
        else -> false
    }

    private fun isInActionWorld(player: Player, action: WandAction): Boolean {
        val worldIds = actionWorldIds(action)
        return worldIds.size == 1 && player.world.uid in worldIds
    }

    private fun actionWorldIds(action: WandAction): Set<UUID> = when (action) {
        is WandAction.CreateAnchor -> setOf(action.worldId)
        is WandAction.DeleteAnchor -> setOf(action.worldId)
        is WandAction.CreateRail -> setOf(action.worldId)
        is WandAction.DeleteRail -> setOf(action.worldId)
        is WandAction.ReplaceRails -> setOf(action.worldId)
        is WandAction.MultiAction -> action.actions.flatMapTo(linkedSetOf(), ::actionWorldIds)
    }

    private fun undoCreateAnchor(player: Player, action: WandAction.CreateAnchor) {
        val world = player.world
        removeAnchorDisplay(player, action.anchorId)
        removeAnchor(world, action.anchorId)
        getState(player).selectedAnchors.remove(action.anchorId)
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

    private fun undoReplaceRails(player: Player, action: WandAction.ReplaceRails): Boolean {
        val coreWorld = getCoreWorld(player) ?: return false
        val createdRails = action.createdRailIds.map { railId -> coreWorld.getRail(railId) ?: return false }
        if (createdRails.any { it.trains.isNotEmpty() }) return false
        val removedIds = action.removedRails.mapTo(linkedSetOf()) { UUID.fromString(it.id) }
        if (removedIds.any { coreWorld.getRail(it) != null }) return false

        val dependency = CoreRail.SavedRailV1.Dependency(coreWorld)
        val restoredRails = mutableListOf<CoreRail>()
        val topologyAtUndo = coreWorld.allRails.map(CoreRail::save)
        val renderAtUndo = RailRenderMetaStore.load(player.world)
        val metadataAtUndo = coreWorld.basicWorldData.railMetadata.snapshot()
        var referencesMigrated = false
        return try {
            action.removedRails.forEach { restoredRails += it.loadDisconnected(dependency) }
            coreWorld.basicWorldData.railMetadata.replace(
                removals = action.createdRailIds,
                additions = action.railMetadataBefore.filterKeys { it in removedIds }
            )
            restoredRails.forEach(OperationalSpeedLimitSignalMaterializer::materializeRail)
            if (action.createdToRemovedIds.isNotEmpty()) {
                OperationalDestinationService(coreWorld).migrateAllOnRails(action.createdToRemovedIds)
                referencesMigrated = true
            }
            action.worldTopologyBefore.forEach { saved ->
                val rail = coreWorld.getRail(UUID.fromString(saved.id)) ?: return@forEach
                saved.restoreConnections(rail, dependency)
            }
            RailRenderMetaStore.saveAll(player.world, action.renderMetasBefore)
            action.renderMetasBefore.filter { it.railId in removedIds }.forEach {
                RailVisualBootstrap.fromPersisted(coreWorld, it)
            }
            val replacementSources = action.createdToRemovedIds.keys
            createdRails.forEach { rail ->
                if (rail.id !in replacementSources) {
                    OperationalDestinationService(coreWorld).removeAllOnRail(rail.id)
                }
                RailVisualBootstrap.unregisterRail(rail.id)
                rail.destroy()
                coreWorld.removeRail(rail)
            }
            val state = getState(player)
            state.selectedRails.removeIf { it in action.createdRailIds }
            state.selectedAnchors.removeIf { railIdFromEndpointAnchor(it) in action.createdRailIds }
            refreshAllAnchorsForPlayer(player)
            updateAnchorDisplay(player)
            true
        } catch (failure: Exception) {
            if (referencesMigrated && createdRails.all { coreWorld.getRail(it.id) != null }) {
                runCatching {
                    OperationalDestinationService(coreWorld).migrateAllOnRails(
                        action.createdToRemovedIds.entries.associate { (createdId, removedId) -> removedId to createdId }
                    )
                }.onFailure(failure::addSuppressed)
            }
            restoredRails.forEach { restored ->
                restored.destroy()
                coreWorld.removeRail(restored)
            }
            coreWorld.basicWorldData.railMetadata.replace(
                removals = removedIds,
                additions = metadataAtUndo.filterKeys { it in action.createdRailIds }
            )
            topologyAtUndo.forEach { saved ->
                coreWorld.getRail(UUID.fromString(saved.id))?.let { saved.restoreConnections(it, dependency) }
            }
            RailRenderMetaStore.saveAll(player.world, renderAtUndo)
            plugin.logger.warning("RailWand replacement undo failed: ${failure.message ?: failure::class.simpleName}")
            false
        }
    }

    private fun undoCreateRail(player: Player, action: WandAction.CreateRail): Boolean {
        val coreWorld = getCoreWorld(player) ?: return false
        val rail = coreWorld.getRail(action.railId)
        if (rail != null) {
            OperationalDestinationService(coreWorld).removeAllOnRail(action.railId)
            RailVisualBootstrap.unregisterRail(action.railId)
            RailRenderMetaStore.removeRail(player.world, action.railId)
            rail.destroy()
            coreWorld.removeRail(rail)
            coreWorld.basicWorldData.railMetadata.remove(action.railId)
        }
        val state = getState(player)
        state.selectedRails.remove(action.railId)
        state.selectedAnchors.removeIf { railIdFromEndpointAnchor(it) == action.railId }
        return true
    }

    // ═══════════════════════════════════════════
    // 模式设置 (使用 ConfigAPI + Paper Dialog)
    // ═══════════════════════════════════════════

    private data class RailModelMenuOption(val name: String, val displayName: String)

    private fun loadedRailModelOptions(): List<RailModelMenuOption> = plugin.modelManager.railPackages.values
        .map { loaded ->
            RailModelMenuOption(
                name = loaded.manifest.name,
                displayName = loaded.manifest.displayName?.takeIf(String::isNotBlank) ?: loaded.manifest.name
            )
        }
        .sortedBy { it.displayName.lowercase() }

    internal fun selectedDecorationModel(player: Player, state: WandPlayerState): String? {
        val models = loadedRailModelOptions()
        if (models.isEmpty()) {
            player.sendActionBar(Component.text("没有已加载的轨道模型", NamedTextColor.RED))
            return null
        }
        if (models.none { it.name == state.decorationModel }) {
            state.decorationModel = models.first().name
            persistState(player, state)
        }
        return state.decorationModel
    }

    private fun showDecorationModelSettings(player: Player, state: WandPlayerState) {
        val models = loadedRailModelOptions()
        if (models.isEmpty()) {
            player.sendActionBar(Component.text("没有已加载的轨道模型", NamedTextColor.RED))
            return
        }
        val selectedName = selectedDecorationModel(player, state) ?: return
        dialogRenderer.showMenu(
            player,
            "RailWand 装饰模型",
            "当前：$selectedName；用于右键装饰及自动装饰",
            models.map { model ->
                val marker = if (model.name == selectedName) "✓ " else ""
                DialogRenderer.MenuEntry("$marker${model.displayName}") { selectedPlayer ->
                    val liveState = getState(selectedPlayer)
                    liveState.decorationModel = model.name
                    persistState(selectedPlayer, liveState)
                    selectedPlayer.sendActionBar(Component.text(
                        "装饰模型已设为 ${model.displayName}",
                        NamedTextColor.GREEN
                    ))
                    showDecorationModelSettings(selectedPlayer, liveState)
                }
            } + DialogRenderer.MenuEntry("← 返回设置") { selectedPlayer ->
                showModeSettings(selectedPlayer, getState(selectedPlayer))
            }
        )
    }

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
                DialogRenderer.MenuEntry("1/3 锚点与并行线路") { openPage(it, "wand.settings.general") },
                DialogRenderer.MenuEntry("2/3 曲线、道岔与限速") { openPage(it, "wand.settings.geometry") },
                DialogRenderer.MenuEntry("3/3 装饰模型：${state.decorationModel}") {
                    showDecorationModelSettings(it, getState(it))
                }
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
        state.pendingDecorationDeleteRail = null
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
