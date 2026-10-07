# Stage 2 implementation plan

## 2026-10-07 统一信号包一等类型格式纠正

- [x] 直接修正未发布 config-version 1：`pack.yml` 只保存包元数据；`model/`、成对同 stem 的
  `logic/*.yml`／`logic/*.js` 和 `signal/*.yml` 按安全相对路径派生稳定 namespaced modelId、logicId、signalId。
- [x] package signal 文件取代旧 combination 概念，保存玩家显示名并引用一个 modelId 与一个 logicId；
  跨包引用继续两阶段、与目录顺序无关，旧 foundation 布局没有兼容 reader/upcaster。
- [x] catalog/loader/runtime 测试改用一等 signal 文件。
- [x] BukkitPlugin 提供 worker-safe 的公共普通模型 YAML compiler；旧 model-only loader 与统一信号包共用其
  parts/inline/import、场景、variant 和 placement 编译。统一包同包 model import 只导入 identity-grouped parts，
  不继承目标 default/variants/placement；循环、缺失引用、非法 variant、不可变结果与捕获后不重读均有聚焦测试。
  Car/Rail 专用包未迁移；生产启动、世界绑定和 signal epoch 不在本提交。
- [x] signal epoch 内存绑定已显式区分 package `signalId` 与已放置逻辑 `deviceId`；observation publication/plan
  也使用 device 语义命名，不按同名、模型或路径猜绑定。本批保持世界 schema 1 不变；持久化类型引用和启动 catalog 后续单列。

## 2026-10-07 JS 包与树形前视决策收敛（本提交仅文档）

- [x] 历史文档批次曾将目标包收敛为 `pack.yml`、`model/`、`logic/` 及清单组合；其布局已由上方
  一等 signal 文件格式取代，稳定 namespaced 跨包引用要求保持不变。
- [x] 确认复用 GraalJS 技术栈而不导入 `BukkitPlugin` 私有 runtime；实际规则在物理快照之后的同步信号阶段执行，
  准备工作才可异步，不规划 context pool、watchdog 或异步求值结果交接。
- [x] 将自动前视目标改为完整分支树，扫描距离逐分支累计；删除节点、分支、时间、内存和整波预算要求，
  明确包作者负责脚本性能与终止。
- [x] 将遍历兜底收敛为逐分支遇环停止并向 JS 报告终止原因；兄弟分支继续，不使用 DAG 全局拒绝、SCC、
  fixed-point 或陈旧 permissive 输出。
- [x] 建立包发现／跨包解析与 GraalJS runtime 基础，保持现有 Java/Kotlin provider 可用；严格 current schema 1、
  两阶段稳定 ID 解析、捕获 JS 源、fresh realm 沙箱、exact-once setter 和 detached async 取消均由聚焦测试覆盖，
  未在同一提交接入树形 observation、世界或插件启动。
- [x] 编译不可变树形 observation plan：从已准备的有向区间连接图逐路径展开，状态身份包含区间、方向、入口和保护进路上下文；
  逐分支累计区间/连接距离，连续短区间扫描到距离边界，分叉保留全部候选，汇合保留独立 occurrence。
  道岔 continuation 与保护进路作为运行期筛选元数据保留；逐分支哈希索引发现回边，仅生成该分支的 `cycle` 叶，
  循环段不进入下游信号依赖集合，兄弟分支继续。编译结果另汇总区间、非循环信号、道岔和保护进路依赖，
  不读取物理 epoch、执行 JS、选择进路或修改世界。聚焦 `SignalObservationPlanTest` 10项通过。
- [x] 从真实 world section/topology publication 构造连接图：复用 exact `TopologyState`、固定闭塞快照、保护区域编译快照、
  保护进路选择索引和既有 traversal/continuation，不建立第二套物理图。准备时计算普通轨道间距、所有合法道岔分支、
  保护进路 choice、track-end/route-unresolved/topology-unavailable 及有向环；同入口多逻辑信号和附属显示 root 保持共享。
  图作为不可变值随完整 runtime publication 安装，普通定义准备与局部拓扑重验失败均保留旧 publication。
  正长度无区间环只生成命中分支的 `cycle` 叶并保留兄弟分支，零长度环拒绝；未受影响保护进路可在外层 exact-topology
  index 下跨无关 revision 复用。独立复核修正旧 revision 误拒绝和正长度环整图拒绝；完整 SimpleSignals 486 项测试、
  Java consumer、插件 JAR 和 diff check 通过。本批不读取 epoch、不执行 JS、不发布规则结果，不改 schema/version。
- [x] 将显式内存绑定接入既有同步 signal epoch：同帧占用／道岔／保护进路事实投影为树形 JSON，按非循环下游依赖
  顺序每设备至多求值一次；完整 setter 结果及 model variant 校验后原子替换快照，异常／未知 variant／不可用物理状态收紧为0，
  不保留上一 epoch 的许可。既有 Kotlin provider 仍作为未绑定设备的默认实现，附属显示继续读取主设备同一结果。
  过渡期调度只允许 JS 设备读取先完成的 Kotlin 下游；若未绑定 Kotlin 消费者位于 JS 替换设备上游，低频编译按传递
  consumer 闭包拒绝该绑定，避免同 epoch 读到被随后覆盖的旧实现结果。已绑定设备仍会产生一次既有 Kotlin 基线开销，
  本批不扩成第二个或统一调度器；没有第二次世界／拓扑扫描。
  离线功能门禁中的 Graal 调用不是吞吐／延迟基准；fresh Context、树投影和过渡期重复 Kotlin 求值的生产规模成本
  仍待代表性包基准及在线观测，不能据单元测试通过宣称 tick 性能已验收。
- [ ] 后续独立实现批次：把 package signal 类型引用加入尚未发布的世界 current model并接入插件启动 catalog；
  当前不从旧 `signalId`、modelId 或文件路径猜测绑定，不改 schema version，也不声称玩家已能在游戏中选择新类型。

本节前四项属于 2026-10-07 的文档收敛提交；随后独立 foundation 提交实现包 catalog、Graal 依赖和 JS runtime，
仍不修改持久化版本或兼容路径，也不声称树形扫描、world/epoch 接线或游戏内放置已经可用。
精确契约由 [JS 规则 owner 文档](../../spec/simple-signals/backend/js-signal-rules.md)维护。

## 2026-10-06 JS 信号规则契约批次（仅文档）

- [x] 记录用户选择 JS 处理距离敏感逻辑，最远扫描距离只控制输入，不新增 Kotlin 距离条件表。
- [x] 根据真实规则／区间边界编写 [TARGET 契约](../../spec/simple-signals/backend/js-signal-rules.md)：工作方式、只读输入、setter、失效、内部机制、复杂度、伪代码与测试断言。
- [x] 完成 [源码对照](research/js-rule-runtime-source-map.md)与独立文档审查；修正“输入相同必不重算”的过度承诺，保留现有事件唤醒和输出不变停止传播语义，不新增输入缓存。
- [x] 后续评审已于 2026-10-07 决定包目录、同步执行阶段、完整树形分支、无通用预算及遇环停止；
  精确 loader/API 字段仍由 owner 契约和独立实现批次确定。

本批不修改运行代码、依赖、存档版本、Wand 或循环策略；已有显式下游距离输入不等于已实现最远距离自动扫描。
验证仅为源码／文档审查、`git diff --check` 与 curated context 校验（implement/check 各12项通过）；
既有大型 index/model 文档仍有注入截断警告，专题 JS 草案单独列入 manifest。未执行构建、JS 引擎测试或 Folia 验证。
本批单独提交后停下供用户检查，Stage 2 不归档。

## 2026-10-06 未发布格式版本纠正

- [x] 按用户要求把 SimpleSignals world-definition schema 从误升的3恢复到1，保留默认进路删除和两端独立规则。
  只改本插件版本常量、current fixture、版本拒绝回归与对应文档；不改其他模块版本或增加2/3兼容分支。
  先前批次日志中的2/3仅为当时实际执行的历史记录，不代表发布/冻结基线，不能作为后续升版依据。
  门槛见[版本号与发布基线](../../spec/javarailways/shared/versioning.md)。生产仅修改codec版本常量；6个测试文件、4份固定JSON同步schema 1。
  方向fixture拒绝0/2/3/99；缺方向仍为FORWARD，双端/model/attached内容保持；schema 1包含已删除defaultRouteIds仍严格拒绝并保留源文件。
  离线`:SimpleSignals:test :SimpleSignals:verifyPluginJar`实际通过：43套/450项，零失败、错误或跳过，42秒。
  生产与测试Kotlin编译、测试资源处理、JAR、插件隔离和测试本轮实际执行；其余依赖复用缓存，本次未重跑Java API consumer。
  独立checker确认四份fixture除版本3→1外全部值不变、跨模块代码diff为空、21个文档链接与10+10上下文引用有效、managed/state块不变，`git diff --check`通过。
  日志`/tmp/javarailways-schema-one-check.log`；含既有构建警告和一次physics overload，不作为性能证明。未部署、未迁移服务器文件；用户已批准提交并继续核对余项，Stage 2仍进行中。

## 2026-10-05 信号对照修正（本轮优先）

- [x] 删除默认进路，并要求固定进路/当前入口信号都具有匹配的有效授权请求。已独立检查，本批提交后停下供用户审阅。
  - 6个生产文件删除默认配置、索引、专用footprint与冲突计算；没有匹配请求或请求其他进路时固定信号也停车。
  - 当时9个测试文件及3份fixture同步sidecar schema 2（误升，现由上方版本纠正批次取代）；不改宿主存档、Basic/ATO/Core API、实际资源仲裁或模型包版本。
  - 聚焦10套/148项实际执行通过。独立完整离线门禁`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`通过：41套/429项，零失败、错误或跳过，39秒。
    实际执行测试编译、完整测试、`verifyPluginJar`及Java consumer编译；生产编译/JAR复用先前实现构建结果。
    `git diff --check`通过，未配置独立lint任务；日志`/tmp/javarailways-request-only-check.log`。
    测试组合真实运行类与构造观测/准入帧，不等于live Folia或客户端验证；不声称出口净空已实现。
