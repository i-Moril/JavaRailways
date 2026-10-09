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
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.ui.config.*
import work.art1st.javarailways.basic.curve.Anchor
import work.art1st.javarailways.basic.curve.AnchorGeometry
import work.art1st.javarailways.basic.curve.ParallelAnchorGeometry
import work.art1st.javarailways.basic.curve.ParallelAnchorMatch
import work.art1st.javarailways.basic.curve.RailCurveBuildOptions
import work.art1st.javarailways.basic.curve.RailCurveFactory
import work.art1st.javarailways.basic.curve.RailCurvePartitioner
import work.art1st.javarailways.basic.curve.RailGeometryMeasurementService
import work.art1st.javarailways.basic.curve.RailCurveTemplateType
import work.art1st.javarailways.basic.curve.StandardTurnout
import work.art1st.javarailways.basic.curve.StandardTurnoutFactory
import work.art1st.javarailways.basic.curve.SuperelevationConfig
import work.art1st.javarailways.basic.curve.VerticalCurveRebuilder
import work.art1st.javarailways.basic.impl.config.ConfigUIRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.basicWorldDataOrNull
import work.art1st.javarailways.simpleato.simpleAtoWorldDataOrNull
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.basic.rail.CurveSafetyMetadata
import work.art1st.javarailways.basic.rail.ParallelRailMetadata
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailAssemblyKind
import work.art1st.javarailways.basic.rail.RailAssemblyMetadata
import work.art1st.javarailways.basic.rail.RailAssemblyRole
import work.art1st.javarailways.basic.rail.RailCurveKind
import work.art1st.javarailways.basic.rail.RailGeometryMetadata
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignalMaterializer
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadataTopology
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadataStore
import work.art1st.javarailways.basic.rail.RailMetadata
import work.art1st.javarailways.basic.rail.RailOperatingMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.TurnoutTopology
import work.art1st.javarailways.basic.rail.resolveLogicalRailLaneIds
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.basic.safety.RailSpeedCalculator
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
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationBatch
import work.art1st.javarailways.core.physics.rail.RailTopologyEditor
import work.art1st.javarailways.core.physics.rail.RailView
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

private const val MAXIMUM_GRADE_DISPLAY_EPSILON = 1.0e-9
internal const val RAIL_WAND_MAX_SUPERELEVATION_METERS = 0.15

/** Canonicalizes legacy manual-cant settings to the current enabled/disabled policy. */
internal fun railWandSuperelevationConfig(
    enabled: Boolean,
    designSpeedKmH: Double,
    balanceSpeedOffsetKmH: Double,
    maximumCantGradient: Double,
    gaugeMeters: Double,
    gravityMetersPerSecondSquared: Double
): SuperelevationConfig? {
    if (!enabled) return null
    return SuperelevationConfig(
        maxSuperelevation = RAIL_WAND_MAX_SUPERELEVATION_METERS,
        autoCalculate = true,
        designSpeedKmH = (designSpeedKmH - balanceSpeedOffsetKmH).coerceAtLeast(0.0),
        maxCantGradient = maximumCantGradient,
        gaugeMeters = gaugeMeters,
        gravityMetersPerSecondSquared = gravityMetersPerSecondSquared
    )
}

