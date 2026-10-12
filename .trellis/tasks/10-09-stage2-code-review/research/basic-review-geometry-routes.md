# Research: Basic geometry, rail metadata and route planning review

- Query: 完整审查 Basic 分区 B：curve/**、rail/**、route/**、api/route/planning/**、api/route/RailOperatingDirections，以及对应测试和窄范围 consumer。
- Scope: internal; source-level review and independent mathematical diagnostic, no build/game validation.
- Date: 2026-10-12
- Ownership: 本文件由分区 B reviewer 维护；不修改代码、规范、问题台账、其他研究或远端。稳定 CR 编号由主会话去重分配。

## Findings

### B1 — P2 confirmed: sloped FIVE_SEGMENT and S_CURVE publish horizontal station as physical arc

Evidence:

- `Basic/src/main/java/work/art1st/javarailways/basic/curve/ConstraintSolver.kt:149-157,181-192`: 以水平 solver station 求高度/坡度，添加三维 position 后仍将相同 station 写入 RawPoint.arcLength。
- `Basic/src/main/java/work/art1st/javarailways/basic/curve/RailCurveFactory.kt:575-589,631-649`: S 曲线 cumulative 只积累 `(x,0,z)` 的距离；赋予高度后没有重新计算三维弧长。
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/curve/PrimeCurveImpl.kt:26-35,96-97`: Core 复制输入 arc，不重算；totalArcLength 直接取末点 arc。
- 对照 `RailCurveFactory.kt:955-981` STRAIGHT 和 `VerticalCurveRebuilder.kt:103-107`：都在生成三维 position 后累积实际三维距离。`StandardTurnoutFactory.kt:120-128` 使用水平弧长乘 `sqrt(1+grade²)`。
- Core `Curve.kt:8,42` 承诺弧长参数化；Core curve spec 统一米制物理位置，没有按模板改变 arc 定义的契约。水平 station 用于求纵断面本身正确，错误在于输出到 Core 时未转换。

Concrete reproductions for implementation regression:

1. 公共 factory S_CURVE：from `(0,0,0)`，to `(100,50,10)`，两端 tangent 都为 normalize`(1,.5,0)`，默认 geometry，无半径/超高。按当前 quintic 与 cubic 公式独立 Python 数值计算，252 个采样区间，水平 cumulative 为 `100.62578807817755`，相同输出位置的三维折线长度为 `112.36353911828924`，少报约 `10.446%`。这是公式诊断，不冒称运行 Kotlin 测试。
2. 已有 GenerateCurve 测试的参数形状：Success `R=20, straightA=10, L1=0, Lc=10π, L2=0, straightB=10, totalLength=20+10π, totalAngle=π/2`；from `(0,0,0)`，to `(30,(20+10π)*.5,30)`，tangents `(1,.5,0)`、`(0,.5,1)`。常坡 `0.5`，真实解析长度 `57.48475343020153`，输出 arc 仍为 `51.41592653589793`。密采样折线长度应逼近解析值；允许圆弧离散误差，不能把水平长度当 expected。
3. FIVE_SEGMENT factory 可沿现有合法 `(0,0,0)→(100,0,100)` X→Z、radius 30 fixture 将终点 elevation 改为 20、保持两端水平 tangent。水平 solver 不变，纵断面三维长度增加；需正式 Kotlin 回归确认 build success 和累计距离。

Impact: 同一 Core API 按模板给出不同的距离尺度；速度积分、制动距离、route metre、占用位置及分割长度解释不一致。普通铁路小坡度误差较小，但非零；上面的陡坡只是可观察诊断输入，修复不需要玩家预算或新框架。

Recommended small fix: 保留水平 station 供纵断面/超高求值，在最终 RawPoint 构造边界建立三维弧长；同步真实曲线边界到最终弧长（见 B3）。补充两种模板的坡道独立长度断言，不重新引入 Core 查询热路径全量扫描。

This is NOT CR-028's already-fixed duplicated shared junction / strict monotonic arc problem. No prior geometry fix was reverted or reimplemented in this review.

### B2 — P2 confirmed: reported horizontal radius differentiates against 3D arc

`RailGeometryMeasurements.kt:54-69` 先将 tangent 投影成水平单位向量，却用完整三维 `next.arcLength-previous.arcLength` 作分母。因此常坡圆弧得到 `R*sqrt(1+grade²)`，不是字段承诺的 horizontal radius。

Concrete input: `StandardTurnoutFactory.build`，radius 90，number 9，anchor tangent normalize`(1,.5,0)`，无 roll。其 Core arc 正确按三维长度增长；测量应返回约 90m，当前数学结果约 `100.62305899m`。现有 measurement 半径用例是水平圆弧，坡度用例只有零水平曲率，未覆盖交叉组合。

`Basic/.../safety/RailSpeedCalculator.kt:69-82` 同样使用水平 heading / 三维 arc；此处仅窄查确认共享公式，已告知主会话交分区 C 合并核验，避免两个 reviewer 分别修复/编号。展示 horizontal radius 的错误已静态成立；限速/脱轨计算需要分区 C 明确采用的动力学模型后定修复范围，不以本报告宣称现场安全事故。

Suggested verification: 常坡圆弧左右转、AB/BA 均保持水平 radius；同一水平 plan 改变 grade 不应把显示半径改大。独立 expected 为构造半径，而非再次调用同一测量 helper。

### B3 — P2 confirmed model mismatch: common relative break fractions do not survive lane offset / vertical rebuild

- `RailCurveFactory.kt:192-196` 将 centerline 的 logicalBreakFractions 原样交给所有 offset lane；offsetCurve `:281-286` 却分别重算各 lane 三维 cumulative。
- `BukkitPlugin/.../tool/RailWand.kt:2512-2516` 在纵断面 rebuild 后仍保留原 fractions；`:2573-2585` 随后用 fraction × 最终总长切分。
- `RailCurvePartitioner.kt:25-30` 确实按最终总弧长乘同一个 fraction，而不是保存真实 tangent/curve 接点。

Independent geometric argument: centerline 有 straight `a`、curve `c`、straight `b`，总长 `L`；平行偏移使曲线长度变为 `c+δθ`，直线长度仍为 `a,b`。正确 leading boundary 仍为 `a`，当前为 `(a/L)*(L+δθ)`。非零 offset、非零转角且存在 tangent 时必然漂移。纵断面重建若各区域伸长比例不同，也不能保留原比例。

Impact: authoring role 标记的 leading/trailing tangent 可能夹入曲线，或 CURVE 包含切线；玩家 logical lane 编辑和各段工程值不再对应真实区域。当前 RailWand 对每个 part 重测限速，所以本报告不声称单凭 role 漂移就会漏施曲线限速。

Regression should partition each lane from existing five-segment parallel fixture and verify boundary at actual zero-curvature-to-curve junction, not merely continuity/number/fraction ordering. Preserve source station-to-final-arc mapping at generation/rebuild; no new world graph required.

### B4 — P3 confirmed: dead placeholders and abandoned protection-distance payload

Safe mechanical deletion candidates:

- `curve/CurveEditor.kt:3`: 空 class；全仓 Kotlin/Java 搜索只有其声明，没有 caller、注册、serializer 或 fixture。
- `curve/StandardRailCurve.kt:422-427`: top-level SuperelevationTransition 与 `SuperelevationConfig.SuperelevationTransition` 重名重复。实际 constructor/branches/tests 使用嵌套 enum；全仓无顶层 import/use。不可删正在使用的嵌套 enum。

Separate persistent/model cleanup (NOT a mechanical delete):

- `rail/RailOperatingMetadata.kt:6-20` 的 turnoutAtA/B 与 protection/lockingDistance payload 仅在 metadata topology copy、RailWand replacement copy、legacy decode 转换和测试中传递；没有运行时 reader 将这些每端值用于准入/停车/锁闭。当前 manual turnout 使用 world defaults lockingDistance。
- `route/RouteAllocationTiming.kt:32-42` 中 defaults.protectionDistanceMeters、decisionLeadTicks 在工厂从配置写入，但无策略读取；retryTicks 和 lockingDistanceMeters 仍有真实 consumer，不能删整个 defaults。
- 这与已取消自动保护/停车区间设计一致，是残留数据模型，而不是新的 signal 功能需求。删除 persistent 字段须按真实样本/离线迁移合同处理；本 reviewer 不授权删档或升级 schema。

`StandardRailCurveBuilder` 多个 wrapper 只由测试使用，但提供实际数学 utility，不以“暂无生产 caller”直接等同死代码；若精简 API，可另评估，不混同空 placeholder。

### B5 — P3 confirmed: documentation/performance explanation has gaps

- `StandardRailCurve.kt:64` 类 KDoc 仍称 Euler；generatePoints `:340-351` 已使用 Gauss-Legendre。positionAt 仍是 Euler，应区分两个入口。positionAt KDoc `:243` 声称 z 始终 0，实际积分计算 z。
- `SuperelevationConfig` KDoc `:16` 说 design speed<=0 不使用超高，`:57` 实际回退 maxSuperelevation；需明确 0 的实际产品语义后统一，不借本次审查改变默认值。
- route-planning spec `:70-71` 的复杂度没有计入 physical slice 展开：TopologyRoutePlanner `:493-533` 每条 base arc 都重新遍历整个 normalized edge.railSlices。若一个 edge 有 R 个普通轨道，沿其存在 C 个切点，展开成本有 `O(C*R)`，而非仅列出的 `V+C+A`。合法的多段单向限制会制造这些切点。不表示已测出服务器卡顿；首先修正复杂度说明，实际必要时 query-local 预存 offsets/binary range，不添加第二张物理图。
- RailGeometryMeasurementService 的 `distinctBy(arcLength)` 在 Core strict constructor/stream 当前唯一弧长约束下无必要；RailSpeedCalculator 同样保留（C scope）。这是低频一次几何测量，不是高优性能热点；删除前核对合法自定义 PrimeCurve 实现合同，不能把重复值静默归一当恢复策略。
- Basic 的 operating-direction owner 已真实迁入 Basic，并有新规范 `operating-directions.md`；不重复报告成“未迁移”的缺陷。

### B6 — tests: useful geometry coverage, but weaknesses are concrete and local

Keep:

- Strict junction/monotonic arc historical regression、曲率/切向导数解析值、cant gradient 与禁用无缓和线超高、左右/反向安装、atomic restriction macro、不可变集合、wrong-facing zero-length marker、loop/weighted topology behavior。
- 两秒 long-curve generation timeout 是历史二次算法回归，不能因 timing assertion 一概删除；它不是性能 benchmark。
- OperatingDirectionStore subset atomicity/callback lock外执行与 collision no-mutation，非纯线程框架测试。

Specific changes recommended:

- `ConstraintSolverTest.kt:134` 的 arcLength monotonic 调用把 `to` position 同时当 `toTangent`（应为 toTan）；测试意外覆盖错误输入而不是声明的合法曲线。改参数并保留严格递增断言。
- `StandardRailCurveTest.kt:189-205` builder buildAndGenerate 非空与前面的 generatePoints 非空、首尾检查重叠，且 wrapper global 只断言非空/首arc0。可合并成一个 wrapper 等价/全局实际映射测试，删除无独立行为意义的非空项；不能以所有 constructor/math 测试都低质量处理。
- 本分区 rail tests（OperatingDirectionStoreTest、OperationalSpeedLimitSignalTest、TurnoutTopologyTest）创建 world/Basic registry 后缺成对 unregister/physics/tick close。OperatingDirectionStoreTest 关闭了自己的 executor，但不是 world owner。统一跟随既有 test fixture cleanup 规则，保留原业务断言。SimpleATO MissionRouteProgressTest 也存在清理缺口，属窄范围 consumer 线索，交所属模块审查，不扩写本分区修复。
- 新增 B1/B2/B3 的交叉几何用例比继续增加单纯“非空/无异常”断言有价值。没有执行删除或改写，没有将源码阅读计作测试运行。

## Complete file-by-file coverage inventory

Depth: 下列所有指定 production/test 文件均逐行读取完整内容（分段读取避免输出截断）；纯值文件检查 constructor/copy/validation，算法文件检查输入→几何/图→输出与失败边界，测试检查 setup/actual/expected/cleanup。不是仅对 diff 或搜索命中检查。

Production base: `Basic/src/main/java/work/art1st/javarailways/basic/`.

- `curve/Anchor.kt` — value input、copy 向量、metadata 引用；worker snapshot 由 caller 冻结，不新增 deep-immutability 框架。
- `curve/AnchorGeometry.kt` — roll metadata、yaw/pitch/roll、切线零向量 fallback。
- `curve/ConstraintSolver.kt` — 固定半径可行性、二分、auto candidates、镜像、Hermite elevation、共享接头；B1。
- `curve/CurveEditor.kt` — 空占位，B4。
- `curve/ParallelAnchorGeometry.kt` — 数量/世界/横断面/切线/间距、排序；无新增确定错误。
- `curve/RailCurveFactory.kt` — AUTO/manual、straight/五段/S、精确半径 fitting、cant profile、offset、端点 validation；B1/B3。
- `curve/RailCurvePartitioner.kt` — 分段角色、小于 tolerance 合并、split 边界；B3 输入契约。
- `curve/RailGeometryMeasurements.kt` — radius/roll/grade final measurement；B2/B5。
- `curve/RailGeometryParameters.kt` — SI engineering 参数、constructor、adaptiveStep；无热路径策略兜底。
- `curve/SavedAnchor.kt` — anchor YAML DTO/vector/file version；实际 load/write caller 仍在 adapter，无格式改动。
- `curve/StandardRailCurve.kt` — curvature/heading、Euler legacy query、accurate integration、生成、cant、global transform/wrappers；B4/B5。
- `curve/StandardTurnoutFactory.kt` — 四方向、constant grade、圆弧解析坐标、三维 arc、catalog；作为 B2 独立输入。
- `curve/VerticalCurveRebuilder.kt` — smooth/reverse/水平长度/重建、grade/frame/roll、三维弧长；B3 consumer。
- `curve/VerticalProfileSmoother.kt` — clamped Hermite、约束优先级、线性系统、有限输入；未发现当前有效输入下确定错误。dense solver低频worker，不提通用框架改写。
- `rail/LogicalRailLaneResolver.kt` — group+lane、segment ordering/completeness；无 companion lane 泄漏。
- `rail/OperatingDirectionStore.kt` — 单锁稀疏owner、相关rail原子读取、replace collision先校验、锁外变化回调、overlay/gesture；无新的确定策略错误。
- `rail/OperationalSpeedLimitSignal.kt` — train-neutral direction filtering、nullable release、lifecycle。
- `rail/OperationalSpeedLimitSignalMaterializer.kt` — destroy/remove/rebuild、两端定向release transition。
- `rail/RailAuthoringMetadata.kt` — geometry/parallel/assembly/split/authoring validation、tokens与默认值。
- `rail/RailInfrastructureMetadataStore.kt` — grouped store、setter物化、snapshot排序、单锁replace/restore。
- `rail/RailInfrastructureMetadataTopology.kt` — split lineage、parallel dissolve、lane detach/reassembly、merge eligibility/outer endpoint、atomic publish；上层完整 transaction/rollback 由 adapter负责，未把此纯metadata helper错当第二个拓扑owner。
- `rail/RailOperatingMetadata.kt` — B4 残留保护payload。
- `rail/RailSpeedMetadata.kt` — speed type uniqueness、effective min、curve safety shape；collection只读类型非深freeze作为规范一致性线索，不夸大成已发生竞态。
- `rail/TurnoutTopology.kt` — facing/trailing closure、锁闭clearance、ordinary degree2无保护；实际当前manual几何维护用途仍有效。
- `route/RouteAllocationTiming.kt` — braking+setup+reaction+margin解析式、defaults、SimpleATO consumers；B4 dead fields。
- `route/RouteBuildResult.kt` — success/failure内部值。
- `route/RouteSnapshots.kt` — ExpandedRoute→same ID/revision/topology/view-local snapshot。
- `route/TopologyRoutePlanner.kt` — immutable topology、cut graph、forbidden/replacement validation、atomic macro、Dijkstra/rebuild、port legality、canonical↔view、loops、segments；未确认绕过宏/非法路径的逻辑缺陷；B5 complexity说明。
- `api/route/planning/OperatingDirectionOverlay.kt` — defensivecopy、revision/railprojection匹配、反向禁止span。
- `api/route/planning/RoutePlanningRestrictions.kt` — DirectedRailSpan、path/replacement/restriction immutable集合与combine/source；纯中立策略边界保持。
- `api/route/RailOperatingDirections.kt` — exact topology root、requested subset存在校验、detached nullabledirection map。

Tests base: `Basic/src/test/java/work/art1st/javarailways/basic/`.

- `curve/ConstraintSolverTest.kt` — computeAngle、fixed/best solve、rounding、generate endpoints/junction/order；B6错误参数。
- `curve/ParallelAnchorGeometryTest.kt` — sign/order/crosssection/spacing。
- `curve/RailCurveFactoryTemplateTest.kt` — 完整556行：classification、SI sampling、manual radius、S curvature/cant、straight pitch、parallel左右/反向、端点和切向几何、main guard；B1/B3交叉缺口。
- `curve/RailCurvePartitionerTest.kt` — 连续几何、总长度、真实限速、endpoint/tolerance省略；B3未验证offset接点。
- `curve/RailGeometryMeasurementServiceTest.kt` — 水平圆解析半径/cant与独立grade，缺graded circle。
- `curve/StandardRailCurveTest.kt` — 完整493行：解析曲率/导数/总角、pure circle、生成/复杂度、global、wrapper、cant全部配置/gradient；B6合并候选。
- `curve/StandardTurnoutFactoryTest.kt` — 四方向、常坡/反向坡度、采样/目录；未断言graded horizontal radius。
- `curve/VerticalCurveRebuilderTest.kt` — crest/3D arc、horizontal保留、manual grade、reverse保存、平面保留。
- `curve/VerticalProfileSmootherTest.kt` — extrema、curvature continuity、horizontal约束、非法输入。
- `rail/LogicalRailLaneResolverTest.kt` — lane isolation/single/incomplete。
- `rail/OperatingDirectionStoreTest.kt` — 完整179行：并发原子子集、锁外callback、overlay、Basic standalone、immutable、碰撞拒绝/no notify、实际changes/transfer/gesture；B6 teardown。
- `rail/OperationalSpeedLimitSignalTest.kt` — 每view定向、对不同train-neutral limit、empty release；B6 teardown。
- `rail/RailInfrastructureMetadataStoreTest.kt` — grouped preservation/fixed JSON、store、duplicate/lookup；不是删除目标。
- `rail/RailInfrastructureMetadataTopologyTest.kt` — 完整245行：additive default、split/outer group、缺authoring、assembly detach、parallel dissolve、merge重建/parent；无真实Core rollback测试，此处只是metadata helper测试，不虚称全transaction覆盖。
- `rail/TurnoutTopologyTest.kt` — 三腿识别、closure、普通节点、clearance；B6 teardown。
- `route/RouteAllocationTimingTest.kt` — 独立expected216m，与实际解析式匹配。
- `route/TopologyRouteRestrictionTest.kt` — 完整281行：defensivecopy、atomic macro、内部goal不得退出、forbidden macro、goalmarker/facing、非法path、overlap拒绝。
- `contract/CoreTopologyApiConsumerCompileFixture.kt` — 别模块仅Core API检查topology的有效编译fixture；非框架测试。

Shared fixtures read in full:

- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/physics/rail/topology/TopologyTestFixture.kt` — 生产 normalizer wrapper，不复制图算法。
- `Core/src/testFixtures/java/work/art1st/javarailways/core/physics/rail/RailTopologyTestFixtures.kt` — exact三腿可控fixture，无几何猜测。
- 各 Basic test 私有 fixture 与 assertions 同文件全读；本分区没有独立资源fixture文件。

Narrow consumer/source reads (NOT whole-module coverage):

- `Core/.../PrimeCurveImpl.kt:24-118` constructor、arc storage与nearest/query语义；Core API Curve全文相关区段。
- `BukkitPlugin/.../tool/RailWand.kt:2415-2600,3081,5439` create/parallel/vertical smooth/partition/measure；其余大类不算已审。
- `SimpleATO/.../routing/OperationalRoutePlanner.kt` 全读：单topologyroot、方向/source预编译、仅一次planner、剔除zero markers。
- `SimpleATO/.../controller/SimpleATO.kt:365-420` 与 `timetable/TimetableDispatcher.kt:267-280,3052`：timing仍有实际消费者，非废类。
- `SimpleATO/.../routing/TopologyRoutePlannerTest.kt` 全读：weighted/BA/backwardsstored/turnout/endpointmarkers/loop/deterministic/stale/invalid及fixture。
- `SimpleATO/.../routing/OperationalRoutePlannerTest.kt` 全读：marker adapter、source一次调用；world unregister但无完整worldclose。
- `SimpleATO/.../routing/MissionRouteProgressTest.kt` 全读：BA投影；不把其单个测试当作所有loop progress已审/验证。
- `Basic/.../safety/RailSpeedCalculator.kt` 全读仅用于B2的公式consumer，正式归分区C。

## Related specs and document accuracy

Read workflow, task PRD/design/current execution plan (not role-isolated implement/check JSONL), and these full owner specs:

- `.trellis/spec/javarailways/basic/curve-authoring.md`
- `.trellis/spec/javarailways/basic/rail-infrastructure.md`
- `.trellis/spec/javarailways/basic/operating-directions.md`
- `.trellis/spec/javarailways/basic/route-planning.md`
- `.trellis/spec/javarailways/basic/api-boundaries.md`
- `.trellis/spec/javarailways/basic/testing.md`

Basic index and Dokka module/packages相关段也已核对；Core curve规范定位arc契约。当前规范对immutable topology+query-local restrictions及Basic公共方向所有权与源码一致，不能继续用历史SimpleATO方向owner文字当当前事实。curve原理缺少水平station→三维arc区别；route复杂度应计physical slices；每端保护payload仍写作当前metadata但缺真实policy reader。文档修复需主会话统一，不在研究角色直接更改。

## Error handling / maintainability assessment

- Good: 无mutable Core图副本；Dijkstra不回调插件；neutral restrictions预校验、stale typed failure；macro原子，路线结果不执行mutation。集合防御copy和拓扑一致性校验有真实必要，不属过度兜底。
- Good: curve工程计算留worker，frame/端点最终校验；vertical singularity/invalid stations拒绝，而非伪造几何。metadata helper准备完整 additions 后单锁发布。
- Current caveat: factory说invalid/nonfinite应返回 failure，但prepareAnchors未明确finite检查，非finite roll/position可在Core constructor抛异常。adapter已有异常处理，尚未执行复现；不提升为游戏阻塞/P1，也不建议在热路径添加通用catch-all。
- Maintainability: RailCurveFactory约1000行但已按模板/offset/vertical/cant分成清晰局部函数；不因行数建议立即拆多个service。TopologyRoutePlanner同时编译与搜索，不过纯query-local值隔离好；首选修单位/复杂度说明，不新造planner框架。

## External references

None. Review uses repository contracts, source and independent mathematical formulas; no external/version-sensitive factual claim was required.

## Caveats / Not Found

- No Gradle/JUnit/Dokka/build/deployment/remote actions ran; existing test counts are not current evidence for these findings. Python诊断只计算公式，没有读写源码文件。
- No confirmed P1 in this partition. B1/B2/B3 are correctness findings to group into geometry unit/boundary repair; B4/B5/B6 mostly nonblocking maintenance after critical paths.
- This is not Basic whole-module signoff: resource admission/topology compiler owned reviewer A, actuator/occupancy/lifecycle/persistence owned other review partition. Main owns final coverage and deduplication.
- API custom PrimeCurve implementations、Folia真实线程交错、玩家现场geometry和铁路现实标准均未现场验证。