- [x] 实现基于列车长度与前方停车位置的保护区域出口净空，不以取得后继闭塞代替净空证明。
  - 2026-10-06 完成独立检查：区域source声明中立入口/出口；SimpleATO以同帧车身保守跨度和原有安全距离扩长请求floor、单次前视，
    使用既有授权/停车/站台/前车/信号约束证明净空并立即安装入口等待。共享信号经已有串行epoch接收变化，必须同时满足匹配请求、授权、设备和净空；只精确排除本次入口自身红灯，不删除最终保护约束。
  - Core公开已有原子Train frame；两端共用既有ActiveRoute中的保守长度上界，吸收车钩解算抖动。真实Core直线100步证明raw变化但floor实例及共享callback稳定，增长立即收紧、旧epoch不能缩短更新上界、缺/错帧不借缓存。
    不改Basic仲裁/真实车尾释放、双向闭塞、JS/模型或存档版本，不新增全世界扫描、资源registry或第二个planner。
  - 聚焦105项全部通过，含7个真实Core周期controller/Basic/signal组合：前视外红灯、同点第三方限制、短运营终点、相邻区域恢复、前车以及Dispatcher停止后的下一tick响应。
    Java API consumer编译和插件JAR隔离实际执行通过。独立checker仅修正2处旧测试fixture，不放宽runtime。
  - 最终离线代码/测试门禁通过：1764项，1757通过、7项基线skip，零失败/错误。
    Core 209（skip6）、Basic 389（skip1）、SimpleATO 351、BukkitPlugin 350、SimpleSignals 434、TunnelBridgeBuilder 31；
    前五个非Signals模块已在`--rerun-tasks`轮实际执行，最终复用相同源码结果，Signals最终434项实际执行。
    日志：`/tmp/javarailways-exit-clearance-full-check2.log`、`/tmp/javarailways-exit-clearance-final-gate.log`。
  - Core HTML Dokka已生成；Javadoc缺离线缓存`org.jetbrains.dokka:javadoc-plugin:2.2.0`，未验证，没有下载或改构建配置。
    `git diff --check`通过；真实Folia/客户端及规模性能基准未验证。提交后停下供用户审阅，Stage 2仍进行中。
- [x] 双向闭塞的两入口独立规则，保持一个共享资源。
  - 2026-10-06 用户批准本批：先补[所属契约](../../spec/simple-signals/backend/bidirectional-fixed-block.md)，再实现与独立检查。
    规则按作者完整路径的 FORWARD/REVERSE 绑定；每入口最多一个主信号，各自配置下游、提前时间、输出和显式模型。
    未配置该端不增加信号速度限制；已配置但无有效结果则入口0。共享一份占用与block资源，复用单向图、检查绑定和正常编辑事务。
    当时世界定义文件schema3、拒绝旧2（误升，现由上方版本纠正批次取代）；不改宿主API/世界文件、JS、信号Wand或多闭塞单线协调。
    研究与准确改动面见[方向规则研究](research/directional-fixed-block-signals.md)。九个生产文件改入口键、有向几何视图和声明索引；
    反向路径借用已有缓存，规则共享同一占用provider，保留方向有效性、原资源身份、车尾释放与净空门控。
  - 实现聚焦8套/160项实际执行通过，生产与全部测试Kotlin编译通过。独立checker未发现生产缺陷；
    补2项真实组合回归，验证Basic方向变化经signal epoch同时失效/恢复两端且无变化IDLE，以及同ID换入口fresh binding/旧新编辑范围。
    补充聚焦2套/21项实际编译与执行通过；总计新增16项测试。
  - 最终独立离线`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`通过，
    排除WebUI前端安装/构建，缓存Gradle9.5/JDK17+25，无下载和构建配置变化。
    43套/450项全部实际执行，零失败、错误或跳过；`verifyPluginJar`实际执行，Java consumer、最终编译与JAR构建复用未变缓存。
    直接检查JAR确认方向类型入包、无宿主类；`git diff --check`与9+9项上下文验证通过，没有独立lint任务。
    日志`/tmp/javarailways-directional-signals-focused3.log`、`/tmp/javarailways-directional-signals-review-focused.log`、
    `/tmp/javarailways-directional-signals-final-check.log`；XML在SimpleSignals/build/test-results/test。
  - 契约/README同步CURRENT，保留合法删除后缺失下游Unavailable。真实Folia/客户端与规模性能未验证；
    测试日志包含一次physics overload及既有Gradle/native警告，不能据测试通过声称延迟已验证。
    本批提交后停下供用户检查；既有TODO/docs-site、另一任务及未跟踪资产不纳入提交，Stage2仍进行中。
- [x] 对已有距离输入与信号循环依赖做有界方案评审，不重复添加输入模型或写死国家规则。
  - 2026-10-06 完成[距离与循环依赖评审](research/distance-and-cycle-rule-review.md)，独立checker核对实际编译器、规则输入及拒绝顺序。
    距离/区间长度已有；当前具体规则仅按下游速度阈值选档，没有距离自适应。物理环线与信号最终输出环是不同合同，后者在候选prepare中有意拒绝。
    当时距离配置策略与JS仍为待选方案；用户现已选择JS，见本文件顶部契约批次，不再扩展距离条件表。
    JS尚未实现；完整循环策略仍另外评审，不新增固定点、陈旧输出或重复扫描，不能把研究完成等同于实现。
    本批仅静态源码与文档检查，未改运行代码或执行构建；上述450项属于已提交版本纠正批次，不冒充新策略验证。

本批范围与验收见[修正批次](research/signal-comparison-corrections.md)。下文的历史默认进路完成项已被新决定取代。

## Start gate

- [x] Stage 1 public resource, restriction, inspection, applied-plan dependency/edit and owner-lifecycle APIs are complete, reviewed and archived.
- [x] Freeze the module/plugin/product name as `SimpleSignals`; the scaffold fixes the Java package root as `work.art1st.javarailways.simplesignals`, while persisted type IDs and script identifiers remain separate API decisions.
- [x] Load SimpleATO, Basic, Bukkit, persistence, interaction/render and shared module specs.
- [x] Keep runtime code current-model only. If pre-release protection definitions need importing, extend the separate auditable world-format migrator with dry-run and recovery rather than adding a Stage 2 runtime compatibility path.
- [x] Re-run the PRD convergence pass against the completed Stage 1 contracts and present the final Stage 2 summary for fresh implementation approval.

## Deliverable 1: complete SimpleATO extraction

Current extraction progress:

- [x] Move Timetable model, validation, runtime store, extension registry, persistence, simulation helpers and Bukkit callers into SimpleATO; remove the Basic owner without aliases or runtime fallback.
- [x] Move Line model, revisioned store, planner, infrastructure persistence, isolated simulation capture and Bukkit callers into SimpleATO; restore after SimpleATO Destination/Station materialization without aliases or runtime fallback.
- [x] Move Destination/Station model, stores, StationCall runtime/resolver, berth allocation, infrastructure persistence and Bukkit callers into SimpleATO; keep only the derived route-anchor projection in Basic WayPointStore, with no Basic aliases or mirror stores. Move Depot reset coordination to SimpleATO to avoid a reverse dependency.
- [x] Move Depot/DepotStore, rolling-stock assignment, runtime persistence and Bukkit callers into SimpleATO; keep Basic runtime as a strict revision/epoch pairing document without a mirror ledger or compatibility reader.

- [x] Inventory remaining ATO/operations production owners and callers in Basic/BukkitPlugin.
- [x] Move ATO mission, route progress, planner, constraint manager/types and world coordinator/diagnostics into `SimpleATO`; retain Basic's generic dynamics/actuation, route/perception snapshots, RailObject store and physical safety contracts shared with manual driving. Preserve manual fail-close through a generic Basic world safety interlock driven by the SimpleATO coordinator.
- [x] Delete the legacy `AtoProvider` registry/boundary/controller-switching path instead of migrating or replacing it. Signal extensions use the Stage 1 inspection and namespaced restrictive-output contracts.
- [x] Finish the separate operating-direction authoring/persistence seam: SimpleATO owns the sparse store, topology-bound overlay, authoring, route predicate and infrastructure persistence; Basic has no direction field or fallback owner.
- [x] Collapse Stage 2 persistence numbering to the real boundaries: infrastructure migrates once from outer V1/Basic V1 to outer V2/Basic V2/SimpleATO V1; Basic runtime moves once from V2 to V3 while additive SimpleATO runtime fields remain V1. Keep the converter pure and leave packaged file migration/recovery to the 09-24 task.
- [x] Create `SimpleATOBukkit` as an independent adapter library module with direct main-plugin composition and no descriptor, second `JavaPlugin` or internal service discovery. Package it through the main plugin's project dependency, and move SimpleATO Core-slot startup wiring plus SimpleATO diagnostics into it as the first real slice.
- [x] Audit the platform presentation boundary: keep the existing generic latest-only scheduler with its actual
  `BukkitPlugin` consumers, keep concrete signal rendering in `SimpleSignals`, and do not turn `SimpleATOBukkit` into a
  generic platform bridge merely to share one helper. Move only platform behavior with a real SimpleATO owner/caller.
- [x] Remove each old production owner after its replacement is wired; a final source audit confirms Basic has no Destination/Station/Depot/Line/Timetable/operating-direction/ATO-policy store or compatibility facade, while BukkitPlugin only composes or presents the SimpleATO-owned stores. Basic's remaining RailObject/WayPoint projections and generic pathfinding/actuation/resource code have distinct shared owners; protection-region ownership has now moved to SimpleSignals.
- [x] Move the immutable-topology search implementation into Basic and add the neutral forbidden-span/path-replacement contract. Compile SimpleATO operating direction into the same restrictions; keep complete replacement paths atomic in the query graph.
- [x] Add owner/world-scoped route-planning source registration through the existing Bukkit signal owner registry. Compile one stable source snapshot once per immutable-topology query, combine it deterministically with operating directions, and reuse the unified owner unregister/world lifecycle without a second token framework.
- [x] Migrate Mission, Station, Line, Timetable and the Bukkit route diagnostic to one SimpleATO immutable-topology planning seam, preserve precomputed timetable routes and typed direction failures, then delete the live-`RailView` planner and compiler.
- [x] Integrate the first concrete fixed-block signal source with the existing SimpleATO inspection declarations, single shared perception scan and dynamic braking-distance comparison; do not build another inspection runtime. Fixed blocks and protection regions compile route-local authority floors through existing inspection bindings, while the production fixed-block rule uses persisted configurable outputs and downstream thresholds.
- [x] Wire injected `SimpleSignals` fixed-block rule output through the existing namespaced most-restrictive restriction pipeline; signal-epoch invalidation marks only current same-source inspection bindings, each callback publishes from the complete active declaration set, and plugin output cannot widen Basic grants or existing ATO/ATP limits. Production concrete rule bindings remain a separate item below.