internal fun buildRailGeometryDisplayText(
    radiusMeters: Double?,
    maximumAbsoluteGrade: Double,
    speedLine: String?,
    maximumGradeLabel: String
): String? {
    require(radiusMeters == null || radiusMeters.isFinite() && radiusMeters > 0.0)
    require(maximumAbsoluteGrade.isFinite() && maximumAbsoluteGrade >= 0.0)
    val gradePermille = maximumAbsoluteGrade
        .takeIf { it > MAXIMUM_GRADE_DISPLAY_EPSILON }
        ?.let { grade ->
            java.math.BigDecimal.valueOf(grade * 1000.0)
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }
    if (radiusMeters == null && gradePermille == null) return null
    return buildList {
        radiusMeters?.let { add("R ${it.roundToInt()} m") }
        gradePermille?.let { add("$maximumGradeLabel $it ‰") }
        speedLine?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString("\n")
}

internal fun resolveCurveOperatingLimitMetersPerSecond(
    automaticLimit: Double,
    requestedLimit: Double?,
    automatic: Boolean,
    creationMinimum: Boolean
): Double {
    require(automaticLimit.isFinite() && automaticLimit > 0.0)
    if (automatic) return automaticLimit
    val requested = requestedLimit
        ?: throw RailWandLocalizedException("message.manual_curve_speed_positive")
    require(requested.isFinite() && requested > 0.0)
    if (requested > automaticLimit + 1.0e-6) {
        val requestedKmH = "%.1f".format(Locale.ROOT, requested * 3.6)
        val automaticKmH = "%.1f".format(Locale.ROOT, automaticLimit * 3.6)
        val key = if (creationMinimum) {
            "failure.creation_speed_unsatisfied"
        } else {
            "failure.rebuild_speed_unsatisfied"
        }
        throw RailWandLocalizedException(key, requestedKmH, automaticKmH)
    }
    return if (creationMinimum) automaticLimit else requested
}

internal fun curveSpeedRequiresGeometryRebuild(
    requestedKmH: Double,
    automatic: Boolean,
    automaticOperatingLimitKmH: Double
): Boolean = !automatic && requestedKmH > automaticOperatingLimitKmH + 1.0e-6

internal fun railGeometryParametersChanged(
    currentDesignSpeedKmH: Double,
    currentRadiusMeters: Double,
    currentSuperelevationEnabled: Boolean,
    newDesignSpeedKmH: Double,
    newRadiusMeters: Double,
    newSuperelevationEnabled: Boolean
): Boolean = abs(newDesignSpeedKmH - currentDesignSpeedKmH) >= 1.0e-6 ||
    abs(newRadiusMeters - currentRadiusMeters) >= 1.0e-6 ||
    newSuperelevationEnabled != currentSuperelevationEnabled

internal fun curveSpeedAutomaticForRebuiltLane(
    laneIndex: Int,
    selectedLaneIndex: Int,
    selectedAutomatic: Boolean,
    oldLane: List<RailMetadata>
): Boolean = if (laneIndex == selectedLaneIndex) {
    selectedAutomatic
} else {
    oldLane.first().authoring?.curveSpeedLimitAutomatic != false
}

internal fun curveSpeedLimitForRebuiltLane(
    laneIndex: Int,
    selectedLaneIndex: Int,
    editedCurveSpeedLimitKmH: Double,
    oldLane: List<RailMetadata>
): Double {
    if (laneIndex == selectedLaneIndex) return editedCurveSpeedLimitKmH
    return oldLane.firstNotNullOfOrNull { metadata ->
        metadata.speedLimits.value(SpeedLimitType.CURVE)
            ?.takeIf { metadata.authoring?.curveSpeedLimitAutomatic == false }
    }?.times(3.6) ?: 0.0
}

/**
 * 终点的水平位置投影到起点 bearing，垂直高度直接采用右键位置。
 */
internal fun straightTerminalPosition(
    startPosition: Vector3dc,
    startTangent: Vector3dc,
    clickedPoint: Vector3dc
): Vector3d {
    val horizontalTangent = Vector3d(startTangent.x(), 0.0, startTangent.z())
    if (horizontalTangent.lengthSquared() < 1.0e-12) {
        throw RailWandLocalizedException("failure.start_horizontal_direction")
    }
    horizontalTangent.normalize()
    val horizontalOffset = Vector3d(clickedPoint).sub(startPosition).also { it.y = 0.0 }
    val distance = horizontalOffset.dot(horizontalTangent)
    return Vector3d(startPosition)
        .fma(distance, horizontalTangent)
        .also { it.y = clickedPoint.y() }
}

/** 新建终点默认使用水平 Pitch；高程变化由直线的独立纵断面处理。 */
internal fun straightTerminalTangent(startPosition: Vector3dc, terminalPosition: Vector3dc): Vector3d {
    val horizontalChord = Vector3d(terminalPosition).sub(startPosition).also { it.y = 0.0 }
    if (horizontalChord.lengthSquared() < 1.0e-12) {
        throw RailWandLocalizedException("failure.straight_too_short")
    }
    return horizontalChord.normalize()
}

/** Returns a horizontal-plan tangent, preserving manual Pitch on a connected endpoint. */
internal fun twoPointStraightTangent(
    from: Vector3dc,
    to: Vector3dc,
    currentTangent: Vector3dc,
    endpointIsEmpty: Boolean
): Vector3d? {
    val chordHorizontal = Vector3d(to).sub(from).also { it.y = 0.0 }
    if (chordHorizontal.lengthSquared() <= 1.0e-12) return null
    chordHorizontal.normalize()
    if (endpointIsEmpty) return chordHorizontal

    val current = Vector3d(currentTangent)
    if (current.lengthSquared() <= 1.0e-12) return null
    current.normalize()
    val currentHorizontal = Vector3d(current.x, 0.0, current.z)
    if (currentHorizontal.lengthSquared() <= 1.0e-12) return null
    currentHorizontal.normalize()
    if (abs(currentHorizontal.dot(chordHorizontal)) < 1.0 - 1.0e-6) return null
    return current
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
    CREATE("mode.create.name", "mode.create.description"),
    CONNECT("mode.connect.name", "mode.connect.description"),
    GRADE_SMOOTH("mode.grade_smooth.name", "mode.grade_smooth.description"),
    TURNOUT("mode.turnout.name", "mode.turnout.description"),
    EDIT("mode.edit.name", "mode.edit.description"),
    SPLIT("mode.split.name", "mode.split.description"),
    DELETE("mode.delete.name", "mode.delete.description"),
    DECORATE("mode.decorate.name", "mode.decorate.description");
}

// ═══════════════════════════════════════════════════════════════
// 撤销操作记录
// ═══════════════════════════════════════════════════════════════

sealed class WandAction {
    data class CreateAnchor(val worldId: UUID, val anchor: Anchor) : WandAction()
    data class DeleteAnchor(val worldId: UUID, val anchor: Anchor) : WandAction()
    data class EditAnchors(
        val worldId: UUID,
        val anchorsBefore: List<Anchor>,
        val expectedAfter: List<Anchor>
    ) : WandAction()
    data class RestoreRailWorld(
        val worldId: UUID,
        val before: RailWorldUndoSnapshot,
        val expectedAfter: RailWorldUndoSnapshot
    ) : WandAction()
    data class RestoreRailMetadata(
        val worldId: UUID,
        val metadataBefore: Map<UUID, RailMetadata>,
        val expectedAfter: Map<UUID, RailMetadata>
    ) : WandAction()
    data class RestoreDecorations(
        val worldId: UUID,
        val railIds: Set<UUID>,
        val renderMetasBefore: List<PersistedRailRenderMeta>,
        val expectedAfter: List<PersistedRailRenderMeta>
    ) : WandAction()
    data class MultiAction(val actions: List<WandAction>) : WandAction()
}

data class RailWorldUndoSnapshot(
    val rails: List<CoreRail.SavedRailV1>,
    val renderMetas: List<PersistedRailRenderMeta>,
    val railMetadata: Map<UUID, RailMetadata>,
    val operatingDirections: Map<UUID, ViewDirection>,
    val destinations: List<OperationalDestination>,
    val wayPoints: List<WayPoint>
)

internal fun wandActionSupportsUndo(action: WandAction): Boolean = when (action) {
    is WandAction.MultiAction -> action.actions.isNotEmpty() && action.actions.all(::wandActionSupportsUndo)
    else -> true
}

/** Current redo coverage, kept explicit so unsupported action kinds cannot be hidden by a default branch. */
internal fun wandActionSupportsRedo(action: WandAction): Boolean = when (action) {
    is WandAction.DeleteAnchor -> true
    is WandAction.MultiAction -> action.actions.isNotEmpty() && action.actions.all(::wandActionSupportsRedo)
    is WandAction.CreateAnchor,
    is WandAction.EditAnchors,
    is WandAction.RestoreRailWorld,
    is WandAction.RestoreRailMetadata,
    is WandAction.RestoreDecorations -> false
}

internal data class OperationalInfrastructureRestore(
    val destinationIdsToRemove: Set<String>,
    val wayPointIdsToRemove: Set<String>,
    val destinationsToRestore: List<OperationalDestination>,
    val wayPointsToRestore: List<WayPoint>
)

internal fun planOperationalInfrastructureRestore(
    currentDestinations: List<OperationalDestination>,
    currentWayPoints: List<WayPoint>,
    targetDestinations: List<OperationalDestination>,
    targetWayPoints: List<WayPoint>
): OperationalInfrastructureRestore {
    val currentDestinationsById = currentDestinations.associateBy { it.id }
    val targetDestinationsById = targetDestinations.associateBy { it.id }
    val currentStandaloneWayPoints = currentWayPoints
        .filterNot { it.id in currentDestinationsById }
        .associateBy { it.id }
    val targetStandaloneWayPoints = targetWayPoints
        .filterNot { it.id in targetDestinationsById }
        .associateBy { it.id }
    return OperationalInfrastructureRestore(
        destinationIdsToRemove = currentDestinationsById.keys.filterTo(linkedSetOf()) { id ->
            currentDestinationsById[id] != targetDestinationsById[id]
        },
        wayPointIdsToRemove = currentStandaloneWayPoints.keys.filterTo(linkedSetOf()) { id ->
            currentStandaloneWayPoints[id] != targetStandaloneWayPoints[id]
        },
        destinationsToRestore = targetDestinationsById.values.filter { destination ->
            currentDestinationsById[destination.id] != destination
        },
        wayPointsToRestore = targetStandaloneWayPoints.values.filter { wayPoint ->
            currentStandaloneWayPoints[wayPoint.id] != wayPoint
        }
    )
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
    /** 创建约束中的精确曲线半径 (m)；自动模式下忽略。 */
    var curveRadius: Double = 0.0,
    /** 是否自动求解曲线半径。 */
    var automaticCurveRadius: Boolean = true,
    /** 曲线模板。AUTO 会按端点几何自动分类；STRAIGHT 保持水平朝向并采用右键高度。 */
    var curveTemplate: RailCurveTemplateType = RailCurveTemplateType.AUTO,
    /** 标准道岔号数，同时确定侧线半径和限速。 */
    var turnoutStandard: StandardTurnout = StandardTurnout.N9,
    /** 新建轨道的 SUBGRADE 上级限速。 */
    var designSpeedKmH: Double = 80.0,
    /** 创建约束中的 CURVE 最低运营限速；自动模式下忽略。 */
    var curveSpeedLimitKmH: Double = 0.0,
    /** 是否忽略最低限速并直接采用自动计算的 CURVE 运营限速。 */
    var automaticCurveSpeedLimit: Boolean = true,
    /** 运营限速计算允许的欠超高。 */
    var allowableCantDeficiencyMm: Double = 75.0,
    /** 是否启用按设计时速自动计算的外轨超高。 */
    var superelevationEnabled: Boolean = true
) : WandState<WandMode>

@Serializable
internal data class SavedRailWandState(
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
    val superelevationEnabled: Boolean,
    val curveSpeedLimitKmH: Double = 0.0,
    val automaticCurveRadius: Boolean = true,
    val automaticCurveSpeedLimit: Boolean = true
)

internal object RailWandStateCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(state: WandPlayerState): String = json.encodeToString(
        SavedRailWandState(
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
            superelevationEnabled = state.superelevationEnabled,
            curveSpeedLimitKmH = state.curveSpeedLimitKmH,
            automaticCurveRadius = state.automaticCurveRadius,
            automaticCurveSpeedLimit = state.automaticCurveSpeedLimit
        )
    )

    fun decode(encoded: String): WandPlayerState? = runCatching {
        json.decodeFromString<SavedRailWandState>(encoded).toState().validated()
    }.getOrNull()

    private fun SavedRailWandState.toState(): WandPlayerState = WandPlayerState(
        mode = enumValueOf<WandMode>(mode),
        selectedAnchors = selectedAnchors.toMutableList(),
        selectedRails = selectedRails.map(UUID::fromString).toMutableList(),
        pendingDeleteAnchor = pendingDeleteAnchor,
        pendingDeleteRail = pendingDeleteRail?.let(UUID::fromString),
        pendingSplitRail = pendingSplitRail?.let(UUID::fromString),
        pendingSplitArcLength = pendingSplitArcLength,
        pendingDecorationDeleteRail = pendingDecorationDeleteRail?.let(UUID::fromString),
        parallelCount = parallelCount,
        parallelSpacing = parallelSpacing,
        snapToAngle = snapToAngle,
        snapToBlock = snapToBlock,
        openConfigAfterCreate = openConfigAfterCreate,
        autoDecorate = autoDecorate,
        decorationModel = decorationModel,
        curveRadius = curveRadius,
        automaticCurveRadius = automaticCurveRadius,
        curveTemplate = enumValueOf<RailCurveTemplateType>(curveTemplate),
        turnoutStandard = enumValueOf<StandardTurnout>(turnoutStandard),
        designSpeedKmH = designSpeedKmH,
        curveSpeedLimitKmH = curveSpeedLimitKmH,
        automaticCurveSpeedLimit = automaticCurveSpeedLimit,
        allowableCantDeficiencyMm = allowableCantDeficiencyMm,
        superelevationEnabled = superelevationEnabled
    )

    private fun WandPlayerState.validated(): WandPlayerState = apply {
        require(parallelCount in 1..10)
        require(parallelSpacing in 1.0..20.0)
        val splitArcLength = pendingSplitArcLength
        require(splitArcLength == null || splitArcLength.isFinite() && splitArcLength > 0.0)
        require(curveRadius.isFinite() && curveRadius >= 0.0)
        require(designSpeedKmH in 5.0..500.0)
        require(curveSpeedLimitKmH in 0.0..500.0)
        require(allowableCantDeficiencyMm in 0.0..300.0)
        decorationModel = decorationModel.trim().also { require(it.isNotEmpty()) }
    }
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
    itemLore = emptyList(),
    modes = WandMode.entries.toList()
) {

    override val modeHandlesSneakLeftClickAir: Boolean = true

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

    override fun itemLoreFor(player: Player): List<Component> = listOf(
        RailWandI18n.component(player, "item.lore.switch_mode").color(NamedTextColor.GRAY),
        RailWandI18n.component(player, "item.lore.open_settings").color(NamedTextColor.GRAY)
    )

    override fun modeDisplayNameFor(player: Player, mode: WandMode): String =
        RailWandI18n.format(player, "mode.${mode.name.lowercase(Locale.ROOT)}.name")

    override fun modeDescriptionFor(player: Player, mode: WandMode): String =
        RailWandI18n.format(player, "mode.${mode.name.lowercase(Locale.ROOT)}.description")

    override fun selectionClearedMessage(player: Player): Component =
        RailWandI18n.component(player, "message.anchor_selection_cancelled").color(NamedTextColor.GRAY)

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
            label = "settings.parallel_count.label"
            description = "settings.parallel_count.description"
            defaultValue(1)
            valueType = ConfigValueType.INTEGER
            constraints = ConfigConstraints.IntRange(1, 10)
            group = "wand.settings"
            order = 0
        }
        val parallelSpacing = ConfigKey.build<Double>("wand.parallelSpacing") {
            label = "settings.parallel_spacing.label"
            description = "settings.parallel_spacing.description"
            defaultValue(4.5)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(1.0, 20.0, 0.5, "m")
            group = "wand.settings"
            order = 1
        }
        val snapToAngle = ConfigKey.build<Boolean>("wand.snapAngle") {
            label = "settings.snap_angle.label"
            description = "settings.snap_angle.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 2
        }
        val snapToBlock = ConfigKey.build<Boolean>("wand.snapBlock") {
            label = "settings.snap_block.label"
            description = "settings.snap_block.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 3
        }
        val openConfigAfterCreate = ConfigKey.build<Boolean>("wand.openConfig") {
            label = "settings.open_after_create.label"
            description = "settings.open_after_create.description"
            defaultValue(false)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 4
        }
        val autoDecorate = ConfigKey.build<Boolean>("wand.autoDecorate") {
            label = "settings.auto_decorate.label"
            description = "settings.auto_decorate.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 5
        }
        val curveRadius = ConfigKey.build<Double>("wand.curveRadius") {
            label = "settings.curve_radius.label"
            description = "settings.curve_radius.description"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER_TEXT
            constraints = ConfigConstraints.NumberRange(0.0, 10000.0, 1.0, "m")
            group = "wand.settings"
            order = 6
        }
        val automaticCurveRadius = ConfigKey.build<Boolean>("wand.automaticCurveRadius") {
            label = "settings.automatic_radius.label"
            description = "settings.automatic_radius.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 7
        }
        val curveTemplate = ConfigKey.build<RailCurveTemplateType>("wand.curveTemplate") {
            label = "settings.curve_template.label"
            description = "settings.curve_template.description"
            defaultValue(RailCurveTemplateType.AUTO)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(
                RailCurveTemplateType.entries,
                RailCurveTemplateType.entries.associateWith {
                    "curve_template.${it.name.lowercase(Locale.ROOT)}"
                }
            )
            group = "wand.settings"
            order = 8
        }
        val turnoutStandard = ConfigKey.build<StandardTurnout>("wand.turnoutStandard") {
            label = "settings.turnout_standard.label"
            description = "settings.turnout_standard.description"
            defaultValue(StandardTurnout.N9)
            valueType = ConfigValueType.ENUM
            constraints = ConfigConstraints.EnumOptions(StandardTurnout.entries, turnoutLabels)
            group = "wand.settings"
            order = 9
        }
        val designSpeedKmH = ConfigKey.build<Double>("wand.designSpeedKmH") {
            label = "settings.design_speed.label"
            description = "settings.design_speed.description"
            defaultValue(80.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(5.0, 500.0, 5.0, "km/h")
            group = "wand.settings"
            order = 10
        }
        val curveSpeedLimitKmH = ConfigKey.build<Double>("wand.curveSpeedLimitKmH") {
            label = "settings.curve_speed.label"
            description = "settings.curve_speed.description"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER_TEXT
            constraints = ConfigConstraints.NumberRange(0.0, 500.0, 1.0, "km/h")
            group = "wand.settings"
            order = 11
        }
        val automaticCurveSpeedLimit = ConfigKey.build<Boolean>("wand.automaticCurveSpeedLimit") {
            label = "settings.automatic_speed.label"
            description = "settings.automatic_speed.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 12
        }
        val allowableCantDeficiencyMm = ConfigKey.build<Double>("wand.allowableCantDeficiencyMm") {
            label = "settings.cant_deficiency.label"
            description = "settings.cant_deficiency.description"
            defaultValue(75.0)
            valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(0.0, 300.0, 5.0, "mm")
            group = "wand.settings"
            order = 13
        }
        val superelevationEnabled = ConfigKey.build<Boolean>("wand.superelevationEnabled") {
            label = "settings.superelevation.enabled.label"
            description = "settings.superelevation.enabled.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "wand.settings"
            order = 14
        }
    }

    class RailEditKeys {
        val designSpeedKmH = ConfigKey.build<Double>("rail.edit.designSpeedKmH") {
            label = "rail_edit.design_speed.label"
            description = "rail_edit.design_speed.description"
            defaultValue(80.0)
            valueType = ConfigValueType.NUMBER_TEXT
            constraints = ConfigConstraints.NumberRange(5.0, 500.0, 1.0, "km/h")
            group = "rail.edit"
            order = 0
        }
        val curveSpeedLimitKmH = ConfigKey.build<Double>("rail.edit.curveSpeedLimitKmH") {
            label = "rail_edit.curve_speed.label"
            description = "rail_edit.curve_speed.description"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER_TEXT
            constraints = ConfigConstraints.NumberRange(0.0, 100000.0, 1.0, "km/h")
            group = "rail.edit"
            order = 1
        }
        val automaticCurveSpeedLimit = ConfigKey.build<Boolean>("rail.edit.automaticCurveSpeedLimit") {
            label = "rail_edit.automatic_speed.label"
            description = "rail_edit.automatic_speed.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "rail.edit"
            order = 2
        }
        val curveRadius = ConfigKey.build<Double>("rail.edit.curveRadius") {
            label = "rail_edit.radius.label"
            description = "rail_edit.radius.description"
            defaultValue(0.0)
            valueType = ConfigValueType.NUMBER_TEXT
            constraints = ConfigConstraints.NumberRange(0.0, 1.0e9, 1.0, "m")
            group = "rail.edit"
            order = 3
        }
        val automaticCurveRadius = ConfigKey.build<Boolean>("rail.edit.automaticCurveRadius") {
            label = "rail_edit.automatic_radius.label"
            description = "rail_edit.automatic_radius.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "rail.edit"
            order = 4
        }
        val superelevationEnabled = ConfigKey.build<Boolean>("rail.edit.superelevationEnabled") {
            label = "rail_edit.superelevation.enabled.label"
            description = "rail_edit.superelevation.enabled.description"
            defaultValue(true)
            valueType = ConfigValueType.BOOLEAN
            group = "rail.edit"
            order = 5
        }
    }

    /** 锚点编辑配置键 */
    class AnchorEditKeys {
        val anchorId = ConfigKey.build<String>("anchor.id") {
            label = "anchor_edit.id.label"; defaultValue(""); valueType = ConfigValueType.STRING; group = "anchor.edit"; order = 0
        }
        val posX = ConfigKey.build<String>("anchor.posX") {
            label = "anchor_edit.x.label"; defaultValue("0.000"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 1
        }
        val posY = ConfigKey.build<String>("anchor.posY") {
            label = "anchor_edit.y.label"; defaultValue("64.000"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 2
        }
        val posZ = ConfigKey.build<String>("anchor.posZ") {
            label = "anchor_edit.z.label"; defaultValue("0.000"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 3
        }
        val tangentYaw = ConfigKey.build<String>("anchor.yaw") {
            label = "anchor_edit.yaw.label"; defaultValue("0.0"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 4
        }
        val tangentPitch = ConfigKey.build<String>("anchor.pitch") {
            label = "anchor_edit.pitch.label"; defaultValue("0.0"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 5
        }
        val roll = ConfigKey.build<String>("anchor.roll") {
            label = "anchor_edit.roll.label"; defaultValue("0.0"); valueType = ConfigValueType.STRING
            constraints = ConfigConstraints.StringLength(1, 32); group = "anchor.edit"; order = 6
        }
        val gradeLocked = ConfigKey.build<Boolean>("anchor.gradeLocked") {
            label = "anchor_edit.grade_locked.label"; description = "anchor_edit.grade_locked.description"
            defaultValue(false); valueType = ConfigValueType.BOOLEAN; group = "anchor.edit"; order = 7
        }
        // 批量编辑模式: 增量字段
        val deltaX = ConfigKey.build<Double>("anchor.deltaX") {
            label = "anchor_edit.delta_x.label"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-100.0, 100.0, 0.5, "m"); group = "anchor.batchEdit"; order = 0
        }
        val deltaY = ConfigKey.build<Double>("anchor.deltaY") {
            label = "anchor_edit.delta_y.label"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-100.0, 100.0, 0.5, "m"); group = "anchor.batchEdit"; order = 1
        }
        val deltaZ = ConfigKey.build<Double>("anchor.deltaZ") {
            label = "anchor_edit.delta_z.label"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-100.0, 100.0, 0.5, "m"); group = "anchor.batchEdit"; order = 2
        }
        val deltaYaw = ConfigKey.build<Double>("anchor.deltaYaw") {
            label = "anchor_edit.delta_yaw.label"; defaultValue(0.0); valueType = ConfigValueType.NUMBER
            constraints = ConfigConstraints.NumberRange(-180.0, 180.0, 1.0, "°"); group = "anchor.batchEdit"; order = 3
        }
    }

    private lateinit var editKeys: AnchorEditKeys
    private lateinit var railEditKeys: RailEditKeys

    private fun registerWandConfig() {
        wandKeys = WandConfigKeys(
            StandardTurnout.entries.associateWith { standard ->
                val geometry = plugin.physicsConfig.turnoutGeometry(standard)
                RailWandI18n.reference(
                    "turnout.option",
                    geometry.number,
                    geometry.radiusMeters.toInt(),
                    geometry.divergingSpeedKmH.toInt()
                )
            }
        )
        editKeys = AnchorEditKeys()
        railEditKeys = RailEditKeys()

        configUI.register(ConfigGroup(
            "wand.settings.general",
            "settings.general.title",
            description = "settings.general.description",
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
            "settings.geometry.title",
            description = "settings.geometry.description",
            items = mutableListOf(
                ConfigItemImpl(wandKeys.curveRadius),
                ConfigItemImpl(wandKeys.automaticCurveRadius),
                ConfigItemImpl(wandKeys.curveTemplate),
                ConfigItemImpl(wandKeys.turnoutStandard),
                ConfigItemImpl(wandKeys.designSpeedKmH),
                ConfigItemImpl(wandKeys.curveSpeedLimitKmH),
                ConfigItemImpl(wandKeys.automaticCurveSpeedLimit),
                ConfigItemImpl(wandKeys.allowableCantDeficiencyMm),
                ConfigItemImpl(wandKeys.superelevationEnabled)
            )
        ))

        // 锚点编辑分组 (单锚点)
        val editGroup = ConfigGroup("anchor.edit", "anchor_edit.title", description = "anchor_edit.description").apply {
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
            label = "rail_edit.title",
            description = "rail_edit.description",
            items = mutableListOf(
                ConfigItemImpl(railEditKeys.designSpeedKmH),
                ConfigItemImpl(railEditKeys.curveSpeedLimitKmH),
                ConfigItemImpl(railEditKeys.automaticCurveSpeedLimit),
                ConfigItemImpl(railEditKeys.curveRadius),
                ConfigItemImpl(railEditKeys.automaticCurveRadius),
                ConfigItemImpl(railEditKeys.superelevationEnabled)
            )
        ))

        // 锚点批量编辑分组 (增量)
        val batchGroup = ConfigGroup(
            "anchor.batchEdit",
            "anchor_batch_edit.title",
            description = "anchor_batch_edit.description"
        ).apply {
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
        state.automaticCurveRadius = session.getValue(wandKeys.automaticCurveRadius)
            ?: (configUI.get(wandKeys.automaticCurveRadius) ?: true)
        state.curveTemplate = session.getValue(wandKeys.curveTemplate)
            ?: (configUI.get(wandKeys.curveTemplate) ?: RailCurveTemplateType.AUTO)
        state.turnoutStandard = session.getValue(wandKeys.turnoutStandard)
            ?: (configUI.get(wandKeys.turnoutStandard) ?: StandardTurnout.N9)
        state.designSpeedKmH = session.getValue(wandKeys.designSpeedKmH)
            ?: (configUI.get(wandKeys.designSpeedKmH) ?: 80.0)
        state.curveSpeedLimitKmH = session.getValue(wandKeys.curveSpeedLimitKmH)
            ?: (configUI.get(wandKeys.curveSpeedLimitKmH) ?: 0.0)
        state.automaticCurveSpeedLimit = session.getValue(wandKeys.automaticCurveSpeedLimit)
            ?: (configUI.get(wandKeys.automaticCurveSpeedLimit) ?: true)
        state.allowableCantDeficiencyMm = session.getValue(wandKeys.allowableCantDeficiencyMm)
            ?: (configUI.get(wandKeys.allowableCantDeficiencyMm) ?: 75.0)
        state.superelevationEnabled = session.getValue(wandKeys.superelevationEnabled)
            ?: (configUI.get(wandKeys.superelevationEnabled) ?: true)
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
        session.setValue(wandKeys.automaticCurveRadius, state.automaticCurveRadius)
        session.setValue(wandKeys.curveTemplate, state.curveTemplate)
        session.setValue(wandKeys.turnoutStandard, state.turnoutStandard)
        session.setValue(wandKeys.designSpeedKmH, state.designSpeedKmH)
        session.setValue(wandKeys.curveSpeedLimitKmH, state.curveSpeedLimitKmH)
        session.setValue(wandKeys.automaticCurveSpeedLimit, state.automaticCurveSpeedLimit)
        session.setValue(wandKeys.allowableCantDeficiencyMm, state.allowableCantDeficiencyMm)
        session.setValue(wandKeys.superelevationEnabled, state.superelevationEnabled)
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
            player.sendActionBar(RailWandI18n.component(player, "message.right_click_block").color(NamedTextColor.RED))
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
            player.sendActionBar(Component.text(
                error.message?.let { RailWandI18n.resolve(player, it) }
                    ?: RailWandI18n.format(player, "message.two_point_invalid"),
                NamedTextColor.RED
            ))
            return
        }
        val newAnchorIds = newAnchors.map { it.id }

        val undoActions = newAnchors.map { WandAction.CreateAnchor(player.world.uid, it) }
        pushUndo(player, if (undoActions.size == 1) undoActions.single() else WandAction.MultiAction(undoActions))

        player.sendActionBar(RailWandI18n.component(
            player,
            "message.anchor_created",
            newAnchors.size,
            newAnchorIds.joinToString(", ")
        ).color(NamedTextColor.GREEN))

        val expectedCount = state.parallelCount * 2
        if (state.selectedAnchors.isNotEmpty()) {
            state.selectedAnchors.addAll(newAnchorIds)
            if (state.selectedAnchors.size >= expectedCount) {
                connectSelectedAnchors(player, state)
            } else {
                player.sendActionBar(RailWandI18n.component(
                    player,
                    "message.anchor_progress",
                    state.selectedAnchors.size,
                    expectedCount
                ).color(NamedTextColor.YELLOW))
            }
        } else {
            state.selectedAnchors.addAll(newAnchorIds)
        }

        updateAnchorDisplay(player)

        // 可选：打开 Config Dialog
        if (state.openConfigAfterCreate) {
            player.sendMessage(RailWandI18n.component(
                player,
                "message.create_config_unavailable"
            ).color(NamedTextColor.GRAY))
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
                ?: throw RailWandLocalizedException("failure.two_point_endpoint_tangent", start.id)
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
        val automaticCurveRadius: Boolean,
        val curveTemplate: RailCurveTemplateType,
        val designSpeedKmH: Double,
        val curveSpeedLimitKmH: Double,
        val automaticCurveSpeedLimit: Boolean,
        val curveSpeedLimitIsCreationMinimum: Boolean = true,
        val allowableCantDeficiencyMm: Double,
        val superelevationEnabled: Boolean,
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

    private interface PreparedRailUndoEdit {
        val topologyEditPlan: RailWandTopologyEditPlan
        val prepareUndo: () -> PreparedRailWorldUndo
    }

    /** Curve-build output paired with one Core topology root before returning to the owner thread. */
    private data class PreparedRailBuild<T>(
        val value: T,
        val railCreation: PreparedRailCreationBatch,
        override val topologyEditPlan: RailWandTopologyEditPlan,
        override val prepareUndo: () -> PreparedRailWorldUndo
    ) : PreparedRailUndoEdit

    private data class PreparedRailDelete(
        override val topologyEditPlan: RailWandTopologyEditPlan,
        override val prepareUndo: () -> PreparedRailWorldUndo
    ) : PreparedRailUndoEdit

    private data class PreparedSplitBuild(
        val curves: Pair<PrimeCurve, PrimeCurve>,
        val operationId: String
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.turnout_direction_invalid"
            ).color(NamedTextColor.RED))
            return
        }
        val decorationModel = if (state.autoDecorate) selectedDecorationModel(player, state) ?: return else null
        val decorationTemplate = decorationModel?.let(RailVisualBootstrap::captureTemplateSnapshot)
        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.turnout_create")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }
        val worldId = player.world.uid
        val expectedCoreWorld = getCoreWorld(player) ?: run {
            heavyOperationGate.finish(player.uniqueId)
            return
        }
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
                    val built = result to RailSpeedCalculator.calculate(
                        curve = result.curve,
                        requestedLimitMetersPerSecond = turnoutGeometry.divergingSpeedKmH / 3.6,
                        allowableCantDeficiencyMeters = cantDeficiencyMeters,
                        derailLateralAccelerationMetersPerSecondSquared = derailAcceleration,
                        physics = plugin.physicsConfig.railSpeedPhysics()
                    )
                    val railCreation = expectedCoreWorld.prepareRailCreation(result.curve)
                    val createdEndpointA = RailEndpointRef(
                        railCreation.creations.single().railId,
                        RailEndpoint.A
                    )
                    val selectedExistingEndpoint = parseRailEndpointAnchorId(anchor.id)?.let { (railId, end) ->
                        RailEndpointRef(railId, if (end == 'A') RailEndpoint.A else RailEndpoint.B)
                    }
                    val editPlan = capturePreparedExplicitConnectionPlan(
                        expectedCoreWorld,
                        railCreation,
                        emptyMap(),
                        selectedExistingEndpoint?.let { listOf(it to createdEndpointA) }.orEmpty()
                    )
                    val railId = railCreation.creations.single().railId
                    val metadata = RailMetadata(
                        designSpeedMetersPerSecond = turnoutGeometry.divergingSpeedKmH / 3.6,
                        speedLimitMetersPerSecond = built.second.operatingLimitMetersPerSecond,
                        derailSpeedMetersPerSecond = built.second.derailSpeedMetersPerSecond,
                        geometry = RailGeometryMetadata(
                            curveKind = RailCurveKind.FIVE_SEGMENT,
                            curveRadiusMeters = turnoutGeometry.radiusMeters,
                            automaticSuperelevation = false,
                            allowableCantDeficiencyMeters = cantDeficiencyMeters
                        )
                    )
                    val prepareUndo = {
                        PreparedRailWorldUndoBuilder.capture(
                            worldId,
                            worldId.toString(),
                            expectedCoreWorld,
                            editPlan
                        ).also { builder ->
                            builder.putMetadata(railId, metadata)
                            decorationTemplate?.let { builder.prepareRender(railId, it) }
                        }.build()
                    }
                    PreparedRailBuild(built, railCreation, editPlan, prepareUndo)
                }
            },
            onSuccess = { builtTurnout ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                if (builtTurnout == null || getAnchor(livePlayer.world, anchor.id) != anchor) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.anchor_changed_retry"
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                val (result, speedLimits) = builtTurnout.value
                val coreWorld = getCoreWorld(livePlayer) ?: return@supply
                if (coreWorld !== expectedCoreWorld) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                val editPlan = builtTurnout.topologyEditPlan
                prepareUndoAndCommit(
                    player = livePlayer,
                    worldId = worldId,
                    expectedWorld = coreWorld,
                    edit = builtTurnout,
                    operation = "RailWand.createTurnoutAtAnchor.prepareUndo",
                    commit = { preparedPlayer, undo ->
                    val undoSnapshot = undo.before
                    var createdRail: CoreRail? = null
                    editPlan.commitTopology(coreWorld) {
                        requirePreparedExplicitEditReady(coreWorld, editPlan)
                        val installed = coreWorld.installPreparedRails(builtTurnout.railCreation)
                        createdRail = installed.single().also { rail ->
                            rail.metadata = undo.expectedAfter.railMetadata.getValue(rail.id)
                        }
                        editPlan.applyExplicitConnections(coreWorld, installed)
                        val rail = requireNotNull(createdRail)
                        undo.expectedAfter.renderMetas.singleOrNull { it.railId == rail.id }?.let { meta ->
                            RailVisualBootstrap.fromPersisted(coreWorld, meta)
                        }
                        undo.publishRenderSidecar()
                        pushUndo(preparedPlayer, WandAction.RestoreRailWorld(
                                preparedPlayer.world.uid,
                                undoSnapshot,
                                undo.expectedAfter
                        ))
                        val liveState = getState(preparedPlayer)
                        liveState.selectedAnchors.clear()
                        liveState.selectedAnchors.add(railEndpointAnchorId(rail.id, 'B'))
                        persistState(preparedPlayer, liveState)
                    }
                    updateAnchorDisplay(preparedPlayer)
                    preparedPlayer.sendMessage(RailWandI18n.component(
                        preparedPlayer,
                        "message.turnout_created",
                        standard.number,
                        (speedLimits.operatingLimitMetersPerSecond * 3.6).toInt(),
                        if (parseRailEndpointAnchorId(anchor.id) == null) 0 else 1
                    ).color(NamedTextColor.GREEN))
                    },
                    failure = { failedPlayer, failure ->
                        plugin.logger.warning(
                            "RailWand turnout commit failed: ${failure.message ?: failure::class.simpleName}"
                        )
                        failedPlayer?.sendMessage(RailWandI18n.component(
                            failedPlayer,
                            "message.turnout_create_failed",
                            railWandFailureReason(failure)
                        ).color(NamedTextColor.RED))
                    }
                )
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("RailWand turnout build failed: ${error.message ?: error::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.turnout_create_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
            },
            onRetired = { heavyOperationGate.finish(player.uniqueId) }
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
            curveRadius = if (state.automaticCurveRadius) 0.0 else state.curveRadius,
            automaticCurveRadius = state.automaticCurveRadius,
            curveTemplate = state.curveTemplate,
            designSpeedKmH = state.designSpeedKmH,
            curveSpeedLimitKmH = state.curveSpeedLimitKmH,
            automaticCurveSpeedLimit = state.automaticCurveSpeedLimit,
            curveSpeedLimitIsCreationMinimum = true,
            allowableCantDeficiencyMm = state.allowableCantDeficiencyMm,
            superelevationEnabled = state.superelevationEnabled,
            autoDecorate = state.autoDecorate,
            decorationModel = state.decorationModel
        )
    }

    /** 连接已选择的锚点组，使用 ConstraintSolver 创建曲线 → CoreRail → 渲染 */
    internal fun connectSelectedAnchors(player: Player, state: WandPlayerState) {
        if (!state.automaticCurveRadius && state.curveRadius <= 0.0) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.manual_radius_positive"
            ).color(NamedTextColor.RED))
            return
        }
        if (!state.automaticCurveSpeedLimit && state.curveSpeedLimitKmH <= 0.0) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.minimum_curve_speed_positive"
            ).color(NamedTextColor.RED))
            return
        }
        if (state.mode != WandMode.CREATE && state.curveTemplate == RailCurveTemplateType.TWO_POINT_STRAIGHT) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.two_point_create_only"
            ).color(NamedTextColor.RED))
            return
        }
        val expectedCount = state.parallelCount * 2
        if (state.selectedAnchors.size != expectedCount) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.required_anchor_count",
                expectedCount,
                state.selectedAnchors.size
            ).color(NamedTextColor.RED))
            return
        }

        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.world_unavailable"
            ).color(NamedTextColor.RED))
            return
        }

        val anchors = state.selectedAnchors.mapNotNull { getAnchor(player.world, it) }
        if (anchors.size != expectedCount) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.some_anchors_missing"
            ).color(NamedTextColor.RED))
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.adjacent_duplicate"
            ).color(NamedTextColor.RED))
            return
        }
        if (state.autoDecorate && selectedDecorationModel(player, state) == null) return

        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.rail_connect")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }

        val selectionSnapshot = state.selectedAnchors.toList()
        val anchorSnapshotsById = anchors.associateBy { it.id }
        val settingsSnapshot = snapshotCurveBuildSettings(state)
        val connectionDecorationTemplate = settingsSnapshot.decorationModel.takeIf { settingsSnapshot.autoDecorate }
            ?.let(RailVisualBootstrap::captureTemplateSnapshot)
        val worldId = player.world.uid
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.generating_curves"
        ).color(NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.connectSelectedAnchors",
            supplier = {
                val build = createConnectionCurves(
                    startAnchors,
                    endAnchors,
                    startAdjacent,
                    endAdjacent,
                    settingsSnapshot
                ) ?: return@supply null
                val curves = build.replacementCurves.values.map(BuiltCurve::curve) +
                    build.newLanes.flatten().map(BuiltRailPart::curve)
                val railCreation = coreWorld.prepareRailCreationBatch(curves, build.replacementCurves.keys)
                val preparedIds = railCreation.creations.map { it.railId }
                val replacementIds = preparedIds.take(build.replacementCurves.size)
                val replacementEndpoints = build.replacementCurves.keys.zip(replacementIds)
                    .flatMap { (oldId, newId) -> listOf(
                        railEndpointRef(oldId, 'A') to railEndpointRef(newId, 'A'),
                        railEndpointRef(oldId, 'B') to railEndpointRef(newId, 'B')
                    ) }
                    .toMap()
                val laneIds = buildList {
                    val iterator = preparedIds.drop(replacementIds.size).iterator()
                    build.newLanes.forEach { lane -> add(List(lane.size) { iterator.next() }) }
                    check(!iterator.hasNext()) { "Prepared rail batch does not match connection lanes" }
                }
                val explicitConnections = buildList {
                    laneIds.forEachIndexed { laneIndex, ids ->
                        ids.zipWithNext().forEach { (first, second) ->
                            add(railEndpointRef(first, 'B') to railEndpointRef(second, 'A'))
                        }
                        parseRailEndpointRef(startAnchors[laneIndex].id)?.let { selected ->
                            add(selected to railEndpointRef(ids.first(), 'A'))
                        }
                        parseRailEndpointRef(endAnchors[laneIndex].id)?.let { selected ->
                            add(railEndpointRef(ids.last(), 'B') to selected)
                        }
                    }
                }
                val editPlan = capturePreparedExplicitConnectionPlan(
                    coreWorld, railCreation, replacementEndpoints, explicitConnections
                )
                val prepareUndo = {
                    val undoBuilder = PreparedRailWorldUndoBuilder.capture(
                        worldId, worldId.toString(), coreWorld, editPlan
                    )
                    val oldToNew = build.replacementCurves.keys.zip(replacementIds).toMap()
                    undoBuilder.migrateMappedRailReplacement(oldToNew)
                    build.replacementCurves.entries.zip(replacementIds).forEach { (entry, newId) ->
                        undoBuilder.putMetadata(
                            newId,
                            undoBuilder.metadataFor(entry.key).copy(
                                speedLimitMetersPerSecond = entry.value.speedLimits.operatingLimitMetersPerSecond,
                                derailSpeedMetersPerSecond = entry.value.speedLimits.derailSpeedMetersPerSecond
                            )
                        )
                    }
                    val segmentCount = build.newLanes.firstOrNull()?.size ?: 0
                    val assemblyGroupId = UUID.randomUUID().toString().takeIf { segmentCount > 1 }
                    val parallelGroupIds = List(segmentCount) {
                        UUID.randomUUID().toString().takeIf { build.newLanes.size > 1 }
                    }
                    build.newLanes.forEachIndexed { laneIndex, parts ->
                        parts.forEachIndexed { segmentIndex, part ->
                            val railId = laneIds[laneIndex][segmentIndex]
                            val parallel = parallelGroupIds[segmentIndex]?.let {
                                ParallelRailMetadata(
                                    it, laneIndex, build.newLanes.size, settingsSnapshot.parallelSpacing
                                )
                            }
                            val assembly = assemblyGroupId?.let {
                                RailAssemblyMetadata(
                                    it,
                                    RailAssemblyKind.FIVE_SEGMENT_CURVE,
                                    laneIndex,
                                    build.newLanes.size,
                                    segmentIndex,
                                    segmentCount,
                                    part.role
                                )
                            }
                            undoBuilder.putMetadata(
                                railId,
                                railMetadata(settingsSnapshot, part.speedLimits, parallel).copy(
                                    assembly = assembly,
                                    gradeLockedAtA = segmentIndex == 0 && isGradeLocked(startAnchors[laneIndex]),
                                    gradeLockedAtB = segmentIndex == segmentCount - 1 && isGradeLocked(endAnchors[laneIndex])
                                )
                            )
                            connectionDecorationTemplate?.let { undoBuilder.prepareRender(railId, it) }
                        }
                    }
                    undoBuilder.removeRails(oldToNew.keys)
                    undoBuilder.build()
                }
                PreparedRailBuild(
                    build, railCreation, editPlan,
                    prepareUndo
                )
            },
            onSuccess = { preparedConnectionBuild ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveCoreWorld = getCoreWorld(livePlayer) ?: run {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.world_unavailable"
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                val liveState = getState(livePlayer)
                if (liveState.selectedAnchors != selectionSnapshot) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.selection_changed_cancel_create"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                val anchorsStillSame = selectionSnapshot.all { anchorId ->
                    getAnchor(livePlayer.world, anchorId) == anchorSnapshotsById[anchorId]
                }
                if (!anchorsStillSame) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.anchors_changed_retry_connect"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                if (preparedConnectionBuild == null) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.curve_build_impossible"
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                if (liveCoreWorld !== coreWorld) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                val connectionBuild = preparedConnectionBuild.value
                val builtLanes = connectionBuild.newLanes
                val liveAdjacentRails = connectionBuild.replacementCurves.keys.associateWith { railId ->
                    val rail = liveCoreWorld.getRail(railId) ?: run {
                        livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.adjacent_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                        return@supply
                    }
                    val expected = (startAdjacent + endAdjacent).first { it?.railId == railId }!!
                    if (rail.curve !== expected.originalCurve || rail.trains.isNotEmpty()) {
                        livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.adjacent_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                        return@supply
                    }
                    rail
                }
                if (liveAdjacentRails.isNotEmpty()) {
                    splitSafetyError(liveCoreWorld, liveAdjacentRails.values.toList())?.let { error ->
                        livePlayer.sendActionBar(Component.text(
                RailWandI18n.resolve(livePlayer, error),
                NamedTextColor.RED
            ))
                        return@supply
                    }
                }

                val preparedIds = preparedConnectionBuild.railCreation.creations.map { it.railId }
                val replacementIds = preparedIds.take(connectionBuild.replacementCurves.size)
                val replacementEndpoints = connectionBuild.replacementCurves.keys.zip(replacementIds)
                    .flatMap { (oldId, newId) -> listOf(
                        railEndpointRef(oldId, 'A') to railEndpointRef(newId, 'A'),
                        railEndpointRef(oldId, 'B') to railEndpointRef(newId, 'B')
                    ) }
                    .toMap()
                val laneIds = buildList {
                    val iterator = preparedIds.drop(replacementIds.size).iterator()
                    builtLanes.forEach { lane -> add(List(lane.size) { iterator.next() }) }
                    check(!iterator.hasNext()) { "Prepared rail batch does not match connection lanes" }
                }
                val explicitConnections = buildList {
                    laneIds.forEachIndexed { laneIndex, ids ->
                        ids.zipWithNext().forEach { (first, second) ->
                            add(railEndpointRef(first, 'B') to railEndpointRef(second, 'A'))
                        }
                        parseRailEndpointRef(startAnchors[laneIndex].id)?.let { selected ->
                            add(selected to railEndpointRef(ids.first(), 'A'))
                        }
                        parseRailEndpointRef(endAnchors[laneIndex].id)?.let { selected ->
                            add(railEndpointRef(ids.last(), 'B') to selected)
                        }
                    }
                }
                val editPlan = preparedConnectionBuild.topologyEditPlan
                prepareUndoAndCommit(
                player = livePlayer,
                worldId = worldId,
                expectedWorld = liveCoreWorld,
                edit = preparedConnectionBuild,
                operation = "RailWand.connectSelectedAnchors.prepareUndo",
                commit = { _, undo ->
                val undoSnapshot = undo.before
                val createdRails = mutableListOf<CoreRail>()
                val newRails = mutableListOf<CoreRail>()
                val preparedRenderByRail = undo.renderAdditionsByRail
                val oldToNew = linkedMapOf<UUID, UUID>()
                val existingConnectionSnapshots = editPlan.explicitConnectionAffectedExistingRailIds
                    .mapNotNull(liveCoreWorld::getRail)
                    .map(::snapshotRailConnections)
                var destinationsMigrated = false
                editPlan.commitTopology(liveCoreWorld) {
                    try {
                    requirePreparedExplicitEditReady(liveCoreWorld, editPlan)
                    val installedRails = liveCoreWorld.installPreparedRails(preparedConnectionBuild.railCreation)
                    val preparedRails = installedRails.iterator()
                    connectionBuild.replacementCurves.forEach { (oldId, _) ->
                        val oldRail = liveAdjacentRails.getValue(oldId)
                        val replacement = preparedRails.next().also { rail ->
                            rail.metadata = undo.expectedAfter.railMetadata.getValue(rail.id)
                        }
                        createdRails += replacement
                        oldToNew[oldId] = replacement.id
                        preparedRenderByRail[replacement.id]?.let { meta ->
                            RailVisualBootstrap.fromPersisted(liveCoreWorld, meta)
                        }
                    }

                    val segmentCount = builtLanes.firstOrNull()?.size ?: 0
                    if (segmentCount <= 0 || builtLanes.any { it.size != segmentCount }) {
                        throw RailWandLocalizedException("failure.parallel_internal_segment_mismatch")
                    }
                    val newLaneRails = builtLanes.mapIndexed { laneIndex, parts ->
                        parts.mapIndexed { segmentIndex, part ->
                            preparedRails.next().also { rail ->
                                rail.metadata = undo.expectedAfter.railMetadata.getValue(rail.id)
                                createdRails += rail
                                newRails += rail
                            }
                        }
                    }
                    check(!preparedRails.hasNext()) { "Prepared rail batch does not match connection build" }
                    newLaneRails.flatten().forEach { rail ->
                        preparedRenderByRail[rail.id]?.let { meta ->
                            RailVisualBootstrap.fromPersisted(liveCoreWorld, meta)
                        }
                    }

                    editPlan.applyExplicitConnections(liveCoreWorld, installedRails)
                    undo.publishRenderSidecar()
                    if (oldToNew.isNotEmpty()) {
                        OperationalDestinationService(liveCoreWorld).migrateAllOnRails(oldToNew)
                        migrateOperatingDirections(liveCoreWorld, oldToNew)
                        destinationsMigrated = true
                    }
                    liveAdjacentRails.values.forEach { oldRail ->
                        RailVisualBootstrap.unregisterRail(oldRail.id)
                        oldRail.destroy()
                        liveCoreWorld.removeRail(oldRail)
                        liveCoreWorld.basicWorldData.railMetadata.remove(oldRail.id)
                    }

                    discardCreatedAnchorOperations(livePlayer, selectionSnapshot.toSet())
                    pushUndo(livePlayer, WandAction.RestoreRailWorld(
                        livePlayer.world.uid,
                        undoSnapshot,
                        undo.expectedAfter
                    ))
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.rails_created",
                        builtLanes.size,
                        newRails.size,
                        oldToNew.size,
                        explicitConnections.size
                    ).color(NamedTextColor.GREEN))
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
                    restoreOperatingDirections(liveCoreWorld, undoSnapshot.operatingDirections)
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
                    throw e
                    }
                }
                },
                failure = { failedPlayer, error ->
                    plugin.logger.warning(
                        "RailWand connect commit failed: ${error.message ?: error::class.simpleName}"
                    )
                    failedPlayer?.sendMessage(RailWandI18n.component(
                        failedPlayer,
                        "message.rail_create_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
                )
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning(
                    "RailWand connect failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.rail_connect_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
            },
            onRetired = { heavyOperationGate.finish(player.uniqueId) }
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.world_unavailable"
            ).color(NamedTextColor.RED))
            return
        }
        val editedRail = coreWorld.getRail(editedRailId) ?: run {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.rail_missing",
                editedRailId
            ).color(NamedTextColor.RED))
            return
        }
        val movedPoint = if (editedEnd == 'A') editedRail.viewAB.startPoint.position else editedRail.viewAB.endPoint.position
        val affectedRails = coreWorld.allRails.filter { rail ->
            rail.viewAB.startPoint.position.distance(movedPoint) <= Curve.TOLERANCE ||
                rail.viewAB.endPoint.position.distance(movedPoint) <= Curve.TOLERANCE
        }
        if (affectedRails.isEmpty()) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.no_endpoint_rails"
            ).color(NamedTextColor.RED))
            return
        }
        if (affectedRails.any { it.trains.isNotEmpty() }) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.occupied_edit_forbidden"
            ).color(NamedTextColor.RED))
            return
        }
        splitSafetyError(coreWorld, affectedRails)?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        if (affectedRails.any(TurnoutTopology::hasTurnoutEndpoint)) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.turnout_geometry_forbidden"
            ).color(NamedTextColor.RED))
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.endpoint_plan_incomplete"
            ).color(NamedTextColor.RED))
            return
        }

        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.endpoint_rebuild")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }

        val settingsSnapshot = snapshotCurveBuildSettings(state)
        val worldId = player.world.uid
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.rebuilding_rail"
        ).color(NamedTextColor.GRAY))

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
                            superelevationEnabled = false
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
                val railCreation = coreWorld.prepareRailCreationBatch(
                        curvesByRailId.values.map(BuiltCurve::curve),
                        curvesByRailId.keys
                    )
                val replacementEndpoints = rebuildInputs
                    .zip(railCreation.creations)
                    .flatMap { (input, prepared) -> listOf(
                        railEndpointRef(input.railId, 'A') to railEndpointRef(prepared.railId, 'A'),
                        railEndpointRef(input.railId, 'B') to railEndpointRef(prepared.railId, 'B')
                    ) }
                    .toMap()
                val editPlan = capturePreparedExplicitConnectionPlan(
                    coreWorld, railCreation, replacementEndpoints, emptyList()
                )
                val oldToNew = rebuildInputs.zip(railCreation.creations)
                    .associate { (input, creation) -> input.railId to creation.railId }
                val newMetadata = rebuildInputs.zip(railCreation.creations).associate { (input, creation) ->
                    val builtCurve = curvesByRailId.getValue(input.railId)
                    creation.railId to input.oldMetadata.copy(
                        speedLimitMetersPerSecond = builtCurve.speedLimits.operatingLimitMetersPerSecond,
                        derailSpeedMetersPerSecond = builtCurve.speedLimits.derailSpeedMetersPerSecond,
                        gradeLockedAtA = if (input.movedEnd == 'A') isGradeLocked(updatedEndpoint)
                        else input.oldMetadata.gradeLockedAtA,
                        gradeLockedAtB = if (input.movedEnd == 'B') isGradeLocked(updatedEndpoint)
                        else input.oldMetadata.gradeLockedAtB
                    )
                }
                val prepareUndo = {
                    val undoBuilder = PreparedRailWorldUndoBuilder.capture(
                        worldId, worldId.toString(), coreWorld, editPlan
                    )
                    undoBuilder.migrateMappedRailReplacement(oldToNew)
                    undoBuilder.removeRails(oldToNew.keys)
                    newMetadata.forEach(undoBuilder::putMetadata)
                    undoBuilder.build()
                }
                PreparedRailBuild(
                    curvesByRailId, railCreation, editPlan,
                    prepareUndo
                )
            },
            onSuccess = { preparedRebuild ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveCoreWorld = getCoreWorld(livePlayer) ?: run {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.world_unavailable"
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                if (preparedRebuild == null) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.endpoint_curve_impossible"
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                if (liveCoreWorld !== coreWorld) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                val curvesByRailId = preparedRebuild.value

                val liveAffectedRails = rebuildInputs.map { input ->
                    val rail = liveCoreWorld.getRail(input.railId) ?: run {
                        livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                        return@supply
                    }
                    val currentStart = anchorFromRailEndpoint(livePlayer.world, rail, 'A')
                    val currentEnd = anchorFromRailEndpoint(livePlayer.world, rail, 'B')
                    if (currentStart != input.oldStart || currentEnd != input.oldEnd) {
                        livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.endpoint_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                        return@supply
                    }
                    rail
                }
                if (liveAffectedRails.any { it.trains.isNotEmpty() }) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.occupied_edit_forbidden"
                    ).color(NamedTextColor.RED))
                    return@supply
                }

                val editPlan = preparedRebuild.topologyEditPlan
                prepareUndoAndCommit(
                player = livePlayer,
                worldId = worldId,
                expectedWorld = liveCoreWorld,
                edit = preparedRebuild,
                operation = "RailWand.rebuildRailForEndpointEdit.prepareUndo",
                commit = { _, undo ->
                val undoSnapshot = undo.before
                val snapshots = editPlan.explicitConnectionAffectedExistingRailIds
                    .mapNotNull(liveCoreWorld::getRail)
                    .map(::snapshotRailConnections)
                val createdRails = mutableListOf<CoreRail>()
                val preparedRenderByRail = undo.renderAdditionsByRail
                val oldToNew = mutableMapOf<UUID, CoreRail>()
                editPlan.commitTopology(liveCoreWorld) {
                    try {
                    requirePreparedExplicitEditReady(liveCoreWorld, editPlan)
                    val installedRails = liveCoreWorld.installPreparedRails(preparedRebuild.railCreation)
                    val preparedRails = installedRails.iterator()
                    rebuildInputs.forEach { input ->
                        val newRail = preparedRails.next().also { rail ->
                            rail.metadata = undo.expectedAfter.railMetadata.getValue(rail.id)
                        }
                        createdRails.add(newRail)
                        preparedRenderByRail[newRail.id]?.let { meta ->
                            RailVisualBootstrap.fromPersisted(liveCoreWorld, meta)
                        }
                        oldToNew[input.railId] = newRail
                    }
                    check(!preparedRails.hasNext()) { "Prepared rail batch does not match endpoint rebuild" }

                    undo.publishRenderSidecar()

                    editPlan.applyExplicitConnections(liveCoreWorld, installedRails)

                    OperationalDestinationService(liveCoreWorld).migrateAllOnRails(
                        oldToNew.mapValues { (_, replacement) -> replacement.id }
                    )
                    migrateOperatingDirections(
                        liveCoreWorld,
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
                    pushUndo(livePlayer, WandAction.RestoreRailWorld(
                        livePlayer.world.uid,
                        undoSnapshot,
                        undo.expectedAfter
                    ))
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.endpoint_updated"
                    ).color(NamedTextColor.GREEN))
                    } catch (e: Exception) {
                    restoreOperatingDirections(liveCoreWorld, undoSnapshot.operatingDirections)
                    createdRails.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        rail.destroy()
                        liveCoreWorld.removeRail(rail)
                        liveCoreWorld.basicWorldData.railMetadata.remove(rail.id)
                    }
                    restoreRailConnections(snapshots)
                    undoSnapshot.renderMetas.forEach { RailVisualBootstrap.fromPersisted(liveCoreWorld, it) }
                    throw e
                    }
                }
                },
                failure = { failedPlayer, error ->
                    plugin.logger.warning(
                        "RailWand endpoint rebuild commit failed: ${error.message ?: error::class.simpleName}"
                    )
                    failedPlayer?.sendMessage(RailWandI18n.component(
                        failedPlayer,
                        "message.endpoint_rebuild_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
                )
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning(
                    "RailWand endpoint rebuild failed for player ${player.name}: ${error::class.simpleName}: ${error.message ?: ""}"
                )
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.endpoint_rebuild_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
            },
            onRetired = { heavyOperationGate.finish(player.uniqueId) }
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

    private fun capturePreparedExplicitConnectionPlan(
        world: CoreWorld,
        preparedBatch: PreparedRailCreationBatch,
        replacementEndpoints: Map<RailEndpointRef, RailEndpointRef>,
        addedConnections: Collection<Pair<RailEndpointRef, RailEndpointRef>>
    ): RailWandTopologyEditPlan {
        val explicitConnections = RailWandExplicitConnections.capture(
            world,
            preparedBatch,
            replacementEndpoints,
            addedConnections
        )
        val plan = RailWandTopologyEditPlan.capturePreparedExplicitConnections(
            world,
            preparedBatch,
            explicitConnections
        )
        return plan
    }

    private fun requirePreparedExplicitEditReady(
        world: CoreWorld,
        plan: RailWandTopologyEditPlan
    ) {
        plan.requireReadyToApply(world)
    }

    private fun railEndpointRef(railId: UUID, end: Char): RailEndpointRef = RailEndpointRef(
        railId,
        if (end == 'A') RailEndpoint.A else RailEndpoint.B
    )

    private fun parseRailEndpointRef(anchorId: String): RailEndpointRef? =
        parseRailEndpointAnchorId(anchorId)?.let { (railId, end) -> railEndpointRef(railId, end) }

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
                .joinToString("; ")
                .ifBlank { RailWandI18n.reference("failure.curve_build") }
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
                .joinToString("; ")
                .ifBlank { RailWandI18n.reference("failure.parallel_curve_build") }
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
                if (replacements.put(end.railId, BuiltCurve(
                        canonical,
                        calculateSpeedLimits(canonical, end.metadata, settings)
                    )) != null
                ) {
                    throw RailWandLocalizedException("failure.adjacent_rebuilt_twice")
                }
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
        val automatic = RailSpeedCalculator.calculate(
            curve = curve,
            requestedLimitMetersPerSecond = Double.MAX_VALUE,
            allowableCantDeficiencyMeters = metadata?.geometry?.allowableCantDeficiencyMeters
                ?: settings.allowableCantDeficiencyMm / 1000.0,
            derailLateralAccelerationMetersPerSecondSquared =
                plugin.physicsConfig.overspeed.derailLateralAccelerationMetersPerSecondSquared,
            physics = plugin.physicsConfig.railSpeedPhysics()
        )
        if (automatic.derailSpeedMetersPerSecond == null) {
            return RailSpeedLimits(settings.designSpeedKmH / 3.6, null)
        }

        val automaticMode = metadata?.authoring?.curveSpeedLimitAutomatic
            ?: settings.automaticCurveSpeedLimit
        val requestedMetersPerSecond = if (metadata != null) {
            metadata.speedLimits.value(SpeedLimitType.CURVE)
        } else {
            settings.curveSpeedLimitKmH.takeIf { it > 0.0 }?.div(3.6)
        }
        val resolved = resolveCurveOperatingLimitMetersPerSecond(
            automaticLimit = automatic.operatingLimitMetersPerSecond,
            requestedLimit = requestedMetersPerSecond,
            automatic = automaticMode,
            creationMinimum = metadata == null && settings.curveSpeedLimitIsCreationMinimum
        )
        return automatic.copy(operatingLimitMetersPerSecond = resolved)
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
            superelevation = railWandSuperelevationConfig(
                enabled = settings.superelevationEnabled,
                designSpeedKmH = settings.designSpeedKmH,
                balanceSpeedOffsetKmH = physics.track.cantBalanceSpeedOffsetKmH,
                maximumCantGradient = physics.track.maximumCantGradient,
                gaugeMeters = physics.track.gaugeMeters,
                gravityMetersPerSecondSquared = physics.track.gravityMetersPerSecondSquared
            ),
            geometry = physics.railGeometryParameters()
        )
    }

    private fun railMetadata(
        settings: CurveBuildSettingsSnapshot,
        speedLimits: RailSpeedLimits,
        parallel: ParallelRailMetadata? = null
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
                    automaticSuperelevation = settings.superelevationEnabled,
                    maxSuperelevationMeters = if (settings.superelevationEnabled) {
                        RAIL_WAND_MAX_SUPERELEVATION_METERS
                    } else {
                        0.0
                    },
                    allowableCantDeficiencyMeters = settings.allowableCantDeficiencyMm / 1000.0,
                    parallel = parallel
                ),
                curveSpeedLimitAutomatic = settings.automaticCurveSpeedLimit
            ),
            operating = RailOperatingMetadata(),
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.complete_parallel_sections"
            ).color(NamedTextColor.RED))
            return
        }
        val world = getCoreWorld(player) ?: return
        val rawGroups = state.selectedAnchors.chunked(count).map { ids ->
            ids.map { id -> getAnchor(player.world, id) ?: run {
                player.sendActionBar(RailWandI18n.component(
                player,
                "message.selected_anchor_missing"
            ).color(NamedTextColor.RED))
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
                player.sendActionBar(RailWandI18n.component(
                player,
                "message.grade_chain_not_unique"
            ).color(NamedTextColor.RED))
                return
            }
            chains += chain
        }
        val affectedIds = chains.flatten().map { it.rail.id }
        if (affectedIds.size != affectedIds.distinct().size) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.parallel_path_invalid"
            ).color(NamedTextColor.RED))
            return
        }
        if (chains.map(List<GradeChainRail>::size).distinct().size != 1) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.parallel_segment_mismatch"
            ).color(NamedTextColor.RED))
            return
        }
        if (chains.flatten().any { it.rail.trains.isNotEmpty() }) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.occupied_smooth_forbidden"
            ).color(NamedTextColor.RED))
            return
        }

        val pending = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.grade_smooth")
        )
        if (pending != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pending
            ).color(NamedTextColor.YELLOW))
            return
        }
        val settings = snapshotCurveBuildSettings(state)
        val worldId = player.world.uid
        val selectedSnapshot = state.selectedAnchors.toList()
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.solving_grade"
        ).color(NamedTextColor.GRAY))
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
                val railCreation = world.prepareRailCreationBatch(
                    rebuilt.values.map(BuiltCurve::curve),
                    rebuilt.keys
                )
                val replacementEndpoints = rebuilt.keys.zip(railCreation.creations)
                    .flatMap { (oldId, prepared) -> listOf(
                        railEndpointRef(oldId, 'A') to railEndpointRef(prepared.railId, 'A'),
                        railEndpointRef(oldId, 'B') to railEndpointRef(prepared.railId, 'B')
                    ) }
                    .toMap()
                val editPlan = capturePreparedExplicitConnectionPlan(
                    world, railCreation, replacementEndpoints, emptyList()
                )
                val oldToNew = rebuilt.keys.zip(railCreation.creations)
                    .associate { (oldId, creation) -> oldId to creation.railId }
                val prepareUndo = {
                    val undoBuilder = PreparedRailWorldUndoBuilder.capture(
                        worldId, worldId.toString(), world, editPlan
                    )
                    undoBuilder.migrateMappedRailReplacement(oldToNew)
                    rebuilt.entries.zip(railCreation.creations).forEach { (entry, creation) ->
                        val old = entry.key
                        val built = entry.value
                        undoBuilder.putMetadata(
                            creation.railId,
                            undoBuilder.metadataFor(old).copy(
                                speedLimitMetersPerSecond = built.speedLimits.operatingLimitMetersPerSecond,
                                derailSpeedMetersPerSecond = built.speedLimits.derailSpeedMetersPerSecond
                            )
                        )
                    }
                    undoBuilder.removeRails(oldToNew.keys)
                    undoBuilder.build()
                }
                PreparedRailBuild(
                    rebuilt, railCreation, editPlan,
                    prepareUndo
                )
            },
            onSuccess = { preparedRebuild ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                if (liveWorld !== world) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                if (getState(livePlayer).selectedAnchors != selectedSnapshot) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.selection_changed_cancel_smooth"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                val oldRails = affectedIds.map { id -> liveWorld.getRail(id) ?: return@supply }
                if (oldRails.any { it.trains.isNotEmpty() } || oldRails.zip(chains.flatten()).any { (live, planned) ->
                        live.curve !== planned.rail.curve
                    }
                ) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                commitGradeSmoothing(livePlayer, oldRails, preparedRebuild, selectedSnapshot)
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail grade smoothing failed: ${error.message ?: error::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.grade_smooth_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
            },
            onRetired = { heavyOperationGate.finish(player.uniqueId) }
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
        preparedRebuild: PreparedRailBuild<LinkedHashMap<UUID, BuiltCurve>>,
        selectedSnapshot: List<String>
    ) {
        val world = getCoreWorld(player) ?: return
        val rebuilt = preparedRebuild.value
        splitSafetyError(world, oldRails)?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        val editPlan = preparedRebuild.topologyEditPlan
        prepareUndoAndCommit(
        player = player,
        worldId = player.world.uid,
        expectedWorld = world,
        edit = preparedRebuild,
        operation = "RailWand.smoothSelectedGrades.prepareUndo",
        commit = { _, undo ->
        val undoSnapshot = undo.before
        val created = mutableListOf<CoreRail>()
        val preparedRenderByRail = undo.renderAdditionsByRail
        val oldToNew = linkedMapOf<UUID, UUID>()
        val connectionSnapshots = editPlan.explicitConnectionAffectedExistingRailIds
            .mapNotNull(world::getRail)
            .map(::snapshotRailConnections)
        editPlan.commitTopology(world) {
            try {
            requirePreparedExplicitEditReady(world, editPlan)
            val installedRails = world.installPreparedRails(preparedRebuild.railCreation)
            val preparedRails = installedRails.iterator()
            oldRails.forEach { old ->
                val replacement = preparedRails.next().also { rail ->
                    rail.metadata = undo.expectedAfter.railMetadata.getValue(rail.id)
                }
                created += replacement
                oldToNew[old.id] = replacement.id
                preparedRenderByRail[replacement.id]?.let { meta ->
                    RailVisualBootstrap.fromPersisted(world, meta)
                }
            }
            check(!preparedRails.hasNext()) { "Prepared rail batch does not match grade smoothing build" }
            editPlan.applyExplicitConnections(world, installedRails)
            undo.publishRenderSidecar()
            OperationalDestinationService(world).migrateAllOnRails(oldToNew)
            migrateOperatingDirections(world, oldToNew)
            oldRails.forEach { old ->
                RailVisualBootstrap.unregisterRail(old.id)
                old.destroy()
                world.removeRail(old)
                world.basicWorldData.railMetadata.remove(old.id)
            }
            pushUndo(player, WandAction.RestoreRailWorld(
                player.world.uid,
                undoSnapshot,
                undo.expectedAfter
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.grade_smoothed",
                created.size
            ).color(NamedTextColor.GREEN))
            } catch (failure: Exception) {
            restoreOperatingDirections(world, undoSnapshot.operatingDirections)
            created.forEach { rail ->
                RailVisualBootstrap.unregisterRail(rail.id)
                rail.destroy()
                world.removeRail(rail)
                world.basicWorldData.railMetadata.remove(rail.id)
            }
            restoreRailConnections(connectionSnapshots)
            throw failure
            }
        }
        },
        failure = { failedPlayer, failure ->
            plugin.logger.warning(
                "RailWand grade smoothing commit failed: ${failure.message ?: failure::class.simpleName}"
            )
            failedPlayer?.sendMessage(RailWandI18n.component(
                failedPlayer,
                "message.grade_smooth_failed",
                railWandFailureReason(failure)
            ).color(NamedTextColor.RED))
        }
        )
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.invalid_number",
                label,
                raw
            ).color(NamedTextColor.RED))
            null
        }
    }

    private data class RailActualValues(
        val subgradeSpeedKmH: Double,
        val curveOperatingSpeedKmH: Double,
        val automaticCurveOperatingSpeedKmH: Double,
        val radiusMeters: Double,
        val superelevationMm: Double,
        val maximumAbsoluteGrade: Double,
        val automaticCurveSpeed: Boolean,
        val automaticRadius: Boolean,
        val superelevationEnabled: Boolean
    )

    @OptIn(Heavy::class)
    private fun measureRailActualValues(rails: List<CoreRail>): RailActualValues {
        val first = rails.first()
        val measurements = RailGeometryMeasurementService.measure(
            rails.map { it.curve },
            plugin.physicsConfig.track.gaugeMeters
        )
        val automaticLimits = rails.mapNotNull { rail ->
            RailSpeedCalculator.calculate(
                curve = rail.curve,
                requestedLimitMetersPerSecond = Double.MAX_VALUE,
                allowableCantDeficiencyMeters = rail.metadata.geometry.allowableCantDeficiencyMeters,
                derailLateralAccelerationMetersPerSecondSquared =
                    plugin.physicsConfig.overspeed.derailLateralAccelerationMetersPerSecondSquared,
                physics = plugin.physicsConfig.railSpeedPhysics()
            ).takeIf { it.derailSpeedMetersPerSecond != null }?.operatingLimitMetersPerSecond
        }
        val automaticCurveSpeed = first.metadata.authoring?.curveSpeedLimitAutomatic != false
        val actualCurveLimit = if (automaticCurveSpeed) {
            automaticLimits.minOrNull()
        } else {
            rails.mapNotNull { it.metadata.speedLimits.value(SpeedLimitType.CURVE) }.minOrNull()
        } ?: automaticLimits.minOrNull() ?: 0.0
        return RailActualValues(
            subgradeSpeedKmH = (rails.firstNotNullOfOrNull {
                it.metadata.speedLimits.value(SpeedLimitType.SUBGRADE)
            } ?: first.metadata.designSpeedMetersPerSecond ?: 80.0 / 3.6) * 3.6,
            curveOperatingSpeedKmH = actualCurveLimit * 3.6,
            automaticCurveOperatingSpeedKmH = (automaticLimits.minOrNull() ?: 0.0) * 3.6,
            radiusMeters = measurements.minimumHorizontalRadiusMeters ?: 0.0,
            superelevationMm = measurements.maximumSuperelevationMeters * 1000.0,
            maximumAbsoluteGrade = measurements.maximumAbsoluteGrade,
            automaticCurveSpeed = automaticCurveSpeed,
            automaticRadius = first.metadata.geometry.curveRadiusMeters == null,
            superelevationEnabled = first.metadata.geometry.automaticSuperelevation ||
                measurements.maximumSuperelevationMeters > 1.0e-9
        )
    }

    internal fun editRail(player: Player, rail: CoreRail, state: WandPlayerState) {
        val selectedLogicalRails = logicalRails(player, rail)
        val railIds = selectedLogicalRails.map(CoreRail::id)
        val curves = selectedLogicalRails.associate { it.id to it.curve }
        val metadata = selectedLogicalRails.associate { it.id to it.metadata }
        state.selectedAnchors.clear()
        state.selectedRails.clear()
        state.selectedRails.add(rail.id)
        updateAnchorDisplay(player)

        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.measure_rail")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }
        val worldId = player.world.uid
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.measuring_rail"
        ).color(NamedTextColor.GRAY))
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.editRail.measure",
            supplier = { measureRailActualValues(selectedLogicalRails) },
            onSuccess = { actual ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                if (railIds.any { id ->
                        liveWorld.getRail(id)?.let { it.curve !== curves[id] || it.metadata != metadata[id] } != false
                    }
                ) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_reopen"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                openRailEditDialog(livePlayer, rail.id, actual)
            },
            onFailure = { failure ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail actual-value measurement failed: ${failure.message ?: failure::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.measure_failed",
                        railWandFailureReason(failure)
                    ).color(NamedTextColor.RED))
                }
            }
        )
    }

    private fun openRailEditDialog(player: Player, railId: UUID, actual: RailActualValues) {
        val session = configUI.getPlayerSession(player.uniqueId)
        session.setValue(railEditKeys.designSpeedKmH, actual.subgradeSpeedKmH)
        session.setValue(railEditKeys.curveSpeedLimitKmH, actual.curveOperatingSpeedKmH)
        session.setValue(railEditKeys.automaticCurveSpeedLimit, actual.automaticCurveSpeed)
        session.setValue(railEditKeys.curveRadius, actual.radiusMeters)
        session.setValue(railEditKeys.automaticCurveRadius, actual.automaticRadius)
        session.setValue(railEditKeys.superelevationEnabled, actual.superelevationEnabled)
        val gradeBody = buildRailGeometryDisplayText(
            radiusMeters = null,
            maximumAbsoluteGrade = actual.maximumAbsoluteGrade,
            speedLine = null,
            maximumGradeLabel = RailWandI18n.format(player, "display.maximum_grade")
        )?.let {
            listOf(Component.text(
                RailWandI18n.format(player, "display.actual", it),
                NamedTextColor.YELLOW
            ))
        }.orEmpty()
        dialogRenderer.showConfigDialog(
            player,
            "rail.edit",
            textResolver = RailWandI18n.resolver(player),
            additionalBody = gradeBody
        ) { p ->
            val liveWorld = getCoreWorld(p) ?: return@showConfigDialog
            val liveRail = liveWorld.getRail(railId) ?: run {
                p.sendActionBar(RailWandI18n.component(
                    p,
                    "message.rail_no_longer_exists"
                ).color(NamedTextColor.RED))
                return@showConfigDialog
            }
            val values = configUI.getPlayerSession(p.uniqueId)
            val designSpeedKmH = values.getValue(railEditKeys.designSpeedKmH) ?: actual.subgradeSpeedKmH
            val curveSpeedLimitKmH = values.getValue(railEditKeys.curveSpeedLimitKmH)
                ?: actual.curveOperatingSpeedKmH
            val automaticCurveSpeed = values.getValue(railEditKeys.automaticCurveSpeedLimit)
                ?: actual.automaticCurveSpeed
            val curveRadius = values.getValue(railEditKeys.curveRadius) ?: actual.radiusMeters
            val automaticRadius = values.getValue(railEditKeys.automaticCurveRadius) ?: actual.automaticRadius
            val superelevationEnabled = values.getValue(railEditKeys.superelevationEnabled)
                ?: actual.superelevationEnabled
            if (!automaticCurveSpeed && curveSpeedLimitKmH <= 0.0) {
                p.sendActionBar(RailWandI18n.component(
                    p,
                    "message.manual_curve_speed_positive"
                ).color(NamedTextColor.RED))
                return@showConfigDialog
            }
            if (!automaticRadius && curveRadius <= 0.0) {
                p.sendActionBar(RailWandI18n.component(
                    p,
                    "message.manual_radius_positive"
                ).color(NamedTextColor.RED))
                return@showConfigDialog
            }

            val geometryChanged = automaticRadius != actual.automaticRadius ||
                superelevationEnabled != actual.superelevationEnabled ||
                (!automaticRadius && abs(curveRadius - actual.radiusMeters) >= 1.0e-6) ||
                (superelevationEnabled && abs(designSpeedKmH - actual.subgradeSpeedKmH) >= 1.0e-6)
            val speedRequiresGeometry = curveSpeedRequiresGeometryRebuild(
                curveSpeedLimitKmH,
                automaticCurveSpeed,
                actual.automaticCurveOperatingSpeedKmH
            )
            val speedChanged = automaticCurveSpeed != actual.automaticCurveSpeed ||
                (!automaticCurveSpeed &&
                    abs(curveSpeedLimitKmH - actual.curveOperatingSpeedKmH) >= 1.0e-6)
            val subgradeChanged = abs(designSpeedKmH - actual.subgradeSpeedKmH) >= 1.0e-6

            if (geometryChanged || speedRequiresGeometry) {
                rebuildRailGeometry(
                    player = p,
                    selectedRail = liveRail,
                    designSpeedKmH = designSpeedKmH,
                    curveSpeedLimitKmH = curveSpeedLimitKmH,
                    automaticCurveSpeedLimit = automaticCurveSpeed,
                    curveRadius = if (automaticRadius) 0.0 else curveRadius,
                    automaticCurveRadius = automaticRadius,
                    superelevationEnabled = superelevationEnabled
                )
            } else if (speedChanged || subgradeChanged) {
                updateCurveSpeedLimit(
                    player = p,
                    selectedRail = liveRail,
                    designSpeedKmH = designSpeedKmH,
                    curveSpeedLimitKmH = curveSpeedLimitKmH,
                    automaticCurveSpeedLimit = automaticCurveSpeed,
                    settings = snapshotCurveBuildSettings(getState(p)).copy(
                        designSpeedKmH = designSpeedKmH,
                        curveSpeedLimitKmH = curveSpeedLimitKmH,
                        automaticCurveSpeedLimit = automaticCurveSpeed,
                        curveSpeedLimitIsCreationMinimum = false
                    )
                )
            } else {
                p.sendActionBar(RailWandI18n.component(
                    p,
                    "message.rail_parameters_unchanged"
                ).color(NamedTextColor.GRAY))
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
        val ids = resolveLogicalRailLaneIds(
            rail.id,
            world.basicWorldData.railMetadata.snapshot()
        )
        return ids.map { railId ->
            requireNotNull(world.getRail(railId)) { "Logical rail segment $railId is no longer loaded" }
        }
    }

    private fun railAssemblyLanes(world: CoreWorld, rail: CoreRail): List<List<CoreRail>> {
        val assembly = rail.metadata.assembly
        if (assembly != null) {
            val lanes = world.allRails
                .filter { it.metadata.assembly?.groupId == assembly.groupId }
                .groupBy { it.metadata.assembly!!.laneIndex }
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

    private fun updateCurveSpeedLimit(
        player: Player,
        selectedRail: CoreRail,
        designSpeedKmH: Double,
        curveSpeedLimitKmH: Double,
        automaticCurveSpeedLimit: Boolean,
        settings: CurveBuildSettingsSnapshot
    ) {
        require(designSpeedKmH.isFinite() && designSpeedKmH > 0.0)
        require(curveSpeedLimitKmH.isFinite() && curveSpeedLimitKmH >= 0.0)
        val world = getCoreWorld(player) ?: return
        val targetRails = logicalRails(world, selectedRail)
        val before = targetRails.associate { it.id to it.metadata }
        val curves = targetRails.associate { it.id to it.curve }
        val manualLimit = curveSpeedLimitKmH.takeUnless { automaticCurveSpeedLimit }?.div(3.6)
        val subgradeLimit = designSpeedKmH / 3.6
        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.speed_update")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }
        val worldId = player.world.uid
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.calculating_speed"
        ).color(NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.updateCurveSpeedLimit",
            supplier = {
                targetRails.associate { rail ->
                    val current = before.getValue(rail.id)
                    val authoring = (current.authoring ?: RailAuthoringMetadata()).copy(
                        designSpeedMetersPerSecond = subgradeLimit,
                        curveSpeedLimitAutomatic = automaticCurveSpeedLimit
                    )
                    val requestedEntries = current.speedLimits.entries
                        .filterNot { it.type == SpeedLimitType.CURVE || it.type == SpeedLimitType.SUBGRADE }
                        .toMutableList()
                        .apply {
                            add(SpeedLimitEntry(subgradeLimit, SpeedLimitType.SUBGRADE))
                            manualLimit?.let { add(SpeedLimitEntry(it, SpeedLimitType.CURVE)) }
                        }
                    val requestedMetadata = current.copy(
                        authoring = authoring,
                        speedLimits = SpeedLimitMetadata(requestedEntries)
                    )
                    val calculated = calculateSpeedLimits(curves.getValue(rail.id), requestedMetadata, settings)
                    val effectiveEntries = current.speedLimits.entries
                        .filterNot { it.type == SpeedLimitType.CURVE || it.type == SpeedLimitType.SUBGRADE }
                        .toMutableList()
                        .apply {
                            add(SpeedLimitEntry(subgradeLimit, SpeedLimitType.SUBGRADE))
                            calculated.derailSpeedMetersPerSecond?.let {
                                add(SpeedLimitEntry(calculated.operatingLimitMetersPerSecond, SpeedLimitType.CURVE))
                            }
                        }
                    rail.id to current.copy(
                        authoring = authoring,
                        speedLimits = SpeedLimitMetadata(effectiveEntries),
                        curveSafety = CurveSafetyMetadata(calculated.derailSpeedMetersPerSecond)
                    )
                }
            },
            onSuccess = { updates ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                val liveRails = targetRails.map { original ->
                    val live = liveWorld.getRail(original.id) ?: run {
                        livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                        return@supply
                    }
                    if (live.curve !== curves[original.id] || live.metadata != before[original.id]) {
                        livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                        return@supply
                    }
                    live
                }
                val editPlan = RailWandTopologyEditPlan.captureRailScopedMutation(
                    liveWorld,
                    liveRails.map(CoreRail::id)
                )
                val editPermit = acquireTopologyEditPermit(livePlayer, editPlan) ?: return@supply
                editPermit.use {
                    try {
                        editPlan.requireCurrent(liveWorld)
                        liveRails.forEach { rail -> rail.metadata = updates.getValue(rail.id) }
                        refreshAnchorsForAllViewers()
                        pushUndo(livePlayer, WandAction.RestoreRailMetadata(
                            livePlayer.world.uid,
                            before,
                            updates
                        ))
                        livePlayer.sendActionBar(RailWandI18n.component(
                            livePlayer,
                            "message.speed_updated"
                        ).color(NamedTextColor.GREEN))
                    } catch (failure: Exception) {
                        liveRails.forEach { rail -> before[rail.id]?.let { rail.metadata = it } }
                        livePlayer.sendMessage(RailWandI18n.component(
                            livePlayer,
                            "message.speed_update_rollback",
                            railWandFailureReason(failure)
                        ).color(NamedTextColor.RED))
                    }
                }
            },
            onFailure = { failure ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail curve speed update failed: ${failure.message ?: failure::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.speed_update_failed",
                        railWandFailureReason(failure)
                    ).color(NamedTextColor.RED))
                }
            }
        )
    }

    private fun rebuildRailGeometry(
        player: Player,
        selectedRail: CoreRail,
        designSpeedKmH: Double,
        curveSpeedLimitKmH: Double,
        automaticCurveSpeedLimit: Boolean,
        curveRadius: Double,
        automaticCurveRadius: Boolean,
        superelevationEnabled: Boolean
    ) {
        val coreWorld = getCoreWorld(player) ?: return
        val oldLanes = railAssemblyLanes(coreWorld, selectedRail)
        val oldRails = oldLanes.flatten()
        if (oldRails.isEmpty()) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.logical_info_incomplete"
            ).color(NamedTextColor.RED))
            return
        }
        if (oldRails.any { it.trains.isNotEmpty() }) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.occupied_rebuild_forbidden"
            ).color(NamedTextColor.RED))
            return
        }
        splitSafetyError(coreWorld, oldRails)?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        turnoutGeometryMaintenanceError(coreWorld, oldRails)?.let { reason ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, reason),
                NamedTextColor.RED
            ))
            return
        }

        val geometry = selectedRail.metadata.geometry
        if (geometry.curveKind == RailCurveKind.UNKNOWN) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.template_missing"
            ).color(NamedTextColor.RED))
            return
        }
        val template = RailCurveTemplateType.valueOf(geometry.curveKind.name)
        val parallel = geometry.parallel
        val settings = CurveBuildSettingsSnapshot(
            parallelCount = oldLanes.size,
            parallelSpacing = parallel?.spacingMeters ?: 4.5,
            curveRadius = curveRadius,
            automaticCurveRadius = automaticCurveRadius,
            curveTemplate = template,
            designSpeedKmH = designSpeedKmH,
            curveSpeedLimitKmH = curveSpeedLimitKmH,
            automaticCurveSpeedLimit = automaticCurveSpeedLimit,
            curveSpeedLimitIsCreationMinimum = false,
            allowableCantDeficiencyMm = geometry.allowableCantDeficiencyMeters * 1000.0,
            superelevationEnabled = superelevationEnabled
        )
        val starts = oldLanes.map { anchorFromRailEndpoint(player.world, it.first(), 'A') }
        val ends = oldLanes.map { anchorFromRailEndpoint(player.world, it.last(), 'B') }
        val oldIds = oldRails.mapTo(linkedSetOf()) { it.id }
        val selectedLane = selectedRail.metadata.assembly?.laneIndex
            ?: selectedRail.metadata.geometry.parallel?.index ?: 0
        val selectedRole = selectedRail.metadata.assembly?.role ?: RailAssemblyRole.CURVE
        val worldId = player.world.uid
        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.curve_rebuild")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.rebuilding_curve"
        ).color(NamedTextColor.GRAY))

        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.rebuildRailGeometry",
            supplier = {
                val builtLanes = createCurvesWithSpeedLimits(starts, ends, settings)?.map { built ->
                    partitionBuiltCurve(built, settings)
                } ?: return@supply null
                val railCreation = coreWorld.prepareRailCreationBatch(
                        builtLanes.flatten().map(BuiltRailPart::curve),
                        oldIds
                    )
                val newLaneIds = buildList {
                    val iterator = railCreation.creations.iterator()
                    builtLanes.forEach { lane -> add(List(lane.size) { iterator.next().railId }) }
                    check(!iterator.hasNext()) { "Prepared rail batch does not match rebuilt lanes" }
                }
                val replacementEndpoints = oldLanes.indices.flatMap { laneIndex ->
                    val oldLane = oldLanes[laneIndex]
                    val newLane = newLaneIds[laneIndex]
                    listOf(
                        railEndpointRef(oldLane.first().id, 'A') to railEndpointRef(newLane.first(), 'A'),
                        railEndpointRef(oldLane.last().id, 'B') to railEndpointRef(newLane.last(), 'B')
                    )
                }.toMap()
                val explicitConnections = newLaneIds.flatMap { lane ->
                    lane.zipWithNext().map { (first, second) ->
                        railEndpointRef(first, 'B') to railEndpointRef(second, 'A')
                    }
                }
                val editPlan = capturePreparedExplicitConnectionPlan(
                    coreWorld, railCreation, replacementEndpoints, explicitConnections
                )
                val oldAssemblyId = selectedRail.metadata.assembly?.groupId
                val segmentCount = builtLanes.first().size
                val assemblyId = if (segmentCount > 1) oldAssemblyId ?: UUID.randomUUID().toString() else null
                val parallelIds = List(segmentCount) {
                    UUID.randomUUID().toString().takeIf { oldLanes.size > 1 }
                }
                val prepareUndo = {
                    val undoBuilder = PreparedRailWorldUndoBuilder.capture(
                        worldId, worldId.toString(), coreWorld, editPlan
                    )
                    val targetSourceByNew = linkedMapOf<UUID, UUID>()
                    builtLanes.forEachIndexed { laneIndex, parts ->
                    val oldLane = oldLanes[laneIndex]
                    val oldLaneMetadata = oldLane.map { it.metadata }
                    val laneSettings = settings.copy(
                        curveSpeedLimitKmH = curveSpeedLimitForRebuiltLane(
                            laneIndex, selectedLane, curveSpeedLimitKmH, oldLaneMetadata
                        ),
                        automaticCurveSpeedLimit = curveSpeedAutomaticForRebuiltLane(
                            laneIndex, selectedLane, automaticCurveSpeedLimit, oldLaneMetadata
                        ),
                        curveSpeedLimitIsCreationMinimum = false
                    )
                    parts.forEachIndexed { segmentIndex, part ->
                        val oldForRole = oldLane.firstOrNull { it.metadata.assembly?.role == part.role }
                            ?: oldLane[minOf(segmentIndex, oldLane.lastIndex)]
                        val newId = newLaneIds[laneIndex][segmentIndex]
                        targetSourceByNew[newId] = oldForRole.id
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
                            laneSettings,
                            calculateSpeedLimits(part.curve, null, laneSettings),
                            parallelMeta
                        )
                        undoBuilder.putMetadata(
                            newId,
                            rebuiltMetadata.copy(
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
                        )
                    }
                    }
                    val oldToNew = oldLanes.flatMapIndexed { laneIndex, oldLane ->
                        oldLane.mapIndexed { oldIndex, old ->
                            val targetIndex = builtLanes[laneIndex].indexOfFirst {
                                it.role == old.metadata.assembly?.role
                            }.takeIf { it >= 0 } ?: minOf(oldIndex, builtLanes[laneIndex].lastIndex)
                            old.id to newLaneIds[laneIndex][targetIndex]
                        }
                    }.toMap()
                    undoBuilder.inheritRender(targetSourceByNew)
                    undoBuilder.migrateDirections(targetSourceByNew, oldIds)
                    undoBuilder.migrateMappedInfrastructure(oldToNew)
                    undoBuilder.removeRails(oldIds)
                    undoBuilder.build()
                }
                PreparedRailBuild(
                    builtLanes, railCreation, editPlan,
                    prepareUndo
                )
            },
            onSuccess = { preparedBuild ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                if (preparedBuild == null) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rebuild_curve_impossible"
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                if (liveWorld !== coreWorld) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                val builtLanes = preparedBuild.value
                if (builtLanes.size != oldLanes.size ||
                    builtLanes.map { it.size }.distinct().size != 1
                ) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rebuild_curve_impossible"
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                turnoutBoundaryFrameError(
                    oldLanes,
                    builtLanes.map { lane -> lane.map(BuiltRailPart::curve) }
                )?.let { reason ->
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.turnout_rebuild_forbidden",
                        RailWandI18n.resolve(livePlayer, reason)
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                val liveOldRails = oldRails.map { old -> liveWorld.getRail(old.id) ?: return@supply }
                if (liveOldRails.zip(oldRails).any { (live, planned) -> live !== planned || live.curve !== planned.curve }) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.rail_changed_retry"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                if (liveOldRails.any { it.trains.isNotEmpty() }) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.occupied_rebuild_forbidden"
                    ).color(NamedTextColor.RED))
                    return@supply
                }
                turnoutGeometryMaintenanceError(liveWorld, liveOldRails)?.let { reason ->
                    livePlayer.sendActionBar(Component.text(
                RailWandI18n.resolve(livePlayer, reason),
                NamedTextColor.RED
            ))
                    return@supply
                }

                val newLaneIds = buildList {
                    val iterator = preparedBuild.railCreation.creations.iterator()
                    builtLanes.forEach { lane -> add(List(lane.size) { iterator.next().railId }) }
                    check(!iterator.hasNext()) { "Prepared rail batch does not match rebuilt lanes" }
                }
                val editPlan = preparedBuild.topologyEditPlan
                prepareUndoAndCommit(
                player = livePlayer,
                worldId = worldId,
                expectedWorld = liveWorld,
                edit = preparedBuild,
                operation = "RailWand.rebuildRailGeometry.prepareUndo",
                commit = { _, undo ->
                val undoSnapshot = undo.before
                val connectionSnapshots = editPlan.explicitConnectionAffectedExistingRailIds
                    .mapNotNull(liveWorld::getRail)
                    .map(::snapshotRailConnections)
                val created = mutableListOf<CoreRail>()
                val preparedRenderByRail = undo.renderAdditionsByRail
                val createdLanes = mutableListOf<List<CoreRail>>()
                editPlan.commitTopology(liveWorld) {
                    try {
                    requirePreparedExplicitEditReady(liveWorld, editPlan)
                    val installedRails = liveWorld.installPreparedRails(preparedBuild.railCreation)
                    val preparedRails = installedRails.iterator()
                    builtLanes.forEach { parts ->
                        val newLane = parts.map {
                            preparedRails.next().also {
                                it.metadata = undo.expectedAfter.railMetadata.getValue(it.id)
                                created += it
                            }
                        }
                        createdLanes += newLane
                    }
                    check(!preparedRails.hasNext()) { "Prepared rail batch does not match geometry rebuild" }
                    val oldToNew = oldLanes.flatMapIndexed { laneIndex, oldLane ->
                        oldLane.mapIndexed { oldIndex, old ->
                            val replacement = createdLanes[laneIndex].firstOrNull {
                                it.metadata.assembly?.role == old.metadata.assembly?.role
                            } ?: createdLanes[laneIndex][minOf(oldIndex, createdLanes[laneIndex].lastIndex)]
                            old.id to replacement.id
                        }
                    }.toMap()
                    editPlan.applyExplicitConnections(liveWorld, installedRails)
                    OperationalDestinationService(liveWorld).migrateAllOnRails(oldToNew)
                    val replacementDirections = createdLanes.flatMapIndexed { laneIndex, newLane ->
                        val oldLane = oldLanes[laneIndex]
                        newLane.mapIndexedNotNull { segmentIndex, newRail ->
                            val oldForRole = oldLane.firstOrNull {
                                it.metadata.assembly?.role == newRail.metadata.assembly?.role
                            } ?: oldLane[minOf(segmentIndex, oldLane.lastIndex)]
                            liveWorld.basicWorldData.operatingDirections[oldForRole.id]?.let {
                                newRail.id to it
                            }
                        }
                    }.toMap()
                    liveWorld.basicWorldData.operatingDirections.replaceRails(
                        removedRailIds = oldIds,
                        replacementDirections = replacementDirections
                    )
                    liveOldRails.forEach { old ->
                        RailVisualBootstrap.unregisterRail(old.id)
                        old.destroy()
                        liveWorld.removeRail(old)
                        liveWorld.basicWorldData.railMetadata.remove(old.id)
                    }
                    createdLanes.flatten().forEach { newRail ->
                        preparedRenderByRail[newRail.id]?.let { meta ->
                            RailVisualBootstrap.fromPersisted(liveWorld, meta)
                        }
                    }
                    undo.publishRenderSidecar()

                    val replacementLane = createdLanes[selectedLane.coerceIn(createdLanes.indices)]
                    val replacement = replacementLane.firstOrNull { it.metadata.assembly?.role == selectedRole }
                        ?: replacementLane.first()
                    val state = getState(livePlayer)
                    state.selectedRails.clear()
                    state.selectedRails.add(replacement.id)
                    persistState(livePlayer, state)
                    refreshAllAnchorsForPlayer(livePlayer)
                    updateAnchorDisplay(livePlayer)
                    pushUndo(livePlayer, WandAction.RestoreRailWorld(
                        livePlayer.world.uid,
                        undoSnapshot,
                        undo.expectedAfter
                    ))
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.curve_updated"
                    ).color(NamedTextColor.GREEN))
                    } catch (error: Exception) {
                    restoreOperatingDirections(liveWorld, undoSnapshot.operatingDirections)
                    created.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        rail.destroy()
                        liveWorld.removeRail(rail)
                        liveWorld.basicWorldData.railMetadata.remove(rail.id)
                    }
                    restoreRailConnections(connectionSnapshots)
                    undoSnapshot.renderMetas.forEach { RailVisualBootstrap.fromPersisted(liveWorld, it) }
                    throw error
                    }
                }
                },
                failure = { failedPlayer, error ->
                    plugin.logger.warning(
                        "RailWand geometry rebuild commit failed: ${error.message ?: error::class.simpleName}"
                    )
                    failedPlayer?.sendMessage(RailWandI18n.component(
                        failedPlayer,
                        "message.rebuild_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
                )
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail radius rebuild failed: ${error.message ?: error::class.simpleName}")
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.rebuild_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
            },
            onRetired = { heavyOperationGate.finish(player.uniqueId) }
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
        }?.times(3.6)?.let { "%.0f km/h".format(it) }
            ?: RailWandI18n.format(player, "message.not_set")
        val curveLabel = selectedLogicalRails.firstNotNullOfOrNull {
            it.metadata.speedLimits.value(SpeedLimitType.CURVE)
        }?.times(3.6)?.let { "%.0f km/h".format(it) }
            ?: RailWandI18n.format(player, "message.none")
        val derailLabel = selectedLogicalRails.mapNotNull { it.metadata.derailSpeedMetersPerSecond }
            .minOrNull()?.times(3.6)?.let { "%.0f km/h".format(it) } ?: "∞"
        val radiusLabel = rail.metadata.geometry.curveRadiusMeters?.let { "%.0f m".format(it) }
            ?: RailWandI18n.format(player, "message.automatic")
        val parallelLabel = rail.metadata.geometry.parallel?.let {
            RailWandI18n.format(player, "message.parallel", it.index + 1, it.count)
        } ?: RailWandI18n.format(player, "message.single")
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.rail_selected",
            rail.id,
            subgradeLabel,
            curveLabel,
            derailLabel,
            radiusLabel,
            parallelLabel
        ).color(NamedTextColor.YELLOW))
    }

    internal fun editAnchor(player: Player, anchor: Anchor, state: WandPlayerState) {
        if (parseSplitAnchorId(anchor.id) != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.split_anchor_immovable"
            ).color(NamedTextColor.YELLOW))
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
        dialogRenderer.showConfigDialog(
            player,
            "anchor.edit",
            textResolver = RailWandI18n.resolver(player)
        ) { p ->
            // 确认回调: 读取新值并更新锚点
            val s = configUI.getPlayerSession(p.uniqueId)
            val x = parseEditDouble(
                s.getValue(editKeys.posX),
                anchor.position.x(),
                RailWandI18n.format(p, "anchor_edit.x.label"),
                p
            ) ?: return@showConfigDialog
            val y = parseEditDouble(
                s.getValue(editKeys.posY),
                anchor.position.y(),
                RailWandI18n.format(p, "anchor_edit.y.label"),
                p
            ) ?: return@showConfigDialog
            val z = parseEditDouble(
                s.getValue(editKeys.posZ),
                anchor.position.z(),
                RailWandI18n.format(p, "anchor_edit.z.label"),
                p
            ) ?: return@showConfigDialog
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
                updateAnchor(p.world, updated)
                removeAnchorDisplay(p, anchor.id)
                spawnAnchorDisplay(p, updated)
                pushUndo(p, WandAction.EditAnchors(p.world.uid, listOf(anchor), listOf(updated)))
                p.sendActionBar(RailWandI18n.component(
                    p,
                    "message.anchor_updated",
                    anchor.id
                ).color(NamedTextColor.GREEN))
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
            dialogRenderer.showConfigDialog(
                player,
                "anchor.batchEdit",
                textResolver = RailWandI18n.resolver(player)
            ) { p ->
                val s = configUI.getPlayerSession(p.uniqueId)
                val dx = s.getValue(editKeys!!.deltaX) ?: 0.0
                val dy = s.getValue(editKeys!!.deltaY) ?: 0.0
                val dz = s.getValue(editKeys!!.deltaZ) ?: 0.0
                val dYaw = s.getValue(editKeys!!.deltaYaw) ?: 0.0
                val dRad = Math.toRadians(dYaw)
                val updatedAnchors = anchors.map { anchor ->
                    val newPos = Vector3d(
                        anchor.position.x() + dx,
                        anchor.position.y() + dy,
                        anchor.position.z() + dz
                    )
                    val oldYaw = atan2(anchor.tangent.x(), anchor.tangent.z())
                    val newYaw = oldYaw + dRad
                    val newTan = Vector3d(-sin(newYaw), 0.0, cos(newYaw))
                    anchor.withPosition(newPos).withTangent(newTan)
                }
                updatedAnchors.forEach { updated ->
                    updateAnchor(p.world, updated)
                    removeAnchorDisplay(p, updated.id)
                    spawnAnchorDisplay(p, updated)
                }
                pushUndo(p, WandAction.EditAnchors(p.world.uid, anchors.toList(), updatedAnchors))
                p.sendActionBar(RailWandI18n.component(
                    p,
                    "message.anchors_translated",
                    anchors.size
                ).color(NamedTextColor.GREEN))
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.no_split_target"
            ).color(NamedTextColor.YELLOW))
            return
        }
        val coreWorld = getCoreWorld(player)
        val rail = coreWorld?.getRail(target.railId)
        if (rail == null || target.arcLength <= Curve.TOLERANCE ||
            target.arcLength >= rail.curve.totalArcLength - Curve.TOLERANCE
        ) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.split_at_endpoint"
            ).color(NamedTextColor.RED))
            return
        }
        splitPointSafetyError(coreWorld, rail, target.arcLength)?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        state.pendingSplitRail = target.railId
        state.pendingSplitArcLength = target.arcLength
        state.selectedRails.clear()
        state.selectedRails.add(target.railId)
        persistState(player, state)
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.split_selected",
            target.arcLength
        ).color(NamedTextColor.YELLOW))
    }

    private fun confirmSplit(player: Player, state: WandPlayerState) {
        val railId = state.pendingSplitRail ?: return
        val arcLength = state.pendingSplitArcLength ?: return
        val coreWorld = getCoreWorld(player) ?: return
        val rail = coreWorld.getRail(railId) ?: run {
            clearPendingSplit(state)
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.split_rail_missing"
            ).color(NamedTextColor.RED))
            return
        }
        splitSafetyError(coreWorld, listOf(rail))?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        if (arcLength <= Curve.TOLERANCE || arcLength >= rail.curve.totalArcLength - Curve.TOLERANCE) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.split_too_close_endpoint"
            ).color(NamedTextColor.RED))
            return
        }
        splitPointSafetyError(coreWorld, rail, arcLength)?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.rail_split")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val curveSnapshot = rail.curve
        val metadataSnapshot = rail.metadata
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.confirmSplit",
            supplier = {
                val curves = curveSnapshot.splitAt(arcLength)
                val operationId = UUID.randomUUID().toString()
                val railCreation = coreWorld.prepareRailCreationBatch(curves.toList(), setOf(railId))
                val beforeId = railCreation.creations[0].railId
                val afterId = railCreation.creations[1].railId
                val editPlan = capturePreparedExplicitConnectionPlan(
                    coreWorld,
                    railCreation,
                    mapOf(
                            railEndpointRef(railId, 'A') to railEndpointRef(beforeId, 'A'),
                            railEndpointRef(railId, 'B') to railEndpointRef(afterId, 'B')
                    ),
                    listOf(railEndpointRef(beforeId, 'B') to railEndpointRef(afterId, 'A'))
                )
                val prepareUndo = {
                    val undoBuilder = PreparedRailWorldUndoBuilder.capture(
                        worldId, worldId.toString(), coreWorld, editPlan
                    )
                    val originalTemplate = undoBuilder.renderTemplateFor(railId)
                    val beforeMetadata = undoBuilder.metadataSnapshot()
                    val preparedMetadata = RailInfrastructureMetadataStore().also { store ->
                        store.restore(beforeMetadata)
                        RailInfrastructureMetadataTopology.split(
                            store, railId, beforeId, afterId, operationId
                        )
                    }.snapshot()
                    undoBuilder.replaceMetadataSnapshot(preparedMetadata)
                    undoBuilder.migrateDirections(
                        mapOf(beforeId to railId, afterId to railId), setOf(railId)
                    )
                    undoBuilder.migrateSplit(railId, beforeId, afterId, arcLength)
                    undoBuilder.removeRails(setOf(railId))
                    preparedMetadata.keys.filter { beforeMetadata[it] != preparedMetadata[it] }.forEach { changedId ->
                        val template = if (changedId == beforeId || changedId == afterId) originalTemplate
                        else undoBuilder.renderTemplateFor(changedId)
                        template?.let { undoBuilder.prepareRender(changedId, it) }
                    }
                    undoBuilder.build()
                }
                PreparedRailBuild(
                    PreparedSplitBuild(curves, operationId), railCreation, editPlan, prepareUndo
                )
            },
            onSuccess = { preparedSplit ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                if (liveWorld !== coreWorld) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.split_changed_reselect"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                val liveRail = liveWorld.getRail(railId)
                if (liveRail == null || liveRail.curve !== curveSnapshot || liveRail.metadata != metadataSnapshot) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.split_changed_reselect"
                    ).color(NamedTextColor.YELLOW))
                    clearPendingSplit(getState(livePlayer))
                    return@supply
                }
                splitSafetyError(liveWorld, listOf(liveRail))?.let { error ->
                    livePlayer.sendActionBar(Component.text(
                RailWandI18n.resolve(livePlayer, error),
                NamedTextColor.RED
            ))
                    return@supply
                }
                splitPointSafetyError(liveWorld, liveRail, arcLength)?.let { error ->
                    livePlayer.sendActionBar(Component.text(
                RailWandI18n.resolve(livePlayer, error),
                NamedTextColor.RED
            ))
                    return@supply
                }

                val editPlan = preparedSplit.topologyEditPlan
                prepareUndoAndCommit(
                    player = livePlayer,
                    worldId = worldId,
                    expectedWorld = liveWorld,
                    edit = preparedSplit,
                    operation = "RailWand.confirmSplit.prepareUndo",
                    commit = { _, undo ->
                    val undoSnapshot = undo.before
                    try {
                        editPlan.commitTopology(liveWorld) {
                            editPlan.requireCurrent(liveWorld)
                            val installed = liveWorld.installPreparedRails(preparedSplit.railCreation)
                            editPlan.applyExplicitConnections(liveWorld, installed)
                            val result = RailTopologyEditor.SplitResult(
                                installed[0], installed[1], preparedSplit.value.operationId
                            )
                            liveRail.destroy()
                            liveWorld.removeRail(liveRail)
                            liveWorld.basicWorldData.railMetadata.replace(
                                undo.metadataRemovals,
                                undo.metadataAdditions
                            )
                            liveWorld.basicWorldData.operatingDirections.run {
                                val inherited = this[railId]
                                replaceRails(
                                    removedRailIds = listOf(railId),
                                    replacementDirections = inherited?.let {
                                        mapOf(result.before.id to it, result.after.id to it)
                                    }.orEmpty()
                                )
                            }
                            OperationalSpeedLimitSignalMaterializer.materializeRail(result.before)
                            OperationalSpeedLimitSignalMaterializer.materializeRail(result.after)
                            OperationalDestinationService(liveWorld).migrateAfterRailSplit(
                                oldRailId = railId,
                                beforeRailId = result.before.id,
                                afterRailId = result.after.id,
                                splitArcLengthInAbMeters = arcLength
                            )
                            val changedExistingIds = undo.metadataAdditions.keys
                                .filterTo(linkedSetOf()) { it != result.before.id && it != result.after.id }
                            RailVisualBootstrap.unregisterRail(railId)
                            val replacementIds = changedExistingIds + result.before.id + result.after.id
                            val replacementMetas = undo.renderAdditionsByRail
                                .filterKeys(replacementIds::contains)
                                .values
                            replacementMetas.forEach { meta ->
                                RailVisualBootstrap.fromPersisted(liveWorld, meta)
                            }
                            undo.publishRenderSidecar()
                            val liveState = getState(livePlayer)
                            clearPendingSplit(liveState)
                            liveState.selectedRails.clear()
                            liveState.selectedRails.addAll(listOf(result.before.id, result.after.id))
                            persistState(livePlayer, liveState)
                            splitPreviewTargets.remove(livePlayer.uniqueId)
                            refreshAnchorsForAllViewers()
                            pushUndo(livePlayer, WandAction.RestoreRailWorld(
                                livePlayer.world.uid,
                                undoSnapshot,
                                undo.expectedAfter
                            ))
                        }
                    } catch (error: Throwable) {
                        restoreOperatingDirections(liveWorld, undoSnapshot.operatingDirections)
                        throw error
                    }
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.split_success"
                    ).color(NamedTextColor.GREEN))
                    },
                    failure = { failedPlayer, error ->
                        plugin.logger.warning("Rail split failed: ${error::class.simpleName}: ${error.message ?: ""}")
                        failedPlayer?.sendMessage(RailWandI18n.component(
                            failedPlayer,
                            "message.split_failed",
                            railWandFailureReason(error)
                        ).color(NamedTextColor.RED))
                    }
                )
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail curve split failed: ${error::class.simpleName}: ${error.message ?: ""}")
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.split_curve_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
            },
            onRetired = { heavyOperationGate.finish(player.uniqueId) }
        )
    }

    internal fun mergeSplitAnchor(player: Player, anchor: Anchor) {
        val operationId = parseSplitAnchorId(anchor.id) ?: run {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.split_anchor_required"
            ).color(NamedTextColor.YELLOW))
            return
        }
        val coreWorld = getCoreWorld(player) ?: return
        val pair = findSplitPair(coreWorld, operationId) ?: run {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.split_pair_changed"
            ).color(NamedTextColor.RED))
            return
        }
        val (before, after) = pair
        RailTopologyEditor.mergeEligibilityError(
            before,
            after,
            signalCanBeRebuilt = { it is OperationalSpeedLimitSignal }
        )?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        RailInfrastructureMetadataTopology.mergeEligibilityError(
            coreWorld.basicWorldData.railMetadata,
            before.id,
            after.id
        )?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        splitSafetyError(coreWorld, listOf(before, after))?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }
        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.rail_merge")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }

        val worldId = player.world.uid
        val beforeCurve = before.curve
        val afterCurve = after.curve
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.mergeSplitAnchor",
            supplier = {
                val mergedCurve = beforeCurve.concatenate(afterCurve)
                val railCreation = coreWorld.prepareRailCreation(
                    mergedCurve,
                    setOf(before.id, after.id)
                )
                val mergedId = railCreation.creations.single().railId
                val editPlan = capturePreparedExplicitConnectionPlan(
                    coreWorld,
                    railCreation,
                    mapOf(
                            railEndpointRef(before.id, 'A') to railEndpointRef(mergedId, 'A'),
                            railEndpointRef(after.id, 'B') to railEndpointRef(mergedId, 'B')
                    ),
                    emptyList()
                )
                val prepareUndo = {
                    val undoBuilder = PreparedRailWorldUndoBuilder.capture(
                        worldId, worldId.toString(), coreWorld, editPlan
                    )
                    val beforeTemplate = undoBuilder.renderTemplateFor(before.id)
                    val afterTemplate = undoBuilder.renderTemplateFor(after.id)
                    if ((beforeTemplate == null) != (afterTemplate == null) || beforeTemplate != afterTemplate) {
                        throw RailWandLocalizedException("message.render_metadata_mismatch")
                    }
                    val beforeMetadata = undoBuilder.metadataSnapshot()
                    val preparedMetadata = RailInfrastructureMetadataStore().also { store ->
                        store.restore(beforeMetadata)
                        RailInfrastructureMetadataTopology.merge(store, before.id, after.id, mergedId)
                    }.snapshot()
                    val template = beforeTemplate
                    undoBuilder.replaceMetadataSnapshot(preparedMetadata)
                    undoBuilder.migrateDirections(mapOf(mergedId to before.id), setOf(before.id, after.id))
                    undoBuilder.migrateMerge(
                        before.id, after.id, mergedId, beforeCurve.totalArcLength
                    )
                    undoBuilder.removeRails(setOf(before.id, after.id))
                    preparedMetadata.keys.filter { beforeMetadata[it] != preparedMetadata[it] }.forEach { changedId ->
                        val changedTemplate = if (changedId == mergedId) template
                        else undoBuilder.renderTemplateFor(changedId)
                        changedTemplate?.let { undoBuilder.prepareRender(changedId, it) }
                    }
                    undoBuilder.build()
                }
                PreparedRailBuild(
                    mergedCurve, railCreation, editPlan, prepareUndo
                )
            },
            onSuccess = { preparedMerge ->
                heavyOperationGate.finish(player.uniqueId)
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId) return@supply
                val liveWorld = getCoreWorld(livePlayer) ?: return@supply
                if (liveWorld !== coreWorld) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.split_rails_changed"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                val livePair = findSplitPair(liveWorld, operationId)
                if (livePair == null || livePair.first.curve !== beforeCurve || livePair.second.curve !== afterCurve) {
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.split_rails_changed"
                    ).color(NamedTextColor.YELLOW))
                    return@supply
                }
                RailTopologyEditor.mergeEligibilityError(
                    livePair.first,
                    livePair.second,
                    signalCanBeRebuilt = { it is OperationalSpeedLimitSignal }
                )?.let { error ->
                    livePlayer.sendActionBar(Component.text(
                RailWandI18n.resolve(livePlayer, error),
                NamedTextColor.RED
            ))
                    return@supply
                }
                RailInfrastructureMetadataTopology.mergeEligibilityError(
                    liveWorld.basicWorldData.railMetadata,
                    livePair.first.id,
                    livePair.second.id
                )?.let { error ->
                    livePlayer.sendActionBar(Component.text(
                RailWandI18n.resolve(livePlayer, error),
                NamedTextColor.RED
            ))
                    return@supply
                }
                val operatingDirections = liveWorld.basicWorldData.operatingDirections
                val beforeDirection = operatingDirections[livePair.first.id]
                val afterDirection = operatingDirections[livePair.second.id]
                if (beforeDirection != afterDirection) {
                    livePlayer.sendActionBar(Component.text(
                        "两段轨道的运行方向限制不一致，无法合并",
                        NamedTextColor.RED
                    ))
                    return@supply
                }
                splitSafetyError(liveWorld, livePair.toList())?.let { error ->
                    livePlayer.sendActionBar(Component.text(
                RailWandI18n.resolve(livePlayer, error),
                NamedTextColor.RED
            ))
                    return@supply
                }

                val editPlan = preparedMerge.topologyEditPlan
                prepareUndoAndCommit(
                    player = livePlayer,
                    worldId = worldId,
                    expectedWorld = liveWorld,
                    edit = preparedMerge,
                    operation = "RailWand.mergeSplitAnchor.prepareUndo",
                    commit = { _, undo ->
                    val undoSnapshot = undo.before
                    try {
                        editPlan.commitTopology(liveWorld) {
                            editPlan.requireCurrent(liveWorld)
                            val beforeRailId = livePair.first.id
                            val afterRailId = livePair.second.id
                            val beforeLengthMeters = livePair.first.curve.totalArcLength
                            val merged = liveWorld.installPreparedRails(preparedMerge.railCreation).single()
                            editPlan.applyExplicitConnections(liveWorld, listOf(merged))
                            livePair.first.destroy()
                            livePair.second.destroy()
                            liveWorld.removeRail(livePair.first)
                            liveWorld.removeRail(livePair.second)
                            liveWorld.basicWorldData.railMetadata.replace(
                                undo.metadataRemovals,
                                undo.metadataAdditions
                            )
                            operatingDirections.replaceRails(
                                removedRailIds = listOf(beforeRailId, afterRailId),
                                replacementDirections = beforeDirection?.let { mapOf(merged.id to it) }.orEmpty()
                            )
                            OperationalSpeedLimitSignalMaterializer.materializeRail(merged)
                            OperationalDestinationService(liveWorld).migrateAfterRailMerge(
                                beforeRailId = beforeRailId,
                                afterRailId = afterRailId,
                                mergedRailId = merged.id,
                                beforeLengthMeters = beforeLengthMeters
                            )
                            val changedExistingIds = undo.metadataAdditions.keys
                                .filterTo(linkedSetOf()) { it != merged.id }
                            RailVisualBootstrap.unregisterRail(beforeRailId)
                            RailVisualBootstrap.unregisterRail(afterRailId)
                            val replacementIds = changedExistingIds + merged.id
                            val replacementMetas = undo.renderAdditionsByRail
                                .filterKeys(replacementIds::contains)
                                .values
                            replacementMetas.forEach { meta ->
                                RailVisualBootstrap.fromPersisted(liveWorld, meta)
                            }
                            undo.publishRenderSidecar()
                            val liveState = getState(livePlayer)
                            clearPendingSplit(liveState)
                            liveState.selectedRails.clear()
                            liveState.selectedRails.add(merged.id)
                            persistState(livePlayer, liveState)
                            refreshAnchorsForAllViewers()
                            pushUndo(livePlayer, WandAction.RestoreRailWorld(
                                livePlayer.world.uid,
                                undoSnapshot,
                                undo.expectedAfter
                            ))
                        }
                    } catch (error: Throwable) {
                        restoreOperatingDirections(liveWorld, undoSnapshot.operatingDirections)
                        throw error
                    }
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        "message.merge_success"
                    ).color(NamedTextColor.GREEN))
                    },
                    failure = { failedPlayer, error ->
                        plugin.logger.warning("Rail merge failed: ${error::class.simpleName}: ${error.message ?: ""}")
                        failedPlayer?.sendMessage(RailWandI18n.component(
                            failedPlayer,
                            "message.merge_failed",
                            railWandFailureReason(error)
                        ).color(NamedTextColor.RED))
                    }
                )
            },
            onFailure = { error ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail curve merge failed: ${error::class.simpleName}: ${error.message ?: ""}")
                plugin.server.getPlayer(player.uniqueId)?.let { livePlayer ->
                    livePlayer.sendMessage(RailWandI18n.component(
                        livePlayer,
                        "message.merge_curve_failed",
                        railWandFailureReason(error)
                    ).color(NamedTextColor.RED))
                }
            },
            onRetired = { heavyOperationGate.finish(player.uniqueId) }
        )
    }

    private fun splitSafetyError(coreWorld: CoreWorld, rails: List<CoreRail>): String? {
        val railIds = rails.mapTo(hashSetOf()) { it.id }
        if (coreWorld.occupyingTrainIds(railIds).isNotEmpty()) return "safety.occupied"
        return nonOccupancyTopologySafetyError(coreWorld, rails)
    }

    private fun nonOccupancyTopologySafetyError(coreWorld: CoreWorld, rails: List<CoreRail>): String? {
        val railIds = rails.mapTo(hashSetOf()) { it.id }
        if (rails.any { rail -> rail.signals.any { it !is OperationalSpeedLimitSignal } }) {
            return "safety.signal_present"
        }
        val basicData = coreWorld.basicWorldDataOrNull ?: return null
        val migratableRailObjectRefs = coreWorld.simpleAtoWorldData.destinations.all().asSequence()
            .filter { it.railId in railIds }
            .mapNotNull { (it.role as? DestinationRole.StationBerthRole)?.berthRef?.railObjectRef }
            .toSet()
        if (railIds.any { railId ->
                basicData.railObjects.objectsOnRail(railId).any { it.ref !in migratableRailObjectRefs }
            }
        ) return "safety.unsupported_operational_object"
        return null
    }

    private fun acquireTopologyEditPermit(
        player: Player,
        plan: RailWandTopologyEditPlan
    ): RailWandEditPermitAcquisition.Granted? = when (val acquired = plan.acquire(plugin, player.world.uid)) {
        is RailWandEditPermitAcquisition.Granted -> acquired
        is RailWandEditPermitAcquisition.Denied -> {
            player.sendMessage(RailWandI18n.component(
                player,
                "message.infrastructure_edit_denied",
                acquired.decision.status.name,
                acquired.decision.detail
            ).color(NamedTextColor.RED))
            null
        }
    }

    /**
     * Holds one exact edit permit while the worker captures side-state and builds the undo value.
     * The owner callback therefore never applies a value captured before competing edits were
     * excluded. Every completion path closes the permit exactly once.
     */
    private fun prepareUndoAndCommit(
        player: Player,
        worldId: UUID,
        expectedWorld: CoreWorld,
        edit: PreparedRailUndoEdit,
        operation: String,
        commit: (Player, PreparedRailWorldUndo) -> Unit,
        failure: (Player?, Throwable) -> Unit
    ) {
        val editPlan = edit.topologyEditPlan
        val permit = acquireTopologyEditPermit(player, editPlan) ?: return
        val preparedUndo = AtomicReference<PreparedRailWorldUndo?>()
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = operation,
            supplier = {
                edit.prepareUndo().also(preparedUndo::set)
            },
            onSuccess = { undo ->
                preparedUndo.compareAndSet(undo, null)
                val livePlayer = plugin.server.getPlayer(player.uniqueId)
                if (livePlayer == null || livePlayer.world.uid != worldId ||
                    getCoreWorld(livePlayer) !== expectedWorld || !editPlan.isCurrent(expectedWorld)
                ) {
                    permit.close()
                    closePreparedUndoAsync(undo, operation)
                    return@supply
                }
                val commitFailure = try {
                    editPlan.requireCurrent(expectedWorld)
                    commit(livePlayer, undo)
                    null
                } catch (error: Throwable) {
                    runCatching(undo::rollbackRenderSidecar).exceptionOrNull()?.let(error::addSuppressed)
                    error
                } finally {
                    permit.close()
                    closePreparedUndoAsync(undo, operation)
                }
                commitFailure?.let { failure(livePlayer, it) }
            },
            onFailure = { error ->
                permit.close()
                preparedUndo.getAndSet(null)?.let { closePreparedUndoAsync(it, operation) }
                failure(plugin.server.getPlayer(player.uniqueId), error)
            },
            onRetired = {
                permit.close()
                preparedUndo.getAndSet(null)?.let { closePreparedUndoAsync(it, operation) }
            }
        )
    }

    private fun closePreparedUndoAsync(undo: PreparedRailWorldUndo, operation: String) {
        plugin.platformDispatcher.runAsync {
            runCatching(undo::close).onFailure { error ->
                plugin.logger.warning(
                    "Failed to clean prepared rail render metadata for '$operation': " +
                        (error.message ?: error::class.simpleName)
                )
            }
        }
    }

    private fun splitPointSafetyError(coreWorld: CoreWorld, rail: CoreRail, arcLength: Double): String? {
        if (rail.metadata.assembly != null && railAssemblyLanes(coreWorld, rail).isEmpty()) {
            return "safety.incomplete_logical_curve"
        }
        val basicData = coreWorld.basicWorldDataOrNull
        if (basicData?.wayPoints?.all()?.any {
                it.railId == rail.id && abs(it.arcLength - arcLength) < SPLIT_OBJECT_CLEARANCE_METERS
            } == true
        ) return RailWandI18n.reference(
            "safety.waypoint_clearance",
            SPLIT_OBJECT_CLEARANCE_METERS.toInt()
        )
        if (rail.signals.any { signal ->
                signal !is OperationalSpeedLimitSignal &&
                    abs(signal.location.arcLengthIn(ViewDirection.AB) - arcLength) < SPLIT_OBJECT_CLEARANCE_METERS
            }
        ) return RailWandI18n.reference(
            "safety.signal_clearance",
            SPLIT_OBJECT_CLEARANCE_METERS.toInt()
        )
        val lockingDistance = basicData?.turnoutSafetyDefaults?.lockingDistanceMeters
            ?: plugin.physicsConfig.turnoutSafety.defaultLockingDistanceMeters
        return TurnoutTopology.splitLockingClearanceError(rail, arcLength, lockingDistance)
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
            val key = if (parseSplitAnchorId(anchorId) != null) {
                "message.split_anchor_not_deletable"
            } else {
                "message.endpoint_anchor_not_deletable"
            }
            player.sendActionBar(RailWandI18n.component(player, key).color(NamedTextColor.YELLOW))
            return
        }
        val anchor = getAnchor(player.world, anchorId) ?: run {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.anchor_missing",
                anchorId
            ).color(NamedTextColor.RED))
            return
        }

        // 清除展示实体
        removeAnchorDisplay(player, anchorId)
        removeAnchor(player.world, anchorId)
        state.selectedAnchors.remove(anchorId)

        pushUndo(player, WandAction.DeleteAnchor(player.world.uid, anchor))
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.anchor_deleted",
            anchorId
        ).color(NamedTextColor.GREEN))
    }

    internal fun railIdFromEndpointAnchor(anchorId: String): UUID? = parseRailEndpointAnchorId(anchorId)?.first

    internal fun deleteRail(player: Player, railId: UUID, state: WandPlayerState) {
        val coreWorld = getCoreWorld(player) ?: run {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.world_unavailable"
            ).color(NamedTextColor.RED))
            return
        }
        val rail = coreWorld.getRail(railId) ?: run {
            RailVisualBootstrap.unregisterRail(railId)
            RailRenderMetaStore.removeRail(player.world, railId)
            coreWorld.basicWorldData.railMetadata.remove(railId)
            coreWorld.basicWorldData.operatingDirections.put(railId, null)
            refreshAllAnchorsForPlayer(player)
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.rail_cleanup",
                railId
            ).color(NamedTextColor.YELLOW))
            return
        }
        val targetRails = logicalRails(coreWorld, rail)
        if (targetRails.any { it.trains.isNotEmpty() }) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.occupied_delete_forbidden"
            ).color(NamedTextColor.RED))
            return
        }
        splitSafetyError(coreWorld, targetRails)?.let { error ->
            player.sendActionBar(Component.text(
                RailWandI18n.resolve(player, error),
                NamedTextColor.RED
            ))
            return
        }

        val targetIds = targetRails.mapTo(linkedSetOf()) { it.id }
        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.rail_delete")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
            return
        }
        val worldId = player.world.uid
        BukkitAsyncTasks.supply(
            plugin = plugin,
            completionEntity = player,
            operation = "RailWand.deleteRail",
            supplier = {
                val editPlan = RailWandTopologyEditPlan.prepareReplacementOrRemoval(coreWorld, targetIds)
                PreparedRailDelete(editPlan) {
                    val undoBuilder = PreparedRailWorldUndoBuilder.capture(
                        worldId, worldId.toString(), coreWorld, editPlan
                    )
                    undoBuilder.removeInfrastructureOnRails(targetIds)
                    undoBuilder.removeRails(targetIds)
                    undoBuilder.build()
                }
            },
            onSuccess = { preparedDelete ->
                heavyOperationGate.finish(player.uniqueId)
                val editPlan = preparedDelete.topologyEditPlan
                val livePlayer = plugin.server.getPlayer(player.uniqueId) ?: return@supply
                if (livePlayer.world.uid != worldId || getCoreWorld(livePlayer) !== coreWorld) return@supply
                val liveTargets = targetIds.map { id -> coreWorld.getRail(id) ?: return@supply }
                if (!editPlan.isCurrent(coreWorld) || liveTargets.any { it.trains.isNotEmpty() }) return@supply
                splitSafetyError(coreWorld, liveTargets)?.let { return@supply }
                prepareUndoAndCommit(
                    player = livePlayer,
                    worldId = worldId,
                    expectedWorld = coreWorld,
                    edit = preparedDelete,
                    operation = "RailWand.deleteRail.prepareUndo",
                    commit = { _, undo ->
                    val undoSnapshot = undo.before
                    var removedDestinations = 0
                    editPlan.commitTopology(coreWorld) {
                        val destinations = OperationalDestinationService(coreWorld)
                        removedDestinations = targetIds.sumOf { destinations.removeAllOnRail(it).size }
                        liveTargets.forEach { target ->
                            RailVisualBootstrap.unregisterRail(target.id)
                            target.destroy()
                            coreWorld.removeRail(target)
                            coreWorld.basicWorldData.railMetadata.remove(target.id)
                        }
                        coreWorld.basicWorldData.operatingDirections.replaceRails(targetIds, emptyMap())
                        undo.publishRenderSidecar()
                        val liveState = getState(livePlayer)
                        liveState.selectedRails.removeAll(targetIds)
                        liveState.selectedAnchors.removeIf { railIdFromEndpointAnchor(it) in targetIds }
                        targetIds.forEach { removedId ->
                            removeAnchorDisplay(livePlayer, railEndpointAnchorId(removedId, 'A'))
                            removeAnchorDisplay(livePlayer, railEndpointAnchorId(removedId, 'B'))
                        }
                        persistState(livePlayer, liveState)
                        refreshAllAnchorsForPlayer(livePlayer)
                        pushUndo(livePlayer, WandAction.RestoreRailWorld(
                            livePlayer.world.uid,
                            undoSnapshot,
                            undo.expectedAfter
                        ))
                    }
                    val deleteMessageKey = if (removedDestinations > 0) {
                        "message.rail_deleted_with_destinations"
                    } else {
                        "message.rail_deleted"
                    }
                    livePlayer.sendActionBar(RailWandI18n.component(
                        livePlayer,
                        deleteMessageKey,
                        *if (removedDestinations > 0) {
                            arrayOf(liveTargets.size, removedDestinations)
                        } else {
                            arrayOf(liveTargets.size)
                        }
                    ).color(NamedTextColor.GREEN))
                    },
                    failure = { _, error ->
                        plugin.logger.warning(
                            "Rail deletion commit failed: ${error.message ?: error::class.simpleName}"
                        )
                    }
                )
            },
            onFailure = { failure ->
                heavyOperationGate.finish(player.uniqueId)
                plugin.logger.warning("Rail deletion preparation failed: ${failure.message ?: failure::class.simpleName}")
            },
            onRetired = { heavyOperationGate.finish(player.uniqueId) }
        )
    }

    internal fun findLookedAtRailAsync(
        player: Player,
        maxDistance: Double = 64.0,
        hitRadius: Double = 1.5,
        onResolved: (CoreRail?) -> Unit
    ) {
        val pendingOperation = heavyOperationGate.tryStart(
            player.uniqueId,
            RailWandI18n.format(player, "operation_name.rail_target")
        )
        if (pendingOperation != null) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.busy",
                pendingOperation
            ).color(NamedTextColor.YELLOW))
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
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.finding_rail"
        ).color(NamedTextColor.GRAY))

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
                livePlayer.sendMessage(RailWandI18n.component(
                    livePlayer,
                    "message.rail_search_failed",
                    railWandFailureReason(error)
                ).color(NamedTextColor.RED))
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

    internal fun findTargetAnchor(player: Player): Anchor? =
        findLookedAtAnchor(player, 64.0, 1.5) ?: findNearestAnchor(player, 3.0)

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

    private data class RailInfoDisplayPlan(val position: Vector3d, val text: String)

    @OptIn(Heavy::class)
    private fun spawnSpeedLimitDisplays(player: Player) {
        val coreWorld = BukkitPlugin.INSTANCE.worldManager.railWorlds[player.world] as? CoreWorld ?: return
        val entities = ConcurrentHashMap.newKeySet<TextDisplay>()
        speedLimitEntities[player.uniqueId] = entities
        val groups = coreWorld.allRails.groupBy { rail ->
            rail.metadata.assembly?.let { "${it.groupId}:${it.laneIndex}" } ?: rail.id.toString()
        }.values.map { rails ->
            rails.sortedBy { it.metadata.assembly?.segmentIndex ?: 0 }
        }
        val worldId = player.world.uid
        val maximumGradeLabel = RailWandI18n.format(player, "display.maximum_grade")
        plugin.server.asyncScheduler.runNow(plugin) {
            val plans = groups.mapNotNull { rails ->
                val measured = RailGeometryMeasurementService.measure(
                    rails.map { it.curve },
                    plugin.physicsConfig.track.gaugeMeters
                )
                val radius = measured.minimumHorizontalRadiusMeters
                val totalLength = rails.sumOf { it.curve.totalArcLength }
                var remaining = totalLength / 2.0
                val middleRail = rails.firstOrNull { rail ->
                    if (remaining <= rail.curve.totalArcLength) true
                    else {
                        remaining -= rail.curve.totalArcLength
                        false
                    }
                } ?: rails.last()
                val point = middleRail.curve.getPointAt(
                    remaining.coerceIn(0.0, middleRail.curve.totalArcLength)
                )
                val automaticCurveSpeed = rails.first().metadata.authoring?.curveSpeedLimitAutomatic != false
                val limit = if (automaticCurveSpeed) {
                    rails.mapNotNull { rail ->
                        RailSpeedCalculator.calculate(
                            curve = rail.curve,
                            requestedLimitMetersPerSecond = Double.MAX_VALUE,
                            allowableCantDeficiencyMeters = rail.metadata.geometry.allowableCantDeficiencyMeters,
                            derailLateralAccelerationMetersPerSecondSquared =
                                plugin.physicsConfig.overspeed.derailLateralAccelerationMetersPerSecondSquared,
                            physics = plugin.physicsConfig.railSpeedPhysics()
                        ).takeIf { it.derailSpeedMetersPerSecond != null }?.operatingLimitMetersPerSecond
                    }.minOrNull()
                } else {
                    rails.mapNotNull { it.metadata.speedLimits.value(SpeedLimitType.CURVE) }.minOrNull()
                }
                val subgrade = rails.mapNotNull {
                    it.metadata.speedLimits.value(SpeedLimitType.SUBGRADE)
                }.minOrNull()
                val speedLine = if (limit != null && subgrade != null) {
                    "CURVE ${(limit * 3.6).roundToInt()} / " +
                        "SUBGRADE ${(subgrade * 3.6).roundToInt()} km/h"
                } else null
                val text = buildRailGeometryDisplayText(
                    radiusMeters = radius,
                    maximumAbsoluteGrade = measured.maximumAbsoluteGrade,
                    speedLine = speedLine,
                    maximumGradeLabel = maximumGradeLabel
                ) ?: return@mapNotNull null
                RailInfoDisplayPlan(Vector3d(point.position), text)
            }
            plugin.platformDispatcher.runForEntity(player, action = showPlans@{
                if (player.world.uid != worldId || speedLimitEntities[player.uniqueId] !== entities) {
                    return@showPlans
                }
                plans.forEach { plan ->
                    val location = Location(
                        player.world,
                        plan.position.x(),
                        plan.position.y() + 1.8,
                        plan.position.z()
                    )
                    plugin.platformDispatcher.runAt(location) spawnTask@{
                        if (speedLimitEntities[player.uniqueId] !== entities) return@spawnTask
                        val display = player.world.spawn(location, TextDisplay::class.java) { entity ->
                            entity.text(Component.text(plan.text, NamedTextColor.YELLOW))
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
            })
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

    private fun captureRailWorldUndoSnapshot(player: Player, coreWorld: CoreWorld): RailWorldUndoSnapshot =
        captureRailWorldUndoSnapshot(
            coreWorld,
            coreWorld.allRails.map(CoreRail::save),
            RailRenderMetaStore.load(player.world),
            coreWorld.basicWorldData.railMetadata.snapshot()
        )

    private fun captureRailWorldUndoSnapshot(
        coreWorld: CoreWorld,
        rails: List<CoreRail.SavedRailV1>,
        renderMetas: List<PersistedRailRenderMeta>,
        railMetadata: Map<UUID, RailMetadata>
    ): RailWorldUndoSnapshot = RailWorldUndoSnapshot(
        rails = normalizeRailWandUndoRails(rails).sortedBy { it.id },
        renderMetas = renderMetas.sortedBy { it.railId.toString() },
        railMetadata = railMetadata.toSortedMap(compareBy(UUID::toString)),
        operatingDirections = coreWorld.basicWorldData.operatingDirections.entries()
            .toSortedMap(compareBy(UUID::toString)),
        destinations = coreWorld.simpleAtoWorldData.destinations.all(),
        wayPoints = coreWorld.basicWorldData.wayPoints.all()
    )

    private fun migrateOperatingDirections(coreWorld: CoreWorld, oldToNew: Map<UUID, UUID>) {
        val store = coreWorld.basicWorldData.operatingDirections
        val grouped = oldToNew.entries.mapNotNull { (oldId, newId) ->
            store[oldId]?.let { newId to it }
        }.groupBy({ it.first }, { it.second })
        val conflicting = grouped.filterValues { it.distinct().size > 1 }.keys
        require(conflicting.isEmpty()) {
            "replacement rails inherit conflicting operating directions: ${conflicting.sorted()}"
        }
        store.replaceRails(
            removedRailIds = oldToNew.keys,
            replacementDirections = grouped.mapValues { (_, directions) -> directions.single() }
        )
    }

    private fun restoreOperatingDirections(
        coreWorld: CoreWorld,
        target: Map<UUID, ViewDirection>
    ) {
        val store = coreWorld.basicWorldData.operatingDirections
        val current = store.entries()
        store.replaceRails(
            removedRailIds = current.keys + target.keys,
            replacementDirections = target
        )
    }

    private fun pushUndo(player: Player, action: WandAction) {
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

        override fun description(): Component = Component.text("RailWand operation")

        override fun undo(player: Player): CompletionStage<OperationResult> =
            applyUndo(player, action)

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
        is WandAction.CreateAnchor -> setOf(action.anchor.id)
        is WandAction.MultiAction -> action.actions.flatMapTo(linkedSetOf(), ::createdAnchorIds)
        else -> emptySet()
    }

    private fun WandAction.withoutCreatedAnchors(anchorIds: Set<String>): WandAction? = when (this) {
        is WandAction.CreateAnchor -> takeUnless { anchor.id in anchorIds }
        is WandAction.MultiAction -> actions.mapNotNull { it.withoutCreatedAnchors(anchorIds) }
            .takeIf { it.isNotEmpty() }
            ?.let(WandAction::MultiAction)
        else -> this
    }

    private fun applyUndo(player: Player, action: WandAction): CompletionStage<OperationResult> {
        if (!canUndo(action)) {
            return CompletableFuture.completedFuture(OperationResult.unsupported(RailWandI18n.component(
                player,
                "operation.undo_unsupported"
            ).color(NamedTextColor.RED)))
        }
        if (!isInActionWorld(player, action)) {
            return CompletableFuture.completedFuture(OperationResult.failed(RailWandI18n.component(
                player,
                "operation.undo_wrong_world"
            ).color(NamedTextColor.RED)))
        }
        return applyUndoPart(player, action).thenApply { applied ->
            if (!applied) {
                return@thenApply OperationResult.failed(RailWandI18n.component(
                    player,
                    "operation.undo_failed"
                ).color(NamedTextColor.RED))
            }
            forgetCreatedAnchorOperations(player, action)
            if (action !is WandAction.RestoreRailWorld) persistState(player, getState(player))
            OperationResult.success(RailWandI18n.component(
                player,
                "operation.undo_success"
            ).color(NamedTextColor.GREEN))
        }
    }

    private fun applyRedo(player: Player, action: WandAction): OperationResult {
        if (!canRedo(action)) {
            return OperationResult.unsupported(RailWandI18n.component(
                player,
                "operation.redo_unsupported"
            ).color(NamedTextColor.RED))
        }
        if (!isInActionWorld(player, action)) {
            return OperationResult.failed(RailWandI18n.component(
                player,
                "operation.redo_wrong_world"
            ).color(NamedTextColor.RED))
        }
        val applied = applyRedoPart(player, action)
        if (!applied) {
            return OperationResult.failed(RailWandI18n.component(
                player,
                "operation.redo_failed"
            ).color(NamedTextColor.RED))
        }
        persistState(player, getState(player))
        return OperationResult.success(RailWandI18n.component(
            player,
            "operation.redo_success"
        ).color(NamedTextColor.GREEN))
    }

    private fun applyUndoPart(player: Player, action: WandAction): CompletionStage<Boolean> = when (action) {
        is WandAction.CreateAnchor -> CompletableFuture.completedFuture(undoCreateAnchor(player, action))
        is WandAction.DeleteAnchor -> CompletableFuture.completedFuture(undoDeleteAnchor(player, action))
        is WandAction.EditAnchors -> CompletableFuture.completedFuture(undoEditedAnchors(player, action))
        is WandAction.RestoreRailWorld -> undoRailWorld(player, action)
        is WandAction.RestoreRailMetadata -> CompletableFuture.completedFuture(undoRailMetadata(player, action))
        is WandAction.RestoreDecorations -> CompletableFuture.completedFuture(undoDecorations(player, action))
        is WandAction.MultiAction -> action.actions.asReversed().fold(
            CompletableFuture.completedFuture(true) as CompletionStage<Boolean>
        ) { stage, part ->
            stage.thenCompose { applied ->
                if (applied) applyUndoPart(player, part) else CompletableFuture.completedFuture(false)
            }
        }
    }

    private fun applyRedoPart(player: Player, action: WandAction): Boolean = when (action) {
        is WandAction.DeleteAnchor -> redoDeleteAnchor(player, action)
        is WandAction.MultiAction -> action.actions.all { applyRedoPart(player, it) }
        else -> false
    }

    private fun canUndo(action: WandAction): Boolean = wandActionSupportsUndo(action)

    private fun canRedo(action: WandAction): Boolean = wandActionSupportsRedo(action)

    private fun isInActionWorld(player: Player, action: WandAction): Boolean {
        val worldIds = actionWorldIds(action)
        return worldIds.size == 1 && player.world.uid in worldIds
    }

    private fun actionWorldIds(action: WandAction): Set<UUID> = when (action) {
        is WandAction.CreateAnchor -> setOf(action.worldId)
        is WandAction.DeleteAnchor -> setOf(action.worldId)
        is WandAction.EditAnchors -> setOf(action.worldId)
        is WandAction.RestoreRailWorld -> setOf(action.worldId)
        is WandAction.RestoreRailMetadata -> setOf(action.worldId)
        is WandAction.RestoreDecorations -> setOf(action.worldId)
        is WandAction.MultiAction -> action.actions.flatMapTo(linkedSetOf(), ::actionWorldIds)
    }

    private fun undoCreateAnchor(player: Player, action: WandAction.CreateAnchor): Boolean {
        if (getAnchor(player.world, action.anchor.id) != action.anchor) return false
        removeAnchorDisplay(player, action.anchor.id)
        removeAnchor(player.world, action.anchor.id)
        getState(player).selectedAnchors.remove(action.anchor.id)
        return true
    }

    private fun undoDeleteAnchor(player: Player, action: WandAction.DeleteAnchor): Boolean {
        if (getAnchor(player.world, action.anchor.id) != null) return false
        addAnchor(player.world, action.anchor)
        spawnAnchorDisplay(player, action.anchor)
        return true
    }

    private fun undoEditedAnchors(player: Player, action: WandAction.EditAnchors): Boolean {
        if (action.expectedAfter.any { expected -> getAnchor(player.world, expected.id) != expected }) return false
        action.anchorsBefore.forEach { anchor ->
            updateAnchor(player.world, anchor)
            removeAnchorDisplay(player, anchor.id)
            spawnAnchorDisplay(player, anchor)
        }
        return true
    }

    private fun redoDeleteAnchor(player: Player, action: WandAction.DeleteAnchor): Boolean {
        if (getAnchor(player.world, action.anchor.id) != action.anchor) return false
        removeAnchorDisplay(player, action.anchor.id)
        removeAnchor(player.world, action.anchor.id)
        return true
    }

    private fun undoRailMetadata(player: Player, action: WandAction.RestoreRailMetadata): Boolean {
        val coreWorld = getCoreWorld(player) ?: return false
        val rails = action.metadataBefore.map { (railId, metadata) ->
            val rail = coreWorld.getRail(railId) ?: return false
            if (rail.metadata != action.expectedAfter[railId]) return false
            rail to metadata
        }
        val editPlan = RailWandTopologyEditPlan.captureRailScopedMutation(
            coreWorld,
            rails.map { (rail) -> rail.id }
        )
        val editPermit = acquireTopologyEditPermit(player, editPlan) ?: return false
        editPermit.use {
            editPlan.requireCurrent(coreWorld)
            rails.forEach { (rail, metadata) -> rail.metadata = metadata }
            refreshAnchorsForAllViewers()
            return true
        }
    }

    private fun undoDecorations(player: Player, action: WandAction.RestoreDecorations): Boolean {
        val coreWorld = getCoreWorld(player) ?: return false
        if (action.railIds.any { coreWorld.getRail(it) == null }) return false
        val current = RailRenderMetaStore.load(player.world)
        val currentTargets = current.filter { it.railId in action.railIds }.sortedBy { it.railId.toString() }
        if (currentTargets != action.expectedAfter.sortedBy { it.railId.toString() }) return false
        action.railIds.forEach(RailVisualBootstrap::unregisterRail)
        RailRenderMetaStore.saveAll(
            player.world,
            current.filterNot { it.railId in action.railIds } + action.renderMetasBefore
        )
        action.renderMetasBefore.forEach { RailVisualBootstrap.fromPersisted(coreWorld, it) }
        return true
    }

    private fun undoRailWorld(
        player: Player,
        action: WandAction.RestoreRailWorld
    ): CompletionStage<Boolean> {
        val result = CompletableFuture<Boolean>()
        val coreWorld = getCoreWorld(player) ?: return CompletableFuture.completedFuture(false)
        val currentRails = coreWorld.allRails.map(CoreRail::save)
        val currentSnapshot = captureRailWorldUndoSnapshot(
            coreWorld,
            currentRails,
            RailRenderMetaStore.load(player.world),
            coreWorld.basicWorldData.railMetadata.snapshot()
        )
        if (currentSnapshot != action.expectedAfter) return CompletableFuture.completedFuture(false)
        val target = action.before

        try {
            plugin.platformDispatcher.runAsync {
                val prepared = runCatching {
                    coreWorld.prepareRailWorldSnapshotRestorePreservingActiveSelections(
                        currentRails,
                        target.rails
                    )
                }
                val scheduled = runCatching {
                    plugin.platformDispatcher.runForEntity(
                        player,
                        action = {
                            prepared.fold(
                                onSuccess = { snapshotPlan ->
                                    val livePlayer = plugin.server.getPlayer(player.uniqueId)
                                    if (livePlayer == null || livePlayer.world.uid != action.worldId ||
                                        getCoreWorld(livePlayer) !== coreWorld ||
                                        captureRailWorldUndoSnapshot(livePlayer, coreWorld) != currentSnapshot
                                    ) {
                                        result.complete(false)
                                        return@fold
                                    }
                                    val railsRemovedOrReplaced = snapshotPlan.removedOrReplacedRailIds
                                        .mapNotNull(coreWorld::getRail)
                                    val missingSourceRail =
                                        railsRemovedOrReplaced.size != snapshotPlan.removedOrReplacedRailIds.size
                                    val dispatcherStillUsesSourceRail = railsRemovedOrReplaced.isNotEmpty() &&
                                        nonOccupancyTopologySafetyError(coreWorld, railsRemovedOrReplaced) != null
                                    if (missingSourceRail || dispatcherStillUsesSourceRail) {
                                        result.complete(false)
                                        return@fold
                                    }
                                    val editPlan = runCatching {
                                        RailWandTopologyEditPlan.capturePreparedSnapshotRestore(coreWorld, snapshotPlan)
                                    }.getOrElse { failure ->
                                        plugin.logger.warning(
                                            "RailWand world undo plan became stale: ${failure.message}"
                                        )
                                        result.complete(false)
                                        return@fold
                                    }
                                    val permit = acquireTopologyEditPermit(livePlayer, editPlan)
                                    if (permit == null) {
                                        result.complete(false)
                                        return@fold
                                    }
                                    val applied = runCatching {
                                        permit.use {
                                            applyRailWorldSnapshot(livePlayer, coreWorld, target, editPlan)
                                            val state = getState(livePlayer)
                                            state.selectedRails.removeIf { coreWorld.getRail(it) == null }
                                            state.selectedAnchors.removeIf { anchorId ->
                                                railIdFromEndpointAnchor(anchorId)?.let {
                                                    coreWorld.getRail(it) == null
                                                } == true
                                            }
                                            clearPendingSplit(state)
                                            refreshAllAnchorsForPlayer(livePlayer)
                                            updateAnchorDisplay(livePlayer)
                                            persistState(livePlayer, state)
                                        }
                                        true
                                    }.getOrElse { failure ->
                                        plugin.logger.warning(
                                            "RailWand world undo failed: " +
                                                (failure.message ?: failure::class.simpleName)
                                        )
                                        false
                                    }
                                    result.complete(applied)
                                },
                                onFailure = { failure ->
                                    plugin.logger.warning(
                                        "RailWand world undo preparation failed: " +
                                            (failure.message ?: failure::class.simpleName)
                                    )
                                    result.complete(false)
                                }
                            )
                        },
                        retired = { result.complete(false) }
                    )
                }.getOrElse { failure ->
                    plugin.logger.warning(
                        "RailWand world undo owner dispatch failed: ${failure.message ?: failure::class.simpleName}"
                    )
                    false
                }
                if (!scheduled) result.complete(false)
            }
        } catch (failure: Exception) {
            plugin.logger.warning(
                "RailWand world undo async dispatch failed: ${failure.message ?: failure::class.simpleName}"
            )
            result.complete(false)
        }
        return result
    }

    private fun applyRailWorldSnapshot(
        player: Player,
        coreWorld: CoreWorld,
        target: RailWorldUndoSnapshot,
        editPlan: RailWandTopologyEditPlan
    ) {
        val infrastructureRestore = planOperationalInfrastructureRestore(
            coreWorld.simpleAtoWorldData.destinations.all(),
            coreWorld.basicWorldData.wayPoints.all(),
            target.destinations,
            target.wayPoints
        )
        editPlan.commitTopology(coreWorld) {
            editPlan.requireReadyToApply(coreWorld)
            removeChangedOperationalInfrastructure(coreWorld, infrastructureRestore)

            val currentRenderMetas = RailRenderMetaStore.load(player.world).associateBy { it.railId }
            val targetRenderMetas = target.renderMetas.associateBy { it.railId }
            val changedRenderIds = (currentRenderMetas.keys + targetRenderMetas.keys).filterTo(linkedSetOf()) { railId ->
                currentRenderMetas[railId] != targetRenderMetas[railId]
            }.apply { addAll(editPlan.snapshotGeometryChangedRailIds) }
            changedRenderIds.forEach(RailVisualBootstrap::unregisterRail)

            editPlan.applySnapshotRestore(coreWorld)

            val currentMetadata = coreWorld.basicWorldData.railMetadata.snapshot()
            val changedMetadataIds = (currentMetadata.keys + target.railMetadata.keys).filterTo(linkedSetOf()) { railId ->
                currentMetadata[railId] != target.railMetadata[railId]
            }.apply { addAll(editPlan.snapshotGeometryChangedRailIds) }
            coreWorld.basicWorldData.railMetadata.replace(
                removals = changedMetadataIds,
                additions = target.railMetadata.filterKeys { it in changedMetadataIds }
            )
            val currentDirections = coreWorld.basicWorldData.operatingDirections.entries()
            val changedDirectionIds = (currentDirections.keys + target.operatingDirections.keys)
                .filterTo(linkedSetOf()) { currentDirections[it] != target.operatingDirections[it] }
            coreWorld.basicWorldData.operatingDirections.replaceRails(
                removedRailIds = changedDirectionIds,
                replacementDirections = target.operatingDirections.filterKeys { it in changedDirectionIds }
            )
            changedMetadataIds.forEach { railId ->
                coreWorld.getRail(railId)?.let(OperationalSpeedLimitSignalMaterializer::materializeRail)
            }

            RailRenderMetaStore.saveAll(player.world, target.renderMetas)
            target.renderMetas.filter { it.railId in changedRenderIds }
                .forEach { RailVisualBootstrap.fromPersisted(coreWorld, it) }
            restoreChangedOperationalInfrastructure(coreWorld, infrastructureRestore)
        }
    }

    private fun removeChangedOperationalInfrastructure(
        coreWorld: CoreWorld,
        restore: OperationalInfrastructureRestore
    ) {
        val service = OperationalDestinationService(coreWorld)
        restore.destinationIdsToRemove.forEach(service::remove)
        restore.wayPointIdsToRemove.forEach(coreWorld.basicWorldData.wayPoints::remove)
    }

    private fun restoreChangedOperationalInfrastructure(
        coreWorld: CoreWorld,
        restore: OperationalInfrastructureRestore
    ) {
        val service = OperationalDestinationService(coreWorld)
        restore.destinationsToRestore.forEach(service::register)
        restore.wayPointsToRestore.forEach(coreWorld.basicWorldData.wayPoints::add)
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

    internal fun recordDecorationUndo(
        player: Player,
        railIds: Set<UUID>,
        renderMetasBefore: List<PersistedRailRenderMeta>,
        expectedAfter: List<PersistedRailRenderMeta>
    ) {
        pushUndo(player, WandAction.RestoreDecorations(
            player.world.uid,
            railIds,
            renderMetasBefore,
            expectedAfter
        ))
    }

    internal fun selectedDecorationModel(player: Player, state: WandPlayerState): String? {
        val models = loadedRailModelOptions()
        if (models.isEmpty()) {
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.no_loaded_rail_models"
            ).color(NamedTextColor.RED))
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
            player.sendActionBar(RailWandI18n.component(
                player,
                "message.no_loaded_rail_models"
            ).color(NamedTextColor.RED))
            return
        }
        val selectedName = selectedDecorationModel(player, state) ?: return
        dialogRenderer.showMenu(
            player,
            RailWandI18n.format(player, "menu.decoration.title"),
            RailWandI18n.format(player, "menu.decoration.description", selectedName),
            models.map { model ->
                val marker = if (model.name == selectedName) "✓ " else ""
                DialogRenderer.MenuEntry("$marker${model.displayName}") { selectedPlayer ->
                    val liveState = getState(selectedPlayer)
                    liveState.decorationModel = model.name
                    persistState(selectedPlayer, liveState)
                    selectedPlayer.sendActionBar(RailWandI18n.component(
                        selectedPlayer,
                        "message.decoration_model_set",
                        model.displayName
                    ).color(NamedTextColor.GREEN))
                    showDecorationModelSettings(selectedPlayer, liveState)
                }
            } + DialogRenderer.MenuEntry(RailWandI18n.format(player, "menu.back")) { selectedPlayer ->
                showModeSettings(selectedPlayer, getState(selectedPlayer))
            }
        )
    }

    /** Shift+右键 → 打开分页设置界面。 */
    private fun showModeSettings(player: Player, state: WandPlayerState) {
        syncStateToConfig(player)
        fun openPage(pagePlayer: Player, groupId: String) {
            dialogRenderer.showConfigDialog(
                pagePlayer,
                groupId,
                textResolver = RailWandI18n.resolver(pagePlayer)
            ) { confirmedPlayer ->
                syncConfigToState(confirmedPlayer)
                confirmedPlayer.sendActionBar(RailWandI18n.component(
                    confirmedPlayer,
                    "message.settings_updated"
                ).color(NamedTextColor.GREEN))
                showModeSettings(confirmedPlayer, getState(confirmedPlayer))
            }
        }
        dialogRenderer.showMenu(
            player,
            RailWandI18n.format(player, "menu.settings.title"),
            RailWandI18n.format(player, "menu.settings.description"),
            listOf(
                DialogRenderer.MenuEntry(RailWandI18n.format(player, "menu.settings.general")) {
                    openPage(it, "wand.settings.general")
                },
                DialogRenderer.MenuEntry(RailWandI18n.format(player, "menu.settings.geometry")) {
                    openPage(it, "wand.settings.geometry")
                },
                DialogRenderer.MenuEntry(RailWandI18n.format(
                    player,
                    "menu.settings.decoration",
                    state.decorationModel
                )) {
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
        player.sendActionBar(RailWandI18n.component(
            player,
            "message.anchor_selection_cancelled"
        ).color(NamedTextColor.GRAY))
    }

    /** Convert Bukkit Vector to JOML Vector3d */
    private fun org.bukkit.util.Vector.toVector3d(): Vector3d = Vector3d(x, y, z)
}