## Deliverable 2: SimpleSignals foundation

- [x] Scaffold the independent Java/Kotlin plugin, descriptor, compile-only host dependencies, capability checks and separate JAR packaging. The descriptor hard-depends on JavaRailways and declares the Bukkit API target; the current host exposes typed services rather than a numeric signal-API version, so startup validates the five required service capabilities instead of inventing string-version comparison.
- [x] Establish the planning-only plugin-owned world runtime: each exact host world load gets fresh candidate catalogs and exactly one namespaced fixed-route source; startup publishes restored definitions or an unwritten empty revision 0 when missing, corrupt/unregistered host worlds remain locally disabled, and topology changes fail closed until explicit republish.
- [x] Add the strict current-model SimpleSignals world-definition sidecar with stable namespaced IDs, canonical UUID/AB positions and complete fixed-route traversals. The plugin loads primary/backup and compiles the full candidate against the current host topology before publishing a usable planning source; missing starts empty without writing, while corrupt/orphan/rejected input installs only a rejecting fail-closed source and no usable runtime. Writes use same-directory temp, last-known-valid backup rotation and atomic-move fallback with no runtime legacy reader.
- [x] Move `ProtectionRegionDefinition`, boundary/catalog, graph-cut/interior derivation, route-window matching and validation tests out of Basic. The plugin catalog atomically publishes one topology/definition-revision snapshot and indexes candidate regions by rail; Basic still performs independent structural route validation.
- [x] Define SimpleSignals-private fixed protection route tables and compile each region table atomically into one neutral Basic `PathReplacement`. Publication validates gate correspondence, direction, continuity and interior containment; the cached source rejects stale topology/region publications and is registered by the planning-only world runtime.
- [x] Add the plugin-local protection definition patch transaction: expected-revision create/update/delete composition, fresh prepare/compile, exact old+new affected-rail edit permit, persistence-before-publication commit and typed failure diagnostics. Retained region/table revisions are monotonic, and every failure preserves the old disk/live state.
- [x] Connect the completed authoring transaction and diagnostics to SimpleSignals-owned commands: inspect/export/validate/apply via isolated strict-current-JSON drafts, shared pure validation, baseline revision and the single apply transaction. See [command contract](../../spec/simple-signals/backend/authoring-commands.md). Native Paper worker/entity/global scheduling, reload/disable identity guards, six-kind CRUD/duplicate validation, zero-mutation dry-run and bounded blocker diagnostics are tested. Final offline `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`: 161 tests passed, zero failures/errors/skips; JAR isolation verified. Real Folia smoke remains pending. Guided Wand remains a separate slice.
- [x] Remove Basic's protection-region request/claim/validator API and `PROTECTION_REQUIRED` policy after confirming the generic route-resource runtime is the production path. Keep and rename the generic tail-release controller used by that runtime.
- [x] Add plugin-owned fixed-block definitions, exact-topology compilation, route-local reverse index, derived Basic occupancy geometry, protection-interior non-overlap validation and current-model persistence. The patch model validates fixed-block edits, while live geometry publication remains gated by the atomic source-replacement item below.
- [x] Add the first plugin-local common section observation slice for fixed blocks: one host physical-epoch source publishes immutable baseline/changed occupancy and directed crossings, while unchanged blocks remain idle. Protection regions join only with concrete interlocking rules.
- [x] Define an atomic host transaction for live fixed-block geometry source replacement. Candidate sources compile before the caller commit callback; fixed-block sidecar/publication/occupancy runtime and host areas cross one simulation barrier, while edit permits still reject operationally protected rails before save. Wand/command callers remain separate work.

## Route and resource integration

- [x] 轨道公共方向归属 Basic，未部署存档直接修正；实现和独立验证完成：
  直接移动稀疏存储、中立读取值与通用方向限制转换；接通寻路、宿主 API、线路编辑/撤销、持久化和信号更新，删除旧 owner。
  Basic infrastructure保持format2并新增默认空方向列表；SimpleATO删除字段后保持format1。
  更新当前固定fixture，已有历史metadata转换只更正输出owner，不新增旧方向转换、兼容reader或中间版本测试。
  不实施09-24文件发现/dry-run/apply/自动升级全流程。
  旧已完成方向迁移记录仍属历史基线；新机制见[方向契约](../../spec/javarailways/basic/operating-directions.md)。
  - `BasicWorldData.operatingDirections` 是唯一稀疏存储；`OperatingDirectionStore`、只读 `RailOperatingDirections`、
    中立 `OperatingDirectionOverlay`、方向编辑换算和 `SavedOperatingDirectionV1` 迁入 Basic。
    SimpleATO 的旧类、字段和方向存档列表直接删除。Basic 纯搜索器仍只接收显式输入，没有新增搜索、兼容别名或双写。
  - 生产和隔离世界先创建已有 `SignalEpochRuntime`，构造 Basic store 时传入它的普通变化回调。
    保留相同值 no-op、锁内原子子集/轨道替换与锁外通知，没有 Basic→SimpleATO/Bukkit imports、新总线、监听生命周期或逐 tick 轮询。
    SimpleATO 寻路、初始方向、线路编辑/撤销与宿主信号读取全部使用 Basic；闭塞和手动控制机制不改。
  - Basic restore 在写入任何 Basic 数据之前检查非法 UUID、重复解析后 UUID 和缺失轨道引用；capture 拒绝孤儿记录。
    固定 current split JSON 验证方向只写 Basic、格式仍为2/1，Destination/Line/revision、Core 与三个 runtime payload 保持相等。
    独立复核补3项宿主读值回归，覆盖缺项/多项/错拓扑、读取中换拓扑与世界重载；针对性24项通过，无需生产修复。
  - 最终串行离线门禁：`:Basic:test :SimpleATO:test :BukkitPlugin:test :SimpleSignals:check`
    `:BukkitPlugin:compileSignalApiConsumerJava :WebUI:compileJava :WebUI:test`，
    排除`:WebUI:installFrontend`和`:WebUI:buildFrontend`，缓存JDK25/Gradle9.5，无下载或构建配置修改。
    Basic63套/381项（380通过、1既有跳过），SimpleATO44套/334通过，BukkitPlugin82套/336通过，
    SimpleSignals31套/306通过，WebUI4套/11通过；总计224套/1368项，1367通过、零失败和错误。
    唯一跳过是基线已有的`PathFinderTest$FindPathLoop`灯泡线测试，`@Disabled`未改变。
  - 最终BukkitPlugin tests和`verifyPluginJar`实际执行，其余未变输入复用此前通过结果；Java consumer此前实际编译、最终复用缓存。
    JAR和`javap`证明宿主方法返回Basic方向值，Basic包含迁入类，SimpleATO无旧owner类，SimpleSignals不夹带宿主类。
    独立复核无剩余问题；`git diff --check`和10项curated上下文校验通过。
    日志：`Basic/build/operating-direction-review-focused.log`、`Basic/build/operating-direction-review-final-gate.log`。
    没有live Folia/客户端或大规模性能验证；整体Stage2继续进行，其他未提交内容不纳入本批。

- [x] 落实在线编辑的统一失效策略：运营方向失配与结构失效均禁止 ATO 驶入，保留区间、进路及设备配置。
  - 已完成：方向失配明确拒绝 ATO、删除旧低速配置/分支；区域边界编辑不完整时局部禁行并保留配置，补齐后逐条验证。
  - 2026-10-05 完成事务影响驱动的跨层修正：Core 成功事务在新拓扑可见前通知受影响轨道；Basic/SimpleATO
    仅使相交依赖不可用，SimpleSignals 在 worker 重验关联区间并原子替换来源。无关冻结路线、授权、设备准备、
    继续申请和车尾释放不因世界 revision 增长而过期。编辑候选一致性、世界/列车/路线身份及新观测一致性检查保留。
    范围见[实施边界](research/section-topology-revalidation-batch.md)，机制归[局部重验合同](../../spec/simple-signals/backend/section-topology-revalidation.md)。
  - 独立审查发现并已修复：重叠双方遗漏修复依赖、失效保护进路丢失已知占用、后台来源副本覆盖旧来源较新的信号、
    连续无观测替换遗漏待更新信号，以及首次申请/折返使用旧物理观测。真实拒绝场景保留，不仅删除全局版本断言。
  - 最终离线门禁通过：Core 46套/208项（6跳过）、Basic 63套/389项（1跳过）、SimpleATO 44套/339项、
    BukkitPlugin 84套/350项、SimpleSignals 35套/358项、WebUI 4套/11项；合计276套/1655项，1648通过、
    7项基线跳过、零失败或错误。最后一次实际执行 SimpleSignals tests/编译/JAR隔离，其余未变输入复用先前结果。
    Java consumer、主插件shadowJar、diff check和12/14项上下文校验通过；没有独立Kotlin lint任务。
    日志 `/tmp/javarailways-local-topology-verified-gate.log`，新增局部重验类12项通过，独立审查无剩余阻塞。
  - 边界：没有真实Folia/客户端或压力测试。启动时无效存档仍可能使该世界安装失败；分割/合并后的信号坐标自动迁移、
    缺失附着轨道后的模型编辑恢复尚未实现。通知仍扫描待处理集合和浅复制局部映射，不能宣称所有提交成本只随命中数增长。
  删除 `disabled-block-speed-kmh` 和仅为低速降级存在的逻辑，不增加兼容策略；按实际调用清理相关接口。
  复用原有方向读取、变化通知、路线校验和资源生命周期；验证正反向、多轨道、无信号规则的闭塞均不能绕过拒绝，
  修复后恢复、旧占用不丢失、无关区间及手动驾驶不受影响。契约目标见[双向固定闭塞合同](../../spec/simple-signals/backend/bidirectional-fixed-block.md)。

- [x] 历史基线 `8ff013f6` 的方向一致性与低速降级修正，完成一个可独立审查的三模块批次；下述存储归属已由 Basic 方案替代，低速行为现已被上方已实现的新决策取代，以下仅保留历史证据：
  - SimpleATO 仍唯一持有运营方向。宿主公开接口一次读取相关轨道的完整方向子集；中央写入只通知实际变化轨道，
    沿用信号 epoch 的合并集合与轨道兴趣索引，无变化和无关编辑不唤醒相关来源，不轮询所有闭塞。
  - SimpleSignals 检查闭塞的每段 authored facing：单向要求每段仅允许该方向，双向要求每段均允许两向。
    反向几何用于识别经过而非自动许可；有效单向的反向资源申请在新增预留和设备准备之前明确拒绝。
    任一段失配禁用整个闭塞的正常 token、入口及保护区域后继授权下限，但保留定义、静态编辑依赖和已有 claim。
  - 既有检查绑定缓存带起止位置的区间限速，限制适配器继续使用原有制动曲线：入口前减速、内部维持、
    车头越过出口后结束。内部起步、反向和重复经过使用自己的路线范围；车尾仍按原队列释放资源，不因限速结束而提前释放。
  - 启动配置 `disabled-block-speed-kmh` 默认20.0，有限正数校验后一次换算；不提供热重载。
    已知降级对下游公开有效速度，包括未配置信号规则的资源型闭塞；未建立观测基线、未知或过期结果仍为0。
    降级限速仅约束ATO，手动驾驶及既有安全行为保持原样，ATS延后。
  - 普通物理更新复用不可变控制状态map；只有首次基线或相关方向修改才复制并更新它。
    独立复核进一步把区间声明ID集合缓存到binding，只查实际失效ID，没有变化时不额外遍历范围。
    另用先失败后通过的真实source/InspectionRuntime回归修复入口限制与区间限制的ID撞名，改用不同私有前缀，不改稳定声明ID。
  - 测试使用真实Basic资源与准备接口：反向拒绝没有新claim/pending或设备准备，正向对照确实启动准备；
    已持有闭塞在降级后保留，车头退出不释放，车尾退出才释放。另覆盖中间轨道失配、混合AB/BA、全部双向、
    重复范围与来源隔离、后继下限、方向更新IDLE、资源型闭塞基线、拓扑换根与新定义基线，以及配置/API/JAR。
  - 门禁结果和独立复核证据见下方。没有Core/Basic/SimpleATOBukkit生产修改、schema升级或兼容路径，
    不声称已验证真实Folia/客户端、完整回库任务或大规模延迟；整体Stage2仍在进行中。
  - 最终独立离线门禁：`:SimpleATO:test :BukkitPlugin:test :SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`，
    排除`:WebUI:installFrontend`和`:WebUI:buildFrontend`，使用缓存JDK25/Gradle9.5，无下载或构建配置修改。
    三个测试任务均实际执行：SimpleATO338项/45套、BukkitPlugin332项/82套、SimpleSignals306项/31套，
    总计976项/158套，零失败、错误或跳过，57秒完成。插件JAR隔离检查实际执行，JAR包含当前默认配置；
    Java consumer在此前相关门禁实际编译，最终门禁复用未变输入缓存。日志`SimpleSignals/build/direction-review-gate.log`。
    独立针对性门禁88项通过，ID撞名复现先失败、修复后两测试类29项通过；最终复核无剩余阻塞。
    `git diff --check`及任务上下文校验通过；既有索引超出自动注入大小的警告仍在，两位worker均直接完整读取所需规范。

- [x] 实现已批准的单线单闭塞双向控制：默认关闭的 `bidirectional` 字段、反向路径准备与匹配、
  实际反向入口检查、同一 block token 的相向互斥/折返保留/车尾释放，以及反向区域后继和编辑保护。
  保持一份主信号输出，两端设备复用已有附属显示；不增加方向锁、占用区、规则节点或宿主 API。
  具体合同见[双向固定闭塞](../../spec/simple-signals/backend/bidirectional-fixed-block.md)。
  工具、测距和单线多闭塞协调继续推迟。六个生产文件复用既有框架，没有 Basic/Core/ATO 生产改动。
  针对性测试 134 项、7 个测试类通过；独立审查无需修复。最终离线
  `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`（排除 WebUI 前端安装/构建）通过：
  295 项测试、30 个测试类，零失败、错误或跳过；JAR 隔离检查实际执行，Java consumer 编译 UP-TO-DATE，diff check 通过。
  日志 `SimpleSignals/build/bidirectional-fixed-block-check.log`。真实 Basic 类组合证明相向原子拒绝、车尾释放后再授权，
  以及停车折返不丢失原资源；完整回库任务与真实 Folia/客户端仍未验证。本项作为一批提交供用户审查，整体 Stage 2 未收尾。

- [x] Index protection-region gates/interiors by topology plus plugin-definition revision and rebuild only on catalog publication, outside the tick loop.
- [x] Index fixed blocks by exact topology root plus plugin-definition revision outside the tick loop.
- [x] Match fixed-block resources only on the frozen-route authority prefix: reuse one plugin-local directed-route projection,
  discover candidates through the rail reverse index. The original complete-block-only restriction is superseded by the reviewed station-inside-block requirement below.
- [x] Allow fixed-block internal starts/stops. Match the full frozen route before authority clipping, distinguish true entry from internal start, and reuse the existing token/grant/release queue. Real Basic+SimpleSignals admission tests prove an internal stop retains the token, strict forward append keeps the grant identity, head-only exit does not release, tail exit does, and later requests do not reacquire historical blocks. Direct region-successor floors clamp at internal station targets. The original internal-start authority-only declaration is superseded by the simplification below. Foundation commit `ac99ba27`: 68 focused tests passed; final offline affected-module gate passed with 1338 tests in 247 suites, 1331 passed, 7 existing skips, zero failures/errors; JAR isolation, Java consumer compile and diff check passed. Repeated frozen-route visits and return-to-depot block-token reversal remain the separate unchecked item below.
- [x] Simplify fixed-block inspection to actual entrances only. Remove the internal-start declaration ID/floor and handler skip; preserve block-resource contribution/held claims independently. Tests prove natural head-forward exclusion of a real passed entrance, no synthetic declaration at an internal start, future real entrances still activate, and front red/missing baseline plus stop/append/tail release remain correct. Basic's route origin and resource lifecycle are unchanged. Focused gate: 35 tests in 5 suites passed. Final offline gate (`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`) passed: 169 tests in 20 suites, zero failures/errors/skips; plugin JAR isolation, Java consumer compile and diff check passed. No live server smoke test was performed.
- [ ] Close repeated-visit and stopped return-to-depot integration without changing physical identity. The fixed-block and initial continuous bulb-line slices below are implemented; follow-up audit corrections remain listed below. Keep stopped return-to-depot reversal separate from a loop within one frozen route. The checked bidirectional item above now verifies single-block physical identity and stopped reversal; do not infer complete depot-mission integration from that bounded slice.
  - [x] Reviewed fixed-block slice: declaration uniqueness is `(stable ID, trigger route metres)`; active-state changes distinguish visits, dirty updates keep stable IDs, and the concrete source projects the nearest active visit per signal. Repeated block windows coalesce only the current requested prefix into one token with the latest covered release point. Inspection regressions and a real four-rail degree-2 Basic admission/extension/tail-release test pass; repeated-region selection, repeated turnout/diamond preparation and reversal semantics are unchanged. Focused gate: 66 tests in 7 suites passed. Final offline gate (`:Core:test :Basic:test :SimpleATO:test :SimpleSignals:check :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`) passed: 1344 tests in 247 suites, 1337 passed, 7 existing skips, zero failures/errors; Java consumer compile, plugin JAR isolation and diff check passed. No live Folia smoke was performed.
  - [x] User-approved bulb-line slice: repeated complete region windows and separate turnout acquisition/preparation after tail release. Basic rejects overlong requests before mutation with a neutral retry ceiling; SimpleATO retreats through existing floors and makes at most one local retry. Region selection uses directed tail-LEFT facts; simultaneous resource release cannot clear a later visit, and atomic unrelated definition replacements carry unchanged bindings' clearance facts, including consecutive edits before an epoch. Tests cover real Core turnout admission/preparation/release with driven physical observations, long lookahead, pending second preparation, conflicting trains, diamond dedup/latest release, repeated-entry selection, indicator-only observation, append and nonzero-clearance reversal reset. Focused runtime tests: 83 passed; production Dispatcher wiring suite: 21 passed (scripted admission results, not physical proof). Final offline gate (`:Core:test :Basic:test :SimpleATO:test :SimpleSignals:check :BukkitPlugin:test :WebUI:test :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`): 1363 tests in 252 suites, 1356 passed, 7 existing skips, zero failures/errors; consumer compile, JAR isolation and diff check passed. No live Folia smoke. Opposite-direction fixed-block ownership remains unresolved; no persistence/runtime compatibility added.
  - [x] Follow-up audit correction: when the next turnout use is still held, return the existing neutral retry ceiling instead of an unqualified busy result. The real Core/Basic regression starts at a short 350 m grant with a 50 m train and turnout uses at 340 m and about 510.71 m; exact prefix growth retains the first claim/selection, then tail clearance permits delayed second preparation under the same grant. Same-target, earliest blocker, tolerance, non-advancing ceiling, too-short-loop and true pending busy cases pass. Reuses current held identities and unchanged SimpleATO at-most-one retry; no new public API/state or compatibility path. Independent check found no additional in-scope issue. Focused gate: 99 tests in 8 suites passed. Final offline affected-module gate passed; reports total 1366 tests in 252 suites, 1359 passed, 7 existing skips, zero failures/errors (Core/WebUI reused up-to-date results). Java consumer compile, plugin JAR isolation and diff check passed. Basic lifecycle uses coherent driven observations; Dispatcher regression responses are scripted, not an end-to-end physical simulation. No live Folia smoke.
  - [x] Follow-up audit correction: unchanged Basic monitored areas now retain physical state through prepared replacements; the first new complete baseline includes real crossings. SimpleSignals inherits unchanged clearance on the first serialized new callback from one world-local last-processed runtime, not during edit commit. Worker-canonicalized unchanged route references avoid deep geometry comparisons on physics and no predecessor/event replay chain exists. Removed unused `replaceDefinitions` rather than wrapping it; no persistence/API compatibility path. Production-class composition tests reproduce old LEFT during a paused commit, old callback completion after publication, first-replacement LEFT, 20 unconsumed edits, failed commit, changed/restored region and new-baseline inspection0; Basic also covers ENTER+LEFT, revision-only continuity, boundary/topology changes, remove/readd, world/epoch rejection and IDLE. Independent review found no additional in-scope defect. Final offline affected-module gate passed: 1377 tests reported in 253 suites, 1370 passed, 7 existing skips, zero failures/errors (Core/WebUI reports reused up-to-date). Java consumer compile, plugin JAR isolation and diff check passed. Tests compose real tracker/host/plugin with constructed frames and admission values, not a live Folia run or proof of changed Basic authority.
- [x] Match protection-region windows on the exact frozen-route inspection binding, validate one complete entry/interior/exit traversal and compile an entry-to-exit authority floor. If the selected route has exactly one fixed block beginning at the exit, extend that floor through its exit; multiple same-point matches are conflicting definitions, not route branches, and fail closed. Basic derives the physical resources for the expanded route prefix, and no exclusive region token is added. See the [owner contract](../../spec/simple-signals/backend/protection-region-authority-transition.md).
- [ ] Implement the remaining plugin policy that chooses a moving-block post-exit boundary without recursive Basic contributors. Fixed-block entry-to-exit floors and the protection-region-to-immediate-fixed-block transition are complete; moving-block policy and its exact clearance contract remain.
- [x] Submit fixed-block tokens only as additive resources through the host contributor; rely on Basic to derive every
  rail/turnout/diamond physical resource and its existing atomic claim/release lifecycle.
- [ ] Add concrete flank/approach resources when the first interlocking rule requires them; do not invent generic placeholders.
- [x] Keep region identity non-exclusive by default; one connected four-gate region publishes two rail-disjoint route macros, Basic admits both physical footprints concurrently, and an overlapping request is denied by the actual rail/turnout footprint rather than a region token.
- [x] Derive permissive aspects only from a committed Basic grant and valid SimpleATO authority. The shared constraint pipeline keeps the initial/pending Basic authority at 0 m despite permissive plugin speed, applies plugin output only as a tighter post-grant limit, fails stale route bindings closed, and distinguishes an unconfigured block from a configured signal whose missing baseline publishes 0 m/s.
- [x] Hold each contributed fixed-block identity to its last matched exit and use the existing host cursor/release/extension lifecycle;
  do not create a plugin release queue.
- [x] Verify the unified owner-unregister gate with the concrete SimpleSignals resource/source identities: claim,
  dependency, non-empty restriction and active inspection blockers all reject planned disable before either
  registration set changes; clearing them permits complete owner removal. Forced disable removes dead callbacks,
  retains committed claims/dependencies and stalls only worlds with affected live state.
- [x] Integrate route-bound diamonds for conflict/inspection without cross-line continuation or selectable state. End-to-end admission proves both crossing rails derive one Basic diamond identity, conflict while held, and permit the other route after tail release; route validation still rejects crossing-line continuation and the crossing creates no turnout state.

## Reactive signal state and customization

- [x] Review the deferred reactive design: SimpleSignals owns a one-way provider-to-upstream-consumer invalidation chain; no fixed-point solver or host-owned dependency graph. Record the future Core active-continuation snapshot prerequisite without implementing it in this batch.
- [x] Publish Core's immutable active-turnout continuations in the coherent world frame and project them unchanged through Basic observation. Rebuild the turnout-port map only when topology or live selection revision changes; reject a candidate frame if the selection changes during epoch processing.
- [x] Implement only the dependency/invalidation mechanism approved in that review for the `CURRENT` built-in providers:
  precompile an acyclic one-way graph and evaluate only reached consumers, stopping unchanged branches without a world-wide scan.
  This completed mechanism is not the `TARGET` JS tree/cycle contract above; concrete aspect/limit rules remain separate.
- [x] Prove unchanged resources remain idle and stale permissive outputs cannot survive invalidation: unchanged host area state skips the plugin callback, unchanged rule output stops the branch, and rule/downstream failure publishes an unavailable zero-limit state instead of retaining an old permissive result.
- [x] Close the actual inspection-to-controller handoff gap (user approved the SimpleATO ownership move): Dispatcher publishes compiled declarations with its frozen-route mapping; each train's controller owns activation/baseline state, consumes signal invalidations and applies projections before its planner, using the existing single perception. Two real Basic/SignalEpoch/SimpleATO integration cases prove changed occupancy/readiness reaches the next physical control calculation with Dispatcher stopped, unchanged state stays idle, and callback failure prevents actuation; direct restriction-publisher tests are not the evidence. See the [inspection owner contract](../../spec/javarailways/simpleato/operational-routing-and-authority.md#343-缓存失效与响应式更新).
- [x] Extend the neutral signal-epoch wake-up seam with source-declared watched rail endpoints. Fixed-block downstream dependencies now declare only required turnout continuations; definition prepare walks that explicit directed path, derives distance and watched conditions, and turnout-only changes seed only affected rules. Missing, illegal, conflicting, cyclic or unreachable paths fail restrictive outside the tick path.
- [x] Propagate changed downstream state only as far as needed; stop when an output is unchanged and reject dependency construction that would introduce reverse/downstream feedback.
- [x] Define the `SimpleSignals` Java/Kotlin rule execution boundary before adding scripts: immutable controlled-section/downstream views enter an injected rule and a validated speed plus opaque model variant leaves it; no concrete aspect vocabulary or speed table is hard-coded.
- [x] Implement concrete rule providers through that boundary without moving policy into Basic or SimpleATO. Persisted fixed-block and protection fixed-route/current-entry providers are complete; arbitrary custom/script providers remain separate.
- [x] Expose the neutral Basic active-admission value and compile/persist mutually compatible configured default protection routes without claiming them. The active value carries the frozen route and actual requested/committed authority boundaries; default-route runtime checks use precompiled turnout requirements.
- [x] Connect admission changes and physical turnout observations to one shared entry selector: request > physically-correct configured default > no route. Retain the old committed prefix during suffix preparation and remove tail-cleared route associations without ending the whole operating route. Match frozen routes once on route changes; unchanged selection state remains idle. Host routes latest-per-owner updates by cached rail interests, including removal/reversal, source replacement baseline and train disappearance independent of Bukkit follow-up. A cleared entry association does not release overlapping long resources still held by Basic.
- [x] Implement the three device forms' backend on that shared state: fixed-route signal, current signal and presentation-only route indicator. Reuse the indicator for distant previews through explicit source binding and player placement. Route-specific occupancy, mixed-kind one-way dependencies, typed inspection declaration IDs, additive schema-1 persistence and live definition patches are implemented. Rendering is tracked below; independent physical inspection consumption is the separately approved host slice above. Do not infer request state from claim owner strings, poll `liveClaims()` per tick or scan turnouts for non-default fallback routes.
- [ ] 实现用户已选的 JS 输入／setter provider；[owner 契约](../../spec/simple-signals/backend/js-signal-rules.md)已确定
  包结构、同步阶段、树形前视、无通用预算和遇环停止，具体类/API 字段随小批次实现，不以文档代替运行验收。
- [x] Verify protection-device deletion through the normal definition edit permit. Actual Basic registry regressions cover fixed-route/current-entry signals blocked by static route dependencies and live claims, unchanged disk/publication/sources on rejection, success after blocker release, and disjoint-line deletion. Normal Dispatcher ordering installs route protection before inspection binding and removes inspection before protection; subsequent worker updates filter removed captured bindings. No production defect was reproduced in this guarded lifecycle, so no deletion compatibility path was added. Owner contract: [definition edit impact](../../spec/simple-signals/backend/definition-edit-impact.md#5-不变量线程与版本). This does not establish arbitrary external-caller lifecycle safety or cancel an already-running callback.
- [ ] Keep scripts away from mutable world objects and raw Basic claim mutation; all outputs pass plugin validation and host restriction/resource APIs.

## Timetable, authoring and rendering

- [x] 本批实现主信号/附属信号机后端：附属定义、默认空集合存档、已有 patch/draft/inspect 接入、
  直接主信号引用及模型校验、旧新逻辑/摆放编辑保护，以及变化主信号到显示设备的预编译索引。
  复用现有 handle 保留、原子发布和清理，不新增信号规则、检查声明或资源。精确合同见
  [模型显示](../../spec/simple-signals/backend/model-presentation.md#主信号与附属信号机)。
  工具、测距、样式编辑界面、轨道可视化和单线运行扩展不在本批。
  聚焦门禁已通过：5 类 94 项（附属存档 4、原设备存档 4、草稿 4、显示绑定 18、world manager 64），
  零失败/错误/跳过；生产与测试代码编译通过。真实 Folia/客户端显示未验证。
  - [x] 独立审查未发现范围内问题，无需修补代码。最终 offline gate：
    `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`，排除既有 WebUI 前端安装/构建；
    30 类 283 项测试零失败/错误/跳过，test 与插件 JAR 校验实际执行，编译与 Java consumer 为 UP-TO-DATE。
    `git diff --check` 通过；日志位于 `SimpleSignals/build/attached-signal-check.log`。
    沙盒首次启动未能加载 Gradle 本地库；在获准的构建环境重试后通过，没有改动依赖、构建文件或业务代码。

- [x] Close the reviewed collection/install race by protecting new physical routes plus old complete dependencies before current matching. Real Dispatcher + SimpleSignals matcher/publication + Basic registry composition verifies completed edits before the guard, denied edits after matching and checkpoint restore; injected first/replacement failures verify cleanup, restoration and failed-restoration retention/stall. This is deterministic ordering coverage, not live Folia scheduling/persistence-transaction coverage.
- [x] Reuse signal-plugin route matching/binding in existing selected-route timetable preparation and collect exact dependencies. Do not create a separate signal-audit layer, repeat authoring-time checks or pathfind again; transient red/occupancy waits, partial fixed-block traversal and repeated visits are not automatic apply failures.
  - [x] Fixed-block static matching reuses the existing matcher and registered token identities; partial internal starts/stops and repeated visits remain allowed, without live bind/update or another pathfinding pass.
  - [x] Protection-region static matching captures one immutable publication and reuses the runtime route-binding compiler. Complete no-table crossings and repeated visits are valid; configured tables require one exact region/entry/exit/path match. Internal endpoints, stale empty catalogs, conflicting successor floors and missing/ambiguous table paths fail before clocks/initial positioning, without claims, bindings or signal output. Real Dispatcher first/replacement tests prove cleanup/restoration; table mismatch is verified at publication/source level, not as a live Folia race. Focused gate: 42 tests/5 suites passed. Final offline gate: SimpleSignals 195, SimpleATO 330 and BukkitPlugin 290 tests passed with zero failures/skips; Java consumer compile and plugin JAR verification passed.
- [x] Extend the existing atomic applied-plan dependency declaration with exact registered SimpleSignals resources from selected routes (currently fixed-block tokens). Keep non-exclusive edit protection distinct from live claims; changed old block identities also enter definition edit impact. No artificial region/signal token, second registry, schema change or compatibility constructor. Owner contracts: [static dependencies](../../spec/javarailways/simpleato/operational-routing-and-authority.md#35-已选时刻表路线的静态资源依赖), [edit impact](../../spec/simple-signals/backend/definition-edit-impact.md). Focused ordering gate: 94 tests/4 suites passed. Final offline Basic/SimpleATO/SimpleSignals/Bukkit gate plus Java consumer and JAR verification passed; SimpleATO 330, SimpleSignals 189, BukkitPlugin 290 tests executed with zero failures/skips. Basic reused existing results (367 passed, 1 skipped).
- [x] Close the existing draft/apply/replace/draining/unapply lifecycle without another draining state machine. Existing tests cover draft storage/validation with no dependencies and guarded apply/replacement rollback; the new SharedAdmission regressions verify pending and granted requests, terminal-but-not-ended retention, edit/replace/force-discard rejection, successful end then removal, no later automatic cycle, typed removal failure retention/stall, and checkpoint recovery-only retention until explicit discard. Basic dependency/edit permits are real; admission completion and the neutral resource source are scripted test fixtures, not physical tail-release or live Folia proof. Owner contract: [Dispatcher lifecycle](../../spec/javarailways/simpleato/dispatcher-runtime.md#停止自动发车与解除编辑保护). Focused gate: 67 tests/3 suites passed; full SimpleATO gate: 333 tests/45 suites passed, zero failures/errors/skips. Core/Basic/Bukkit tests and Java consumer compilation were up-to-date in the successful offline gate; no runtime/schema/compatibility changes.
- [ ] Finish the remaining editor integration through the common edit permit; guided authoring remains separate below.
  - [x] Existing SimpleSignals six-kind definition patches use the common permit, and the admin apply command already displays the original blocker kind, owner/revision and rail/node/resource overlap with bounded output. `SimpleSignalsWorldRuntimeManagerTest` covers exact diagnostic content and truncation; do not add another formatter or mark that command UI as unimplemented. Contract: [authoring commands](../../spec/simple-signals/backend/authoring-commands.md).
- [ ] Add guided RailObjectWand interaction for create/edit/delete/inspect/validate; the admin JSON command workflow is complete above. Topology-format migration and its recovery points remain owned by the separate 09-24 migration task, not runtime command compatibility.
  - 独立道岔工具已提交。闭塞与保护区域 Wand 的已确定交互已完成本批离线验证，见[实施说明](research/section-wands-batch.md)；进路录制、信号机放置、测距及信号规则预设/模板推迟，不预留实现。
  - [x] SimpleSignals 提供 `/simplesignals wand block|region [create|delete]`。闭塞两点/旧点接续、自动方向、禁止道岔，独立删除模式；区域有向边界、吸附、限定轨道距离的自动补全与原区域修复。
    领域操作只生成已有 patch，复用 worker 保存、原子 publication/source 替换和 Basic 编辑许可。空/仅待补全边界不扫描整图；第一次实际区间编译产生的拓扑索引按 exact root 复用。
    世界存档新增默认空 `pendingProtectionBoundaries` 和区域默认 null `boundaryDraft`，不升 schema、不增加兼容或派生图存档。未完成区域保留原认可边界作为局部禁行依据，进路表/设备配置不丢失。
    修复时旧表逐条重验；失效进路或丢失下游目标的设备结果不可用。禁止规划、绑定和直接准入绕过缺口；方向失配同样明确拒绝，旧资源继续原车尾释放。
    复核修复了待补全边界编辑范围包含无关点、相邻轨道共同端点的零长度到达和删除关联、补放边界恢复身份、外部编辑过期选点等问题。
    最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` 成功，34 套 / 345 项测试实际执行，零失败/错误/跳过；生产/测试 Kotlin 和 JAR 实际重建，JAR 隔离通过，未变 Java consumer 复用编译缓存。
    使用缓存 JDK 25 / Gradle 9.5，`--offline -x :WebUI:installFrontend -x :WebUI:buildFrontend`。证据：`SimpleSignals/build/test-results/test/TEST-*.xml` 与 HTML test report。
    新工具 33 项测试包括真实 Core 轨道与可控玩家/worker 调度；后端测试组合真实 Basic registry、方向存储和释放。不是 live Folia、客户端画面或大规模延迟基准。
    `git diff --check` 与 16 项 curated context 校验通过；原大型 index 超过注入长度上限的警告仍在，所需专题契约已单独列入 manifests。
    使用说明与契约已同步；地面子模型、可视化、信号机/测距工具、进路录制仍未完成。本批未修改 Core/Basic/ATO/宿主生产代码，Stage2 不归档。
  - [x] 主插件提供独立道岔 Wand：`/jr wand turnout`，左键来向、右键出口，复用现有 `selectTurnout` 和实际占用/资源冲突结果。
    根据命中弧长选择较近端点，只接受当前拓扑中的合法连接；成功/拒绝后重新读真实双侧选择，以局部短暂粒子和消息反馈。
    复用既有异步选轨和玩家调度；取消、换工具、换世界或换拓扑后旧查询结果不能扳动道岔。
    无新资源管理器、授权、周期任务、存档升级或兼容路径；Core、Basic 和 ATO 生产代码不变。
    使用说明见 [BukkitPlugin README](../../../BukkitPlugin/README.md)，实现机制见[道岔工具契约](../../spec/bukkit-plugin/backend/turnout-wand.md)。
    新增 12 项回归；独立复核补上跨世界往返时清空旧选择，并增加支线到干线的真实服务切换断言，无剩余范围内问题。
    修复后完整 BukkitPlugin 84 套/348 项测试实际执行通过，Basic 手动切换的4项聚焦测试实际通过，均零失败/错误/跳过。
    生产和测试 Kotlin 重新编译，Java consumer 编译复用未变缓存；shadowJar 实际重建，确认工具类、中英语言资源、WebUI JAR和默认 OP 权限入包。
    `git diff --check`与 curated context 校验通过；日志为 `BukkitPlugin/build/turnout-wand-review-fixed-gate.log` 和 `turnout-wand-review-gate.log`。
    取消测试覆盖实际生产判定函数，服务测试组合真实 Core/宿主；换世界事件接线通过源码复核，不表示完整 Folia 调度或客户端画面已验证。
  - [x] 提供 `/simplesignals wand route` 当前通路预览：左键入口、右键同区域出口、潜行取消。沿真实当前道岔选择追踪，不寻路、不扳动道岔、不申请资源、不保存进路。
    保留错误出口/尽头/循环/未开放通路的完整前缀和真实停点；仅相关道岔、轨道或所选区域事件异步重算，合并待处理工作并丢弃旧结果。
    worker 准备路径与箭头，玩家每20 tick最多发送512个已准备粒子；长路径循环分批，端点每批保留，逻辑路径不截短。完整 TextDisplay 可视化另做。
    关闭覆盖取消、换工具、退出、换世界、世界卸载和插件停用；修复迟到订阅/显示任务、旧玩家线程结果覆盖以及输入未变化时重复重算的问题。
    精确端点区域切口属于原有禁止配置，选点只处理合法同轨边界；删除不可达的跨接头匹配分支，没有放宽区域规则。
    独立复核补上相关轨道失效时的区域通知，真实 manager 回归先复现漏通知，再验证同定义恢复通知与无关编辑隔离。
    新增34项测试；最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` 成功，40套/392项全部执行通过，零失败/错误/跳过。
    编译在聚焦检查中执行，最终门禁复用编译缓存；JAR隔离实际检查，Java consumer复用未变缓存。日志 `/tmp/javarailways-current-route-preview-check.log`，XML位于 `SimpleSignals/build/test-results/test/`。
    机制/复杂度见[预览合同](../../spec/simple-signals/backend/current-route-preview.md)，范围见[预览批次](research/current-route-preview-batch.md)。没有 Core/Basic/ATO/API/schema 变化，未做 live Folia/客户端或规模延迟实测，Stage2仍不归档。
  - [x] 接入 `/simplesignals route record`：保存当前成功预览的同一完整路径，首次建立固定表、之后追加；自动ID、相同完整路径无变化，同入口/出口不同支路可分别录制。
    冻结前校验有效预览及当前相关观测，冻结后正常道岔切换不改写记录；保留旧表ID、全部旧进路/defaults，绑定当前区域revision严格校验，不静默过滤失效旧进路。
    小型SimpleSignals适配生成原table upsert，复用manager唯一事务；沿用玩家Choice busy/active和关闭路径，提交判定只读原子状态。不寻路、采样曲线、申请资源或切换道岔。
    新增15项测试（manager9、handler6），聚焦107项/4套通过。真实manager/store/preview backend/Basic planner证明保存前后实际支路变化、重载保持、重复不写、追加/default不丢失和冻结语义。
    真实Basic registry的静态依赖/live claim拒绝相关区域编辑，包括未录支路；非法旧route、save/source/cancel失败保持旧disk/publication/source。
    最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` 成功，40套/407项全部实际执行，零失败/错误/跳过。聚焦中编译实际执行，最终编译与Java consumer复用缓存，JAR隔离实际检查。
    日志 `/tmp/javarailways-route-recording-check.log`，XML在 `SimpleSignals/build/test-results/test/`。本批范围见[录制批次](research/current-route-recording-batch.md)，机制/复杂度见[录制契约](../../spec/simple-signals/backend/current-route-recording.md)。
    当时不改Core/Basic/ATO/API/sidecar形状，不加兼容、可选录制模式或新请求/结果层；后续已完成进路替换，并取消默认进路配置（见本文件顶部修正批次）。删除、信号机工具和完整可视化仍待后续。未做live Folia/客户端或规模实测，Stage2不归档。
- [ ] 在 `SimpleSignals` 接入信号模型显示：把实体操作安排到目标线程，只更新实际变化并保留最新待显示状态，
  丢弃旧显示序号，世界卸载或插件停用时可重复安全清理。复用主插件平台 API 前先审查具体跨插件需求，
  不通过 `SimpleATOBukkit` 转发显示。
  - [x] 根据当前代码编写模型与轨道定位接口草稿，以及信号状态到模型的处理流程；包含伪代码、复杂度和必需测试。接口与数据类型仍待审查，不能据此认定渲染功能已完成。
  - [x] 提供按世界/轨道 UUID 查询不可变曲线的公开接口，并提取接收现有部件定义的内部普通模型入口；保留车体/转向架流程。这批不开放完整模型显示服务。查询复用 UUID 世界索引和 Core 世界局部读锁，不扫描世界轨道、不调用 Bukkit 调度；构造复用既有递归部件逻辑，根位置和车体 owner 改为显式参数，避免构造器复用时保留上次车体状态。独立审查未发现阻塞问题。离线验证：Bukkit 77 suites / 296 tests、TunnelBridgeBuilder 8 suites / 31 tests，零失败、错误或跳过；公开 Java consumer 编译与主插件 shadowJar 打包通过。回归覆盖真实 service/Core/世界索引组合和替身模型树，不证明完整世界加载保存、驾驶座交互或真实 Folia 实体显示。
  - [x] 根据实际部件/动画源码补充普通模型值契约提案：已展开的不可变容器/物品树、完整场景选择、同名动画广播与冲突拒绝、基础外观复位、完整资源键和普通模型零插值；记录原有残留外观、单动画槽、命名空间和缩放限制。该批只有文档与研究，实现由下一项跟踪。
  - [x] 实现公开不可变普通模型定义、校验与内部完整场景应用，包含基础外观复位、完整命名空间和普通模型零插值；独立 Java 调用示例验证值类型。保留列车入口及三 tick 平滑，删除未使用的旧配置普通入口，不实施旧 YAML 包迁移或时间轴播放修复。实现机制和证据边界见[主插件模型契约](../../spec/bukkit-plugin/backend/model-presentation-api.md)。独立审查无阻塞，补充 AIR 场景构造/恢复回归；最终离线 Bukkit gate 实际执行 80 suites / 313 tests，零失败、错误或跳过，Java consumer 编译通过。TunnelBridgeBuilder 回归复用既有 8 suites / 31 tests 通过结果，主插件 shadowJar 打包和公开模型类入包检查通过。普通模型使用真实 Paper ItemStack 与记录后端，Native 使用真实 adapter/替身实体，EntityLib 仅验证真实元数据自然光语义，尚未验证 Folia 客户端画面。
  - [x] 提供 `ModelHandle` 与第八个公开服务 `ModelPresentationService`：复用 `ModelPose`、创建时建立的场景索引、既有最新状态调度和附近玩家索引。提交只保存值；实体工作不持有提交锁，根位置变化先在旧所属线程删除再到新位置重建。接入实际服务注册、插件停用、世界撤回/同 UUID 重载和 Java consumer，不公开内部模型管理器，不保留草稿兼容。本批不接 SimpleSignals 模型包或信号绑定。
    - 卸载顺序已获用户确认：先卸载附属插件并完成其模型清理，再卸载主插件。`closeOwner` 返回非阻塞完成结果，也统计已经单独关闭但尚未删除的模型；删除失败仍可观察，不把 `onDisable` 返回或排入队列当成清理成功。没有新增卸载管理器。完整规则见[主插件模型契约](../../spec/bukkit-plugin/backend/model-presentation-api.md)。
    - 独立审查修复删除结果误判、玩家可见列表的删除竞争，以及登记玩家显示失败后的引用清理；无剩余阻塞。最终离线 Bukkit gate 实际执行 82 suites / 331 tests（服务 15 项），零失败/错误/跳过，Java consumer 编译、主插件 shadowJar 打包通过；TunnelBridgeBuilder 编译通过并复用既有测试结果。测试连接真实 service/builder/玩家索引和 Paper ItemStack，使用可控调度与记录后端；不证明 live Folia、客户端资源包或完整世界加载保存。真实平台 smoke 仍待后续验证。
  - [x] 明确信号模型包的 `default` 契约及配置片段：仅 SimpleSignals 模型包必填，初始缺状态或 `Unavailable` 时使用，未知明确外观仍报错；内部状态名称和宿主 API 不变。同步 README、需求与显示契约，并修正设计文档中主插件显示服务尚未完成的旧描述。本项仅完成文档，不表示已有包加载或信号实体。
  - [x] 确认并记录两种摆放方式、方向相关偏移、模型默认/玩家选择的倾斜规则，以及预设选择时复制为独立实例参数的语义。改变附着方向保留偏移值重算位置，原地旋转不移动摆放点；本项仅完成设计讨论和文档。
  - [x] 实现 SimpleSignals 的预设应用与纯摆放计算：两个源码文件复用 `RailPositionSnapshot`、`ModelPose` 和公开曲线，完成方向相关偏移、倾斜/竖直参照与位置独立的局部旋转；预设选择时复制参数，不增加动态继承或深拷贝模型树。两个测试文件使用真实曲线与独立推导的轴向期望验证 AB/BA、组合坡度/超高、局部旋转、实例独立性、重新应用、地面摆放及非法数值，新增 16 项全部通过。独立审查无阻塞或明显过度设计。
    - 最终离线 gate：`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`，排除 `:WebUI:installFrontend` 和 `:WebUI:buildFrontend`。25 suites / 211 tests 全部通过，零失败/错误/跳过；模块测试、插件 JAR 隔离校验和 Java consumer 编译实际执行。复用本机 JDK25 与缓存 Gradle9.5，未修改 wrapper；首次 focused 构建意外触发既有前端依赖任务，无 WebUI tracked 改动，后续显式排除。不涉及加载器、存档、Wand、缓存/调度器或生产绑定，未做 live Folia/客户端显示验证。
  - [x] 按已批准的[模型包格式](../../spec/simple-signals/backend/model-packages.md)实现完整目录加载：三个源码文件读取多个模型、展开包内共享/内联部件，生成现有不可变模型定义、完整默认/普通外观及已解析预设。静态场景不要求时间字段，导入参数与列车同义且内联偏移仅应用一次；未知字段/null/重复键/别名/对象标签、非法路径与引用、循环及场景冲突均定位文件和字段后拒绝。五文件固定示例与 27 项回归通过。
    - 独立审查修正共享嵌套链逐层重复校验：先展开全部定义，再仅从共享根校验整棵树；用有序集合记录循环路径。补充未使用的嵌套错误、独立场景命名空间和调用方嵌套 Map 修改隔离回归，无剩余阻塞。测试通过仅测试依赖复用宿主已有 Paper 注册表初始化，不改变生产依赖或 JAR 内容。
    - 最终离线 gate：`:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`，排除既有 WebUI 前端安装/构建。26 suites / 238 tests 全部通过，零失败/错误/跳过；模块全量测试与插件 JAR 隔离检查实际执行，Java consumer 编译任务通过且复用未变输入缓存。源码 510 行、加载测试 592 行及五份 YAML；无包自动发现、设备存档、生产显示绑定、实体或 live Folia 验证。
  - [x] 将用户提供的 JR 四灯式灯头整理为[实际 demo](../../../SimpleSignals/examples/jr-four-light/README.md)：服务器模型包配置四种完整外观和左右预设，客户端资源包提供现代物品定义、四份网格与共用贴图，不覆盖普通纸张、不包含立柱或速度规则。demo 网格仅整体下移16个模型单位，并用部件偏移补偿；逆平移后的 JSON 与原始文件一致，PNG 逐字节一致，原模型工作区未修改。三项回归读取同一份分发文件，验证整包加载、资源引用/元素坐标、真实1.21.11资源包元数据，以及 AB/BA 安装朝向；不证明生产绑定或真实客户端显示。
    - 独立复核无剩余问题，三项 focused 测试实际重跑通过。最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava` 为27 suites / 241 tests，零失败/错误/跳过；模块全量测试和插件 JAR 隔离检查实际执行，Java consumer 编译复用未变输入缓存。只新增示例资产、测试资源接线、165行测试与文档，无生产逻辑或存档版本变化。
  - [x] 实现设备模型/摆放存档：三类设备复用可选模型配置、rail/ground 摆放和当前 sidecar；删除显示器重复位置，接入原有候选校验及旧、新轨道编辑许可，包括结构消费者的模型附着轨道。用户明确批准直接修正未发布 schema 1；不增加兼容字段或模型实例参数占位。具体合同见[信号模型契约](../../spec/simple-signals/backend/model-presentation.md)。固定 JSON 与实际 encode/decode 验证格式和值；manager 回归使用真实 Basic registry 验证缺轨道/越界/revision 拒绝、两类编辑 blocker、旧/新位置、ground 逻辑保护和失败原子性。
    - 首轮离线 focused gate 为 7 suites / 98 tests；独立复核无缺陷或明显过度设计，实际重跑 codec/manager 的 55 项测试通过。最终 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`（排除既有 WebUI 前端安装/构建）为 28 suites / 248 tests，零失败/错误/跳过；模块全量测试及 JAR 隔离检查实际执行，Java consumer 编译复用未变输入缓存。`git diff --check` 通过；不改主插件 API/构建依赖，不验证包存在性、生产绑定、Wand、任意模型参数或 live Folia 画面。
  - [ ] 接入只处理显示变化的更新流程，验证实际画面、世界卸载和插件停用时的清理。设备存档不能代替生产绑定及真实 Folia 验证。
    - [x] 异步发现目录模型包，接通生产创建、完整信号结果到变化提交、定义替换及卸载清理。validate 只做纯准备，handle 在成功提交后创建；显示故障不回滚安全定义。无新格式、兼容、Wand、实例参数或热重载。详细机制与测试由[模型显示合同](../../spec/simple-signals/backend/model-presentation.md#生产接线与编辑边界)维护。初次离线 gate 为71项；独立复核修复多模型创建被新配置取代时误报显示故障，并以先失败后通过的回归确认。独立重新执行绑定12、manager58、服务发现2，共72项，零失败/错误/跳过；无剩余范围内问题。
      - 最终离线 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`（排除既有 WebUI 前端安装/构建）实际执行模块全量测试、插件 JAR 隔离检查与 Java consumer 编译。29 suites / 267 tests，零失败/错误/跳过，`git diff --check` 通过。主插件/各模块仅作为构建依赖编译，未修改其源码或构建配置；已有 JDK 自动探测、deprecated API、JLine/JOML 等警告不在本批修复。Stage 2 保持进行中。
    - [x] 将模型对象生命周期与配置替换分开：世界持续保存实际 handle、上次场景/姿态和显示序号，各配置只保存设备选择和已计算姿态。规则编辑保留模型，位置/旋转变化向原对象提交，只有增删或换模型执行创建/关闭。工作线程对照当前配置与实际对象，差异遍历和集合合并在短提交锁外执行；该锁阻止旧回调覆盖保留对象。相同摆放和精确拓扑根可复用姿态，但全部外观仍须校验。纯准备、原事务和卸载清理不改变；无新 API、schema、兼容、热重载或实例参数。详细机制归[模型显示合同](../../spec/simple-signals/backend/model-presentation.md#生产接线与编辑边界)。
      - 回归直接记录 create/close 次数、对象身份、姿态和序号，覆盖规则编辑、移动/原地旋转、增删/换模型、无关定义编辑、同 revision 的不同拓扑根、部分失败/取消、反序启动，以及分别暂停旧计算与已开始的公开 submit。独立复核未发现生产缺陷，补齐缓存复用时非法外观拒绝且磁盘/source/permit/对象不变、原地旋转保留对象的断言。独立离线重跑绑定16、manager59、服务发现2，共77项，零失败/错误/跳过。
      - 最终 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava`（排除既有 WebUI 前端安装/构建）通过：29 suites / 272 tests，零失败/错误/跳过；模块全量测试、插件 JAR 隔离检查与 Java consumer 编译实际执行，`git diff --check` 通过。使用现有 JDK25/Gradle9.5 缓存，不改构建配置；测试日志移至 `SimpleSignals/build/signal-model-retention-test.log`。主插件和其他模块未改源码；已有 JDK 自动探测、JLine/JOML 与 Gradle 警告不在本批范围。Stage 2 保持进行中，真实 Folia/客户端画面仍未验证。
    - [ ] 真实 Folia/客户端画面验证；记录式服务和受控调度不能代替实际实体显示。

## Cross-cutting online edit latency

- [x] Narrow `SimpleSignalsWorldRuntimeManager.applyDefinitionPatch` edit-permit impact to changed old/new definitions, compiled geometry and structural dependency consumers, including connector paths and region/successor-block authority floors. Unrelated topology-root replacement compares existing derived values instead of treating every definition as edited. Reuse the existing permit/save/source transaction; retain whole-related-region and rail-granular protection. Owner contract: [definition edit impact](../../spec/simple-signals/backend/definition-edit-impact.md). Final offline gate: `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:jar`; 150 SimpleSignals tests passed, zero failures/errors/skips, artifact and Java consumer checks passed.

- [x] Remove cross-world simulation-lock ownership from online signal source replacement and Basic
  edit-permit acquisition. A blocked commit callback must not prevent an old immutable signal epoch
  from completing; the next epoch alone observes the replacement.
- [x] Stage SimpleSignals JSON encoding, validation, temporary write and valid-primary backup copy
  before the final source/publication commit. The bounded callback may only recheck identity,
  atomically rename the staged primary and swap immutable references; backup rotation/cleanup follows
  publication.
- [x] Audit every RailWand create/replace/split/merge/remove path. Move final normalized
  `TopologyState` construction and any whole-world undo/persistence capture into asynchronous prepare;
  the platform/world commit may only validate the captured root, apply the prepared delta and publish
  the prepared root for the affected world.
- [x] RailWand create/rebuild/smooth/split/merge/remove/snapshot-restore now build the complete normalized
  `TopologyState` on the existing async worker. Prepared commits validate the exact base root plus only
  affected rail/endpoint results and publish the prebuilt root; stale preparation rejects before action,
  with no full-world fingerprint or synchronous builder fallback.
- [x] RailWand structural-edit undo captures both complete `before` and `expectedAfter` values in the
  asynchronous prepare pipeline. Core first freezes complete saved-rail values for the exact base/target roots;
  Bukkit then acquires the exact affected-infrastructure permit before a second worker stage prepares metadata,
  direction, render and operational-object values. The same permit remains held through owner revalidation and
  commit, so the prepared side-state cannot be superseded by an overlapping edit. The owner callback only consumes
  those prepared undo values and rejects a stale base root;
  it does not rescan the world to create the undo record. The worker now also stages the complete render sidecar
  and old-primary recovery copy; owner publication is an identity check plus atomic rename, and commit failure restores
  the old primary before releasing the edit permit. A per-world writer slot prevents two whole-file writers from racing,
  while stale record snapshots reject instead of overwriting a preceding disjoint edit. User-triggered application of an existing undo record
  deliberately keeps its complete owner-thread current-state checks until non-topology stores have one reviewed
  revision/stale boundary; optimizing that separate flow is not part of this item. Transient
  `SavedRailV1.activeTrackAtA/B` is now normalized out of structural undo before/after equality. Undo apply preserves
  the live selection when it remains in the ordered target connections, otherwise selects the first target or null;
  unrelated turnout switching therefore does not stale or overwrite the structural undo.
- [x] Prepared topology failure handling now compares immutable `O(delta)` base rail identities,
  endpoint continuations and crossing pairs: failures before mutation or after complete rollback keep
  the world healthy, while an incomplete rollback faults it. Snapshot restore reuses the exact topology
  root for active-branch-only changes and advances the revision only for structural changes.
- [x] Close the cross-world editing concurrency regressions. Existing host source/permit tests and SimpleSignals replacement-continuity tests cover paused source commits and cross-world simulation-lock independence; Core already covers a paused real prepared topology commit with another-world epoch. Two additional `RailWandTopologyEditPlanTest` cases pause real curve sampling while another world publishes actual moving-train physics, prove candidate invisibility before commit, and prove stale commit never enters its mutation closure or changes the current root/inventory. Owner evidence boundary: [online edit concurrency](../../spec/bukkit-plugin/backend/lifecycle-and-threading.md#在线编辑并发验证边界). Focused gate: 11 Bukkit + 18 Core tests passed (Core reused its successful focused results on final retry); full Bukkit gate: 292 tests/75 suites passed, zero failures/errors/skips; Java consumer compilation was up-to-date. No production/schema/compatibility changes. Full player/Folia scheduling and live-server latency benchmarks remain unverified.

## Validation

- [ ] Module-boundary tests prove Basic, SimpleATO, SimpleATOBukkit and `SimpleSignals` have one owner per state and valid dependency direction.
- [ ] Packaging checks for the independently installed `SimpleSignals` plugin prove it has its own descriptor/JAR, compile-only host dependencies and no shadowed host classes; embedded `SimpleATOBukkit` needs no separate deployable-artifact verification.
- [ ] Region tests cover simple turnout, multi-turnout throat, leak, ambiguity, direction, topology invalidation and unsupported overlap.
- [x] Resource tests cover mandatory Basic physical floor, valid additions, omitted/invalid additions, disjoint paths and atomic conflict denial.
- [ ] Fixed/moving transition tests cover whole next block versus exact post-exit boundary and sufficient train-tail clearance.
- [x] Reactive tests cover inspection lead-time cache invalidation, host time-to-distance and braking calculations, affected-only dependency propagation and idle unchanged state. The cross-layer fixed-block test now proves an ended route drops its cached maximum and a later binding reads the newly published SimpleSignals lead time; existing scheduler/runtime/one-way-graph tests cover motion conversion, braking activation, active-route source replacement fail-close, affected-only propagation and unchanged IDLE behavior.
- [x] Constraint tests prove plugin output only tightens authority/speed and stale/exceptional state fails restrictive.
- [ ] Lifecycle tests cover enable/disable, missing capability, world unload/reload, live-grant recovery and rendering cleanup.
- [ ] Persistence tests use fixed current-model fixtures plus corruption/orphan cases. Add a legacy fixture only for a separately approved sidecar migration support window.
- [x] Timetable tests cover audit diagnostics, atomic applied-plan dependency apply/replace, draining and edit blocking without treating dependencies as train claims. Evidence and the scripted-admission/physical-runtime boundary are recorded in the lifecycle item above; live Folia validation remains separate.
- [ ] Run focused module tests, then `./gradlew test`, plugin packaging tasks and Folia smoke checks.
- [ ] Run `git diff --check`.

## Reviewable commit order

1. SimpleATO production slice migrations, one owner at a time.
2. Embedded `SimpleATOBukkit` library scaffold and platform adapter extraction.
3. `SimpleSignals` scaffold and persistence boundary.
4. Protection-region domain/index migration from Basic.
5. Generic Basic intent integration and removal of old protection policy.
6. Fixed blocks and SimpleSignals policy.
7. Reactive state/dependency scheduling.
8. Authoring/rendering and optional customization runtime.
9. Timetable audit/applied-plan dependency integration and lifecycle closure.

Each commit must explain which runtime flow it changes, the mechanism used, the remaining transitional owner and the focused verification performed. Stop for user review after every commit.

## 进路自动生成与鼠标替换：当前批次

- [x] 创建完整的新保护区域时自动生成各有向边界组合的最短进路；复用现有异步编辑与校验发布。
- [x] 将录制改为左键入口、Shift + 左键出口、右键确认；移除录制命令及旧的潜行取消手势。
- [x] 将保存由单纯追加改为对应入口—出口组合的路径替换，保留已有进路身份及引用。
- [x] 为多通路组合提供出入口警告信息；先记录共用视觉规范，再接入边界警告显示。
- [x] 覆盖唯一/多通路、方向限制、等长选择、不可达、鼠标交互、绑定保留、编辑拒绝及修复不覆盖测试。
- [x] 明确离线验证与真实客户端显示验证的边界；独立审查通过，无需代码修正。本批提交后停止等待用户检查。

聚焦检查实际执行133项/6套，全部通过，新增20项。独立完整门禁通过：41套/427项，零失败、错误或跳过。
完整测试与JAR隔离实际执行；生产/测试编译、JAR及Java consumer编译复用缓存，本批编译已在聚焦检查执行。
日志`/tmp/javarailways-automatic-routes-check.log`；未验证真实Folia、客户端画面或规模延迟。
搜索和警告几何在worker，每个入口一次最短路搜索；替代通路用移除路径连接后的局部可达性检查。
没有新增每tick图遍历或schema升级；完整TextDisplay、信号机Wand、测距及单线多闭塞协调不在本批，Stage2继续进行。

## Rollback points

- Keep module extraction, protection migration, route integration, reactive policy, persistence and rendering in separate commits.
- Do not delete the old owner until the new owner is wired and parity tests pass in the same reviewed slice.
- Do not enable permissive aspects until resource/grant/constraint integration tests pass.
- Leave `SimpleSignals` disabled for a world when persisted state or topology derivation cannot be validated safely.
