# Journal - ARTI5T (Part 1)

> AI development session journal
> Started: 2026-08-28

---


## Session 1: Allow station berths without door operations
<!-- trellis-session: v=2 fp=7449292d60e27fda -->

**Date**: 2026-09-17
**Task**: Allow station berths without door operations
**Package**: javarailways
**Branch**: `feat/webui-timetable-editor`

### Summary

Removed the stale StationBerthRole DoorSide.NONE restriction, added registration regression coverage, explicitly ignored and documented local MCSM test-server operations, verified Basic and BukkitPlugin tests, deployed hash 22b0b36c to JavaRailwaysTest, and confirmed successful Folia startup.

### Git Commits

| Hash | Message |
|------|---------|
| `33e0558` | fix(station): allow berths without door operations |

### Status

[OK] **Completed**


## Session 2: Unify SimpleATO and manual train dynamics
<!-- trellis-session: v=2 fp=0a524d6d42472db4 -->

**Date**: 2026-09-27
**Task**: Unify SimpleATO and manual train dynamics
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

SimpleATO and manual control now share Basic handle-based train dynamics; gravity is configured as track.gravity; operating direction is separate from model direction; reverse-grade force semantics were fixed and covered by tests.

### Git Commits

| Hash | Message |
|------|---------|
| `560fb0f0` | feat(physics): unify manual and ATO train dynamics |

### Status

[OK] **Completed**


## Session 3: Complete Stage 1 signal add-on host APIs
<!-- trellis-session: v=2 fp=3ba35f0ce1c54458 -->

**Date**: 2026-09-27
**Task**: Complete Stage 1 signal add-on host APIs
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Completed and archived Stage 1 host APIs. Applied timetable routes now install static infrastructure edit dependencies; signal restrictions are consumed by train controllers before every physics epoch without depending on Dispatcher progress; Bukkit exposes and verifies edit-permit, conflict-inspection, source-lifecycle, and related host services through a real external Java consumer. Updated owner specs and task acceptance evidence, reran Core/Basic/SimpleATO/BukkitPlugin tests plus consumer compilation and shadowJar after the separate shared-dynamics change 560fb0f0, and left Stage 2 plus the endpoint-format migration open for later work.

### Git Commits

| Hash | Message |
|------|---------|
| `57ab8374` | feat(simpleato): lock applied timetable infrastructure |
| `47cd99db` | fix(simpleato): enforce signal restrictions on physics ticks |
| `98627482` | test(signal-api): verify host service contracts |
| `ae55fa6d` | docs(signal): close stage 1 host API task |

### Status

[OK] **Completed**


## Session 4: Stage 2 local topology revalidation checks
<!-- trellis-session: v=2 fp=4955ffb73602285c -->

**Date**: 2026-10-05
**Task**: Stage 2 local topology revalidation checks
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Completed local topology invalidation review and offline cross-module gate; retained active Stage 2 task for user review.

### Main Changes

- Core affected-rail notifications drive Basic and ATO local validity plus SimpleSignals worker revalidation.
- Reviewed late-epoch output continuity, invalid-route occupant retention, overlap repair dependencies, fresh-frame coherence and consecutive swaps.

### Git Commits

| Hash | Message |
|------|---------|
| `9c4491cf` | fix(signals): invalidate topology dependencies locally |

### Testing

- [OK] 276 suites, 1655 tests: 1648 passed, 7 baseline skips, zero failures/errors; final SimpleSignals tests and verifyPluginJar executed, unchanged modules reused prior results.
- [OK] Java consumer compile, Bukkit shadowJar, diff check and 12/14 context entries validated; no live Folia or scale test.

### Status

[OK] **Completed**

### Next Steps

- Stop for user review of 9c4491cf; Stage 2 remains in progress. Startup recovery and coordinate remapping are not complete.


## Session 5: Protection region current route preview
<!-- trellis-session: v=2 fp=44e06b6074677aa8 -->

**Date**: 2026-10-05
**Task**: Protection region current route preview
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Implemented read-only current-turnout protection route preview in SimpleSignals; 34 new tests and 392 module tests passed; route recording and live platform validation remain pending.

### Main Changes

- Added /simplesignals wand route: left entry, right exit, current physical choices only, explicit actual stop reasons.
- Coalesced relevant events on workers; prepared bounded particle batches; closed subscriptions and late tasks on player/world/plugin teardown.
- Independent review removed unreachable endpoint selection logic and fixed pending invalidation to unchanged-region recovery notification.

### Git Commits

| Hash | Message |
|------|---------|
| `dee2409d` | feat(simplesignals): preview current protection routes |

### Testing

- [OK] Focused 33 preview tests and one manager regression passed; regression failed before notification fix.
- [OK] Final offline SimpleSignals check and Java consumer gate passed: 40 suites, 392 tests, zero failures/errors/skips; tests and verifyPluginJar executed, Java consumer reused cache.
- [OK] git diff --check and task manifests validation passed; live Folia/client and load tests not performed.

### Status

[OK] **Completed**

### Next Steps

- Stop for user review; route recording is the next implementation item. Stage 2 remains active.


## Session 6: Record current protection routes
<!-- trellis-session: v=2 fp=eb07479312b705ec -->

**Date**: 2026-10-05
**Task**: Record current protection routes
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Added explicit current-preview recording through the existing fixed table transaction. Fifteen new tests; 407 module tests pass; Stage 2 remains active.

### Main Changes

- Added /simplesignals route record on the existing route wand; captured complete preview path freezes once and never retraces or requests resources.
- First table constrains planning; append preserves table ID/routes/defaults; exact-path duplicate performs no edit; current region binding strictly validates retained old paths.
- Reused Choice lifecycle and real manager edit/store/source transaction; recording callback is required, with no optional legacy constructor mode.

### Git Commits

| Hash | Message |
|------|---------|
| `0dd7b2be` | feat(simplesignals): record current protection routes |

### Testing

- [OK] Focused 107 tests in four suites pass; final offline SimpleSignals check and Java consumer gate pass: 40 suites, 407 tests, zero failures/errors/skips.
- [OK] Real manager/store/backend/Basic planner prove save and reload, branch selection, duplicate/append/default/freeze; actual registry static/live blockers preserve disk/publication/source.
- [OK] Tests and JAR isolation executed in final gate; compilation and unchanged Java consumer reused focused outputs. No live Folia/client/load verification.

### Status

[OK] **Completed**

### Next Steps

- Stop for user review. Route deletion/replacement/default configuration and signal-machine placement remain later batches; do not archive Stage 2.


## Session 7: Automatic protection routes and mouse replacement
<!-- trellis-session: v=2 fp=5d701885b60d1c24 -->

**Date**: 2026-10-05
**Task**: Automatic protection routes and mouse replacement
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Complete reviewed protection-region authoring batch: shortest route tables, endpoint warnings and mouse confirmation with stable route replacement. Stage 2 remains in progress.

### Main Changes

- New complete regions atomically save direction-legal shortest routes; no valid routes reject the complete candidate; repairs preserve authored tables.
- Left selects entry, sneak-left selects exit, right confirms stable-ID path replacement; removed recording command and old cancel gesture.
- Local Dijkstra and omitted-edge reachability run on workers; endpoint warning geometry is cached and bounded at 512 particles per 20-tick display refresh; specs and README synchronized.

### Git Commits

| Hash | Message |
|------|---------|
| `6ee799fb` | feat(simplesignals): generate and edit protection routes |

### Testing

- [OK] Focused 133 tests / 6 suites passed; 20 added tests.
- [OK] Independent full gate: SimpleSignals check and Java API consumer compile passed; 41 suites / 427 tests actually executed, zero failures/errors/skips; verifyPluginJar executed, compilation and Java consumer reused cache.
- [OK] git diff checks and task manifests validated; gate log /tmp/javarailways-automatic-routes-check.log. Live Folia/client and scale latency not tested.

### Status

[OK] **Completed**

### Next Steps

- Wait for user review of 6ee799fb before choosing next Stage 2 batch; do not archive unfinished task.


## Session 8: 修正列车三维坐标约束并比较性能
<!-- trellis-session: v=2 fp=473e412694144edd -->

**Date**: 2026-10-11
**Task**: 修正列车三维坐标约束并比较性能
**Package**: javarailways
**Branch**: `codex/train-coordinate-performance`

### Summary

修复真实三维轴距、车钩及恢复检查；按用户范围排除极紧曲线特殊搜索。全量1093项零失败、6项已有跳过；84次前后及补测均通过50ms p95预算，曲线不宣称全面提速。证据和比较报告已归档至10-11-train-coordinate-performance。保留无关改动，未推送或部署。

### Git Commits

| Hash | Message |
|------|---------|
| `24035887` | fix(core): enforce rigid train connector geometry |

### Status

[OK] **Completed**


## Session 9: Fix editor UUIDs and migrate section direction glyphs
<!-- trellis-session: v=2 fp=f51f32e60df0bdd9 -->

**Date**: 2026-10-16
**Task**: Fix editor UUIDs and migrate section direction glyphs
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Committed prior deployed shared-runtime fix separately; corrected generated-ID UI, added oriented TextDisplay editor glyph API/client pack, migrated RailObjectWand directions and shared section overlays with Signal Wand. Stage 2 remains in progress; no new deployment.

### Main Changes

- Preserved persistent identities/admin diagnostics and unrelated dirty files; shared cached glyph geometry and viewer-scoped cleanup.

### Git Commits

| Hash | Message |
|------|---------|
| `54a10deb` | fix(signals): share host Kotlin runtime across plugin boundary |
| `40a62c1d` | fix(editor): hide generated identities in authoring messages |
| `44c510bb` | feat(editor): add oriented TextDisplay glyphs and client pack |
| `1e569162` | refactor(editor): render RailObjectWand directions as text glyphs |
| `66cc3314` | fix(signals): share section glyph overlays with Signal Wand |

### Testing

- [OK] Bukkit 410 and SimpleSignals 589 tests passed; Java consumer, plugin/module gates, reproducible resource ZIP and embedded WebUI checked.

### Status

[OK] **Completed**

### Next Steps

- Review four UI commits, then obtain deployment approval for both JARs and perform real-client glyph/entry/cleanup acceptance.


## Session 10: Wand i18n and shared F mode routing
<!-- trellis-session: v=2 fp=38339a174fb5255a -->

**Date**: 2026-10-16
**Task**: Wand i18n and shared F mode routing
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

提取公开无状态Wand F路由，SimpleSignals按玩家locale呈现中英工具文案并接入模式切换；不部署，Stage 2待真实客户端验收。

### Main Changes

- 宿主公共helper先取消换手再回调，只响应主手；信号业务/PDC/会话仍由消费者拥有。
- 中英各223键，WandText跨worker传消息键与参数；区间文字本地化复用几何缓存。

### Git Commits

| Hash | Message |
|------|---------|
| `81621f64` | feat(editor): expose shared wand F-key mode routing |
| `fcc839d7` | feat(signals): localize authoring tools and enable F mode switching |

### Testing

- [OK] Bukkit定向13项和SimpleSignals定向6项通过；编译、Java消费者、双JAR与模块边界通过，未跑整套。

### Status

[OK] **Completed**

### Next Steps

- 获准后同时部署两JAR；游戏验收F、中文文案与旧编辑接续失效；不归档Stage 2。


## Session 11: Unified section wand and crossing enclosure
<!-- trellis-session: v=2 fp=eaef8cc77b07fb19 -->

**Date**: 2026-10-16
**Task**: Unified section wand and crossing enclosure
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

统一区间工具按围合拓扑自动生成闭塞或保护区域，菱形交叉只参与冲突围合不增加通行方向；本批未部署。

### Main Changes

- pending边界沿用现有模型；最近切口一次行走保留普通闭塞端点/长区间能力，保护区域修复保留ID和设备。
- F仅创建/删除，状态栏复用overlay；共享有向边界删除/警告按内向选择，不猜测。

### Git Commits

| Hash | Message |
|------|---------|
| `03bf4acd` | fix(signals): enclose all diamond crossing conflict legs |
| `24a84a2a` | feat(signals): unify section wand with automatic interval classification |

### Testing

- [OK] 24项定向检查通过，9秒；编译及JAR/模块边界通过，未跑完整套件；首次缺失测试import已修。

### Status

[OK] **Completed**

### Next Steps

- 检查两笔提交；获准部署前核对旧交叉保护区域完整性，部署仅更新Signals并重发section工具；游戏验收后再收尾Stage 2。


## Session 12: 无向区间切口与内部方向推导
<!-- trellis-session: v=2 fp=46f016daf3eff0c4 -->

**Date**: 2026-10-16
**Task**: 无向区间切口与内部方向推导
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

区间Wand取消玩家朝向要求，按连通分量推导正式入口；本地完成并提交，未部署，Stage 2继续。

### Main Changes

- 新切口两侧局部搜索，唯一内部自动完成，多解左键内部确认；旧pending朝向不参与选择，正式区域及信号仍有向。
- 沿用唯一manager事务和现有拓扑，保留修复身份及邻区所有权；歧义共享保护切口删除明确拒绝。同步中英文与owner合同。
- 普通接头两轨坐标按真实拓扑识别同一几何切口，避免零长度后继误判；未触及其他任务的脏文件。

### Git Commits

| Hash | Message |
|------|---------|
| `bec5cee9` | feat(signals): derive section directions from undirected cuts |

### Testing

- [OK] 定向门禁22项/3套通过（8秒）；最终接头创建/删除2项通过（3秒），合计23个不同用例。生产/测试源编译及verifySignalModuleBoundaries/verifyPluginJar通过，独立静态复核和diff检查通过。未跑全仓测试。
- [OK] 未部署、未修改开发服数据；真实Folia/客户端与规模延迟未验证。review工具返回queued，未确认窗口已打开。

### Status

[OK] **Completed**

### Next Steps

- 等待用户检查bec5cee9；获得单独部署指令后再备份并部署，现场验证既有四pending交叉渡线围合。共享多保护区cut删除目标选择仍未实现。


## Session 13: 清理无向切口的方向占位
<!-- trellis-session: v=2 fp=67960c32c0ae6864 -->

**Date**: 2026-10-16
**Task**: 清理无向切口的方向占位
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

pending、区域修复草稿与工具输入使用真正无向模型，严格current格式保持schema1，无旧reader；仅本地提交，未部署。

### Main Changes

- 新增模块私有RailCutPosition和ProtectionBoundaryCut，pending/draft保存DTO只有boundaryId、railId与canonical AB米数；正式认可边界和信号方向保留。
- 去掉AB输入占位、effectiveBoundaries及undirectedBoundaryIds；几何/编辑影响读effectiveCuts，有向编辑读placedEntrances，仍保留完整认可边界的禁行依据。同步owner合同及必要测试类型。

### Git Commits

| Hash | Message |
|------|---------|
| `50afd64c` | refactor(signals): model pending section cuts without direction |

### Testing

- [OK] 生产编译5秒；最终定向门禁11秒，28项/6套零失败、错误或跳过。生产及全部测试源编译、模块/JAR隔离通过，Java consumer编译门禁up-to-date；checksum同步与diff检查通过，独立静态复核无阻断。
- [OK] 只新增1项codec关键用例：pending/draft恰为三字段，正式边界保留insideFacing，两处旧字段分别严格拒绝。其余为既有测试适配；未运行全仓测试或现场验收。

### Status

[OK] **Completed**

### Next Steps

- 用户检查50afd64c；预览请求queued，尚未确认打开。部署前另行批准整目录打包备份与开发服pending/draft一次性迁移；本批不操作远端，Stage 2保持进行中。


## Session 14: 无向切口开发服迁移与部署
<!-- trellis-session: v=2 fp=2615e60b2bb960ac -->

**Date**: 2026-10-16
**Task**: 无向切口开发服迁移与部署
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

用户授权后正常停服，完整外部备份并迁移旧pending方向字段，部署已验证Signals候选；启动和只读运行状态检查通过。

### Main Changes

- 目标仅192.168.1.230的836082a48c4f48fcb1fea4e83c57dc96实例。整包备份两配置目录与旧JAR，恢复点undirected-cuts-20261016-NKBtfewt；primary4个及bak3个pending只移除insideFacing，其他120文件hash一致。
- 仅更新SimpleSignals SHA38f0cbc6cbb742f88efcc6c4bf9f191a976b997da6d9e981df757e5faa156f0c；主插件/客户端包不变。迁移脚本与计划/结果audit保留外部恢复目录，不进入JAR，无runtime兼容。

### Git Commits

| Hash | Message |
|------|---------|
| `e1bb62cc` | docs(signals): record undirected cut migration and deployment |

### Testing

- [OK] 一次性脚本语法及3项轻量合成检查、独立只读复核、远端dry-run/精确输入hash和完整语义对比通过。不重复产品测试或构建。15:53:23 Done，primary加载、bridge ok、WebUI200，ATO RUNNING，MCS3/PID508478。
- [OK] 仍有既有Terra Unsafe反射错误/离线模式告警；本次检查无新类加载或physics overload/clamp。没有执行玩家围合、创建测试对象或应用时刻表，不宣称现场行车/视觉验证完成。

### Status

[OK] **Completed**

### Next Steps

- 玩家进游戏用区间工具左键原交叉渡线内部确认已有4个无向切口；继续现场验收。Stage 2保留in_progress。恢复须旧JAR和旧方向数据同恢复点配套，不能仅降级JAR。


## Session 15: Section route overlays, unbound devices and confirmed deletion
<!-- trellis-session: v=2 fp=da262f7d012eb0cc -->

**Date**: 2026-10-16
**Task**: Section route overlays, unbound devices and confirmed deletion
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

完成已批准三笔区间交互实现；本地定向门禁和独立复核通过，未部署，Stage2保持in_progress。

### Main Changes

- Overlay按入口出口仍放置的已保存进路合并中心线，不改变草稿禁行。
- 固定闭塞主信号和表示器支持显式null绑定与原设置页手动重绑，保留模型/身份/附属，不读旧开发格式或升版。
- 区间Wand严格物品状态、Shift右键保留设备设置、F保偏好、右键确认整体区域删除；复用唯一patch、编辑许可及原子publication。
- 表示器尚无snapshot时使用当前定义的noRoute初始输出/模型，已有交接仅保留current IDs，不继承普通编辑前物理baseline。

### Git Commits

| Hash | Message |
|------|---------|
| `4532774e` | fix(signals): retain only confirmed route centerlines in region drafts |
| `11d6432f` | feat(signals): retain unbound section devices and support rebinding |
| `81ffc387` | feat(signals): add whole-region deletion and device-retention settings |

### Testing

- [OK] 三笔最终定向门禁分别3/14/37项通过；第三笔37项及JAR隔离11秒，测试清理调整后仅重跑组合1项8秒通过，未跑全套。
- [OK] verifyPluginJar与verifySignalModuleBoundaries实际执行通过；宿主/API消费者编译复用缓存；source checksum同步一致、diff检查及独立只读复核通过。
- [OK] 实际manager/store/Basic许可及模型场景有断言；平台handler调度使用替身，不称真实Folia/客户端验证。

### Status

[OK] **Completed**

### Next Steps

- 交用户统一检查三笔提交；另获部署授权后备份并部署，新区间Wand需重新领取，再做游戏内验收。


## Session 16: Deploy section editing and retained-device changes
<!-- trellis-session: v=2 fp=37c69d8f608ba194 -->

**Date**: 2026-10-16
**Task**: Deploy section editing and retained-device changes
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

用户批准后正常停服、外部完整打包备份，仅部署已验证的SimpleSignals三笔提交；运行加载及健康通过，未做游戏内验收。

### Main Changes

- 部署4532774e/11d6432f/81ffc387产物SHA bef59191f61d17100999de95233b026e8bdfa6106f965896c953d419f6efbebc；宿主与配置不变，无迁移。
- 外部恢复点section-retain-20261016-L6ndpF包含完整两配置目录和两旧JAR，manifest124项核验，替换外另123项hash不变。

### Git Commits

| Hash | Message |
|------|---------|
| `e5b4faf7` | docs(signals): record section editing deployment |

### Testing

- [OK] MCS正常停服保存和启动Done26.718s；primary rev22安装，1区域/1表/2闭塞保留，ATO RUNNING，bridge ok:true/WebUI200；无新类加载错误，既有TerraUnsafe告警未扩修。
- [OK] 部署记录和一次性脚本独立只读复核、git diff检查通过；本次未重复离线测试或构建。

### Status

[OK] **Completed**

### Next Steps

- 重新领取区间Wand，在游戏内验证Shift设置/F偏好、中心线与整区删除，Stage2继续in_progress。


## Session 17: Persist exact rail endpoint identities
<!-- trellis-session: v=2 fp=47b87306e2bd8538 -->

**Date**: 2026-10-17
**Task**: Persist exact rail endpoint identities
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Implemented UUID+A/B connection and active-selection persistence, shared pre-world graph validation, prepared/undo/simulation consumers and independent offline conversion. Six affected modules: 2259 tests passed with zero failures/errors/skips; validated 12 file pairs copied read-only from remote 192.168.1.230, source/runtime bytes and unrelated infrastructure preserved. User-approved work commit and task archive complete; other dirty work excluded, no push or deployment.

### Git Commits

| Hash | Message |
|------|---------|
| `f90c6805` | fix(persistence): persist exact rail endpoint identities |

### Status

[OK] **Completed**


## Session 18: Shared boundary display names
<!-- trellis-session: v=2 fp=246424b6f30645dd -->

**Date**: 2026-10-17
**Task**: Shared boundary display names
**Package**: simple-signals
**Branch**: `dev/signal-system`

### Summary

实现可重复的边界名称、共享边界同步、一次命名操作及进路端点标签；仅提交本任务，保留其他修改。

### Main Changes

- Flat raw-cut name metadata in existing schema1; exact shared identity, conflicts, lifecycle cleanup and guarded UI.
- Scoped staging excludes previous empty-dialog, diagnostics, logging, model and physics work; task archived.

### Git Commits

| Hash | Message |
|------|---------|
| `6390dddd` | feat(signals): add shared boundary display names |

### Testing

- [OK] Combined workspace59 suites/622 tests passed; isolated HEAD plus exact scoped commit57 suites/614 tests passed, no failures/errors/skips.
- [OK] SimpleSignals check/JAR, API consumer and root module boundary checks passed; no push or deployment.

### Status

[OK] **Completed**

### Next Steps

- Real Paper/Folia naming input and visible label layout await in-game acceptance.


## Session 19: Commit preserved backlog and integrate World epoch events
<!-- trellis-session: v=2 fp=1394a35c58863387 -->

**Date**: 2026-10-19
**Task**: Commit preserved backlog and integrate World epoch events
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

用户授权提交dev/signal-system全部积压改动。分批提交日志/信号/Wand、表示器示例、原模型资源和文档，docs-site独立提交29a59d8。无冲突整合事件分支；组合2343项测试及API/JAR/模块/Dokka通过。首轮测试类加载异常证据保留，针对性重建后完整通过，无源码改动。仅归档完成的World事件子任务，后续隔离/信号/行为及Stage 2仍未完成。无部署、迁移或推送。

### Git Commits

| Hash | Message |
|------|---------|
| `dfc7b0e7` | fix(signals): complete diagnostics and authoring fixes |
| `cd2d04bb` | feat(signals): add route indicator model example |
| `abec3837` | chore(models): track local model and resource packages |
| `01d2c7fe` | docs: synchronize operational architecture and audit records |
| `423ea7dd` | merge: integrate world epoch events with runtime fixes |

### Status

[OK] **Completed**

### Next Steps

- 按已规划的World隔离子任务继续；尚未开始实现。


## Session 20: World runtime isolation and observation completion
<!-- trellis-session: v=2 fp=a65ef3002e9e7b48 -->

**Date**: 2026-10-19
**Task**: World runtime isolation and observation completion
**Package**: javarailways
**Branch**: `dev/signal-system`

### Summary

Complete and integrate per-world runtime ownership, scheduling, shared domain assembly and observation O1/O2;2395 tests passed, task2 archived, Signals JS remains next batch.

### Main Changes

用户确认最终三笔提交清单后完成本地提交和快进整合。

- Core5d8cdabe：每World host owner、帧间入口与真实worker终止确认。
- Runtime1423116d：逐World runner/入口、必要LIVE管理事务、共同领域装配及O1/O2观察构造隔离。
- docs-site独立提交cb9263b，主docs工作区已快进更新，主仓gitlink一致。
- 原42源码2391项仅历史；最终44源码fingerprint702e1db6c9b6e16e8af8cb18cfdec639f9d204d432f7f4441e7383a842bd3c4e，356 XML/2395项全执行且0失败/错误/跳过。root与独立checker逐源/逐XML/logSHA复核。整合到主工作区后产品字节再次核对相同，无需重复无源变更的完整构建。
- Java API consumer/module boundary/shadowJar/Dokka和修改后docs完整check（TS/58 YAML/schema fixtures/reference/Docusaurus/Pagefind27页）通过。既有前端108项为相同前端源码历史证据，本轮未重复计为新执行。
- 子任务2已归档至.trellis/tasks/archive/2026-10/10-18-world-runtime-isolation，链接和18项manifest有效。父任务进度2/4；子任务3 LIVE/SIM JS隔离、子任务4车厢行为及父AC4/5/7保持开放。
- 不推送、不部署、不做持久化迁移；Paper/Folia/客户端现场验证未做。同JVM永不退出callback只能保留依赖等待，不能安全强杀释放。
- 临时node_modules链接已移除，主依赖未改。主区重复task元数据在/tmp/jr-world-main-integration-_teu9c8d备份后按已有完整提交整合，无未识别改动。

后续：按已更新子任务3的S1-S3计划开展真实规则求值隔离及完整信号模拟安装；当前收尾不自动启动新批次。


### Git Commits

| Hash | Message |
|------|---------|
| `5d8cdabe` | feat(core): add per-world host ownership and termination barriers |
| `1423116d` | feat(runtime): isolate world scheduling and observation construction |

### Status

[OK] **Completed**


## Session 21: Commit isolated simulation runtime and final acceptance
<!-- trellis-session: v=2 fp=8205ac7e690d9ad2 -->

**Date**: 2026-10-20
**Task**: Commit isolated simulation runtime and final acceptance
**Package**: javarailways
**Branch**: `codex/simulation-world-alignment`

### Summary

Committed world-local signal engines, complete domain simulation and real per-car behavior, including exact failed-install retirement and bounded lifecycle ownership. Independently verified staged bytes match the accepted 53-path candidate: 2507 JVM tests and 108 frontend tests passed, with API, module, package and documentation gates. Committed docs-site as 3bdd5626, archived only the completed behavior task and repaired its context links. Remain on codex/simulation-world-alignment; no merge, push, deployment or runtime server acceptance.

### Git Commits

| Hash | Message |
|------|---------|
| `207807eb` | feat(simulation): complete world-isolated signal and car behavior runtime |
| `3e53b3d7` | docs(trellis): record complete isolated simulation acceptance |

### Status

[OK] **Completed**


## Session 22: Commit and deploy station operation programming
<!-- trellis-session: v=2 fp=ab584619e26cf82c -->

**Date**: 2026-10-22
**Task**: Commit and deploy station operation programming
**Package**: javarailways
**Branch**: `codex/station-operation-programming`

### Summary

Committed station programs and fixed EMU operations, preserved deployed signal issue recovery, backed up and deployed JavaRailwaysTest; ATO/signals/WebUI health and artifact hashes verified. Further game acceptance and fixes deferred per user.

### Git Commits

| Hash | Message |
|------|---------|
| `e38c67a5` | feat(timetable): add shared station programs and fixed EMU operations |
| `3b09e17b` | fix(signals): preserve deployed rule issue recovery behavior |
| `290aa909` | docs(trellis): record station programming deployment verification |

### Status

[OK] **Completed**

### Next Steps

- Observe real game/client behavior before further fixes; no automatic replay or additional feature changes.


## Session 24: Shared Blockly editor and arrival programs
<!-- trellis-session: v=2 fp=ab4a539caee8d762 -->

**Date**: 2026-10-24
**Task**: Shared Blockly editor and arrival programs
**Package**: javarailways
**Branch**: `feat/station-program-blocks`

### Summary

Implemented Blockly 13.4.0/Zelos shared program library and arrival editor; integrated actual stopped/doors-open arrival execution, snapshot validation, source navigation and protected workspace drafts. Passed 192 frontend and 692 backend tests, production build and real WebUI disk save/reopen browser checks. User approved commit and task archive. No push, deployment or live Paper/Folia client acceptance.
## Session 23: Disk error logs in WebUI and current world issues
<!-- trellis-session: v=2 fp=60f44971b5bf1af9 -->

**Date**: 2026-10-23
**Task**: Disk error logs in WebUI and current world issues
**Package**: javarailways
**Branch**: `feat/error-log-webui`

### Summary

Implemented independent authenticated disk log page, module/Throwable/context logging and bounded queue; world issues reflect current content only. Backend 2515 and frontend 115 tests passed; packaging and local browser verified. No deployment or live Paper/Folia acceptance.

### Git Commits

| Hash | Message |
|------|---------|
| `ec00c91c` | feat(webui): add shared Blockly editor and arrival programs |
| `64129b79` | feat(diagnostics): expose disk logs in WebUI and clarify world issues |

### Testing

- [OK] 2515 backend tests and 115 frontend tests passed; production builds and packaging verified
- [OK] Local real WebUI browser: world outage, disk error stack, paging and rotation reset passed

### Status

[OK] **Completed**

### Next Steps

- Review or merge feat/error-log-webui; live Paper/Folia acceptance remains separate


## Session 25: Rewrite duty editor as a timed task timeline and deploy
<!-- trellis-session: v=2 fp=3eb40e71203c8b4d -->

**Date**: 2026-10-27
**Task**: Rewrite duty editor as a timed task timeline and deploy
**Package**: webui
**Branch**: `codex/timetable-station-programs`

### Summary

Implemented RUN intervals and program point/manual/simulated durations; true dispatcher task timing; offline migration and verified developer-server deployment. Task remains unarchived for player acceptance.

### Main Changes

- Replaced duty Scratch editor with controlled timeline; drag/resize, current schema and task timing metadata.

### Git Commits

| Hash | Message |
|------|---------|
| `8ae62d56` | Replace duty block editor with a task timeline and measured durations |
| `38f8bb54` | Respect independent document storage identity during timeline migration |
| `39ea6890` | Identify JVM executables accurately in offline migration guard |

### Testing

- [OK] Frontend217/backend1309/migration14 tests pass; real browser save/restart and public17assets verified.

### Status

[OK] **Completed**

### Next Steps

- Player verification of timeline UI and live program scheduling; keep existing task in_progress.


## Session 26: Fix waypoint pruning station and direction semantics
<!-- trellis-session: v=2 fp=dec7f8e23fd19ece -->

**Date**: 2026-10-27
**Task**: Fix waypoint pruning station and direction semantics
**Package**: webui
**Branch**: `codex/timetable-station-programs`

### Summary

Corrected same-station and direction-aware waypoint pruning; baseline-verified fixture corrections; deployed and reproduced original UP2U/4U Reset success.

### Main Changes

- SimpleATO owns canonical station exceptions; Basic evaluates optional blocker facing on actual local rail traversal; structural queries share semantics.

### Git Commits

| Hash | Message |
|------|---------|
| `a277f64c` | fix(routing): respect station and traversal direction when pruning waypoints |

### Testing

- [OK] 2401 backend tests pass. Original UP2U/4U and DOWN Reset HTTP200. All7editor/libraryfiles unchanged,17servedassets match.

### Status

[OK] **Completed**

### Next Steps

- Player game verification; keep current task in_progress and unarchived.


## Session 27: Feedback round3: numeric IDs and formation-unit picker
<!-- trellis-session: v=2 fp=a80e50d43a0fa0e7 -->

**Date**: 2026-10-27
**Task**: Feedback round3: numeric IDs and formation-unit picker
**Package**: webui
**Branch**: `codex/timetable-station-programs`

### Summary

Implemented feedback8/15; browser and tests pass. Deployment upload blocked by auto-review, awaiting explicit payload/target authorization; live unchanged. Task remains unarchived.

### Git Commits

| Hash | Message |
|------|---------|
| `cae124d9` | fix(webui): simplify authoring IDs and formation unit selection |

### Testing

- [OK] Frontend234; WebUI36; production build; browser history, invalid raw repair and restart persistence

### Status

[OK] **Completed**

### Next Steps

- After explicit approval deploy cae124d9 with backup; retain task for game acceptance


## Session 28: Deploy feedback round3
<!-- trellis-session: v=2 fp=602eec30c30c2b18 -->

**Date**: 2026-10-27
**Task**: Deploy feedback round3
**Package**: webui
**Branch**: `codex/timetable-station-programs`

### Summary

User explicitly authorized upload/deploy. Deployed numeric authoring IDs and formation-unit picker with recovery6kmrt8zt. Data and other plugins unchanged; bridge healthy,17 public/local assets match,ATO RUNNING. Keep task unarchived for game acceptance.

### Git Commits

| Hash | Message |
|------|---------|
| `cae124d9` | fix(webui): simplify authoring IDs and formation unit selection |

### Status

[OK] **Completed**


## Session 29: Feedback round4: explicit time points and object actions
<!-- trellis-session: v=2 fp=cf4e22c20d4c63e3 -->

**Date**: 2026-10-27
**Task**: Feedback round4: explicit time points and object actions
**Package**: webui
**Branch**: `codex/timetable-station-programs`

### Summary

Fixed feedback12/13 with explicit time-point creation, shared dialog and real object menus; stale/reference guards and numeric IDs preserved. Frontend236/WebUI36 pass, real browser acceptance and restart checked. Deployed with recoveryekuh0eth;17assets match,bridge healthy,ATO RUNNING. Keep task unarchived.

### Git Commits

| Hash | Message |
|------|---------|
| `ceea884e` | fix(webui): expose time point creation and object actions |

### Status

[OK] **Completed**


## Session 30: Unify graph and duty timed operations
<!-- trellis-session: v=2 fp=45ec2d5b2bcc00d2 -->

**Date**: 2026-10-27
**Task**: Unify graph and duty timed operations
**Package**: webui
**Branch**: `codex/timetable-station-programs`

### Summary

Unified model/shared fields, graph reference and duty projection ownership, generation/hash/navigation/persistence. Frontend250/WebUI40/migration9 tests pass; actual browser checks and restart recovery pass. Deployment upload blocked by automatic review; requested explicit payload/target authorization; live unchanged.

### Git Commits

| Hash | Message |
|------|---------|
| `a824bc2e` | feat(webui): unify graph and duty timed operations |

### Status

[OK] **Completed**


## Session 31: Fixed-block direction decoupling and test-server deployment
<!-- trellis-session: v=2 fp=754a80a6769c7052 -->

**Date**: 2026-10-30
**Task**: Fixed-block direction decoupling and test-server deployment
**Package**: simple-signals
**Branch**: `dev/signal-system`

### Summary

Removed independent fixed-block direction permissions; ATO validates actual movement/body/stopping margin. Retained already-deployed 4c19f7f4 features, committed integrated release220e705e and deployed both plugins with an audited 25-field primary/backup conversion and full recovery archive. ATO RUNNING, Signals revision63, HTTP assets match; no push.

### Main Changes

- Preserved resource ownership through mixed-direction double reversal; Basic/Core source unchanged relative to deployed baseline.

### Git Commits

| Hash | Message |
|------|---------|
| `188c24c6` | refactor(simplesignals): decouple fixed blocks from operating directions |
| `220e705e` | merge: retain deployed features for fixed-block direction release |
| `d7e9d305` | chore(signals): record audited direction migration and deployment |
| `c1da82ce` | docs: finalize deployment audit and retained artifacts |

### Testing

- [OK] 3001 JVM tests and323 frontend tests passed; migration13 and local installation11 checks passed. ATO tests use2GiB per-class workers; installed old runtime also reproduces1GiB class OOM.
- [OK] Normal save/stop, external recovery hash and exact two-JAR/two-sidecar installation verified; bridge/WebUI and LAN resource hashes pass.

### Status

[OK] **Completed**

### Next Steps

- Manual game-client driving and visual acceptance remain separate; retain recovery archive and preserve unrelated dirty files.


## Session 32: Boundary naming mode and test-server deployment
<!-- trellis-session: v=2 fp=6e8fa62966d785cb -->

**Date**: 2026-10-30
**Task**: Boundary naming mode and test-server deployment
**Package**: simple-signals
**Branch**: `dev/signal-system`

### Summary

Implemented create -> name -> delete section-tool modes and named route labels; 660 tests and module checks passed. Deployed only SimpleSignals to JavaRailwaysTest with normal stop, verified recovery archive, matching artifact hash, ATO RUNNING, signals installed and HTTP checks. Unrelated files preserved; no push.

### Git Commits

| Hash | Message |
|------|---------|
| `5bd00387` | feat(signals): add persistent boundary naming mode |

### Status

[OK] **Completed**

### Next Steps

- Verify F cycling, boundary naming input and labels in the game client.


## Session 33: Complete route position semantics and hand off simulation timing
<!-- trellis-session: v=2 fp=8b8c0d17ce00328e -->

**Date**: 2026-11-06
**Task**: Complete route position semantics and hand off simulation timing
**Package**: javarailways
**Branch**: `codex/ato-per-car-rebuild`

### Summary

Completed and archived route-position-semantics after user approval: preserve canonical endpoints, separate stopping tolerance from geometric projection, remove actual-progress clamping, and retain observed stopped origins. Verified 2646 tests pass and one existing experiment skip; original Tick302/5403 replay failures cleared. Full replay still ends at Tick10026 DelayExceeded1226; controlled diagnostics identify simplified line-preview and dwell-accounting differences, not a blocked reverse. New planning task will address simulation fidelity and discuss intended dwell semantics next turn. No push or deployment; unrelated dirty work preserved.

### Git Commits

| Hash | Message |
|------|---------|
| `c2a62f6d` | fix(route): preserve exact endpoints and observed train progress |

### Status

[OK] **Completed**


## Session 34: Line simulation and dwell semantics verified and deployed
<!-- trellis-session: v=2 fp=0079ee81ea5f25c3 -->

**Date**: 2026-11-06
**Task**: Line simulation and dwell semantics verified and deployed
**Package**: javarailways
**Branch**: `codex/ato-per-car-rebuild`

### Summary

Completed real line measurement and STOP-only arrival-to-close dwell semantics, removed the old estimator and excess door-boundary machinery, and fixed completion-versus-consumption timing without tick tolerance. Verified real S100 measurement/Reset/exact replay, physical reversal/resource waiting, missing-profile and stale Reset failure behavior. Full affected-module gate passed (1507 executed passes, one existing skip); final additions passed backend7 and UI4 plus typecheck. User approved the exact single-task commit and deployment, no push. Deployed ee4dde6d to JavaRailwaysTest after normal stop/save and verified recovery line-dwell-20261106-f1m35v88; new JAR636d3834, ATO RUNNING, Signals117, local/public HTTP health and assets match, original timetable unchanged. Separate known route issues remain; online global simulation was not triggered. Archived only this task and preserved unrelated dirty files.

### Git Commits

| Hash | Message |
|------|---------|
| `ee4dde6d` | fix(simpleato): align line simulation and station dwell semantics |

### Status

[OK] **Completed**


## Session 35: Station target reuse and test-server deployment
<!-- trellis-session: v=2 fp=74f352e140644ddf -->

**Date**: 2026-11-06
**Task**: Station target reuse and test-server deployment
**Package**: javarailways
**Branch**: `codex/ato-per-car-rebuild`

### Summary

Removed repeated StationCall target calculations; 1515 tests passed with one existing skip, Java API compilation passed, and real-data replay cleared the original StationBerth failure. Deployed 5d5a4b7a with verified recovery and domain/HTTP health; preserved current timetable and unrelated work, no push. Tick14219 delay remains separate.

### Main Changes

## Scope and authorization

User requested removal of duplicated StationCall target calculations, then explicitly authorized commit and test-server deployment. No new Trellis task, no Git push, no online simulation or timetable mutation. Unrelated TODO.md and six untracked paths were preserved.

## Implementation and verification

- Work commit: 5d5a4b7a8d05bbfdf26d7c72950e873e292d204f, `fix(simpleato): reuse resolved station stopping targets`.
- ResolvedStationCall computes fixed berth/stopping distance once; Dispatcher, controller and line measurement reuse the result. Removed controller fixed-berth reprojection and unused request overload.
- Structural output keeps its original operating point/selected prefix separately from the physical arrival-window Stop compensation. Nonzero-offset departure tracking uses the actual stopping target. No new tolerance or fallback.
- Full final tests: SimpleATO 788 passed; BukkitPlugin 727 passed and one pre-existing disabled experiment; Java signal API consumer compilation and diff check passed. Focused 95 tests are included, not additional to this total.
- Offline replay of copied real infrastructure/timetable passed original Tick12469 StationBerth failure. It stopped at Tick14219 with duty3/105P DelayExceeded, 1219 ticks versus 1200 threshold. This remains separate and is not full global-simulation acceptance.
- Built Mojang-mapped BukkitPlugin shadowJar successfully; no reobf deployment and no data migration.

## Deployment evidence — 2026-11-06 UTC

- Target: user@192.168.1.230, MCSManager JavaRailwaysTest instance 836082a48c4f48fcb1fea4e83c57dc96. Used /run/host-ssh-agent/agent.sock; no authentication changes or credential copying.
- Installed baseline matched prior ee4dde6d artifact SHA256 636d383407e83c1d81686ad88dbc335e2a2c027a7eea610f9a1124db8776577e.
- New JavaRailways SHA256: 994c5e03f08199d29e9eba3ece105431b4a6e254a32c5fd4be69cc211817c9f3.
- Retained SimpleSignals SHA256: 36657ce6a312e4498aba656060e831b8a0f4beaa6677ecab4cc3d6d715d2f29c.
- Latest user editor document new-timetable-9.json SHA256 before/after: c7dced2d37c395900c056edb2c0e4f7193c7ea60be2ec8144269de90ace2aad9. Did not restore the older document from the prior deployment.
- One online player notified in chat before authorized normal stop. Old PID946187 exited; all worlds/player data saved at17:41:17. No forced termination.
- First backup attempt encountered a service-owned model snapshot; no replacement occurred. Retried the same reviewed helper with existing sudo -n permission without altering file ownership/permissions.
- Recovery: /opt/mcsmanager/backups/javarailways/station-target-20261106-vudeiqai/recovery.zip, 150 files, SHA256 aa1d2f127282a3edade6053b99fa9ef7a31881fc91aa424771df8c0fc98348ff. Membership and each file hash verified; source stability and stopped JVM/listeners checked.
- At17:42:18 atomically replaced only plugins/JavaRailways.jar, preserving metadata. The other149 inventoried files remained unchanged before startup.
- Local/remote staging and exact deployment manifests: /tmp/jr-station-target-deploy-20261106-FvglFTUu. Remote recovery.json/install.json preserve operation evidence.
- New PID948935, manager status3; startup Done at17:42:48. At17:43:03 ATO world RUNNING; SimpleSignals installed, definitionRevision117, regions5/tables5/blocks14/fixedBlockSignals8/protectionSignals4/routeIndicators1.
- Local WebUI29002 and bridge8765 health successful; public http://mc.art1st.work:29002/health returned status ok. Plugin and editor hashes matched after startup.
- No new JavaRailways/SimpleSignals startup failure observed. Existing third-party com.dfsek.seismic Unsafe reflection error and startup physics overload/clamping warnings remain visible; no unrelated fix attempted.

## Recovery and acceptance boundary

For rollback, normal-stop this exact instance and verify the recovery manifest/archive, then restore only its plugins/JavaRailways.jar atomically with original file metadata. Do not overwrite later user data during JAR-only rollback.

Deployment proves installation and domain/HTTP startup, not a successful new online global simulation. The Tick14219 delay remains unresolved. No other Trellis tasks were archived.


### Git Commits

| Hash | Message |
|------|---------|
| `5d5a4b7a` | fix(simpleato): reuse resolved station stopping targets |

### Status

[OK] **Completed**


## Session 36: Timetable door-state validation deployment
<!-- trellis-session: v=2 fp=2c13c60617bf6875 -->

**Date**: 2026-11-07
**Task**: Timetable door-state validation deployment
**Package**: javarailways
**Branch**: `codex/ato-per-car-rebuild`

### Summary

Committed and deployed conservative timetable door-state validation to JavaRailwaysTest from an exact clean snapshot; preserved unrelated work and current timetable data, with verified recovery and runtime health.

### Main Changes

## Scope and implementation

- User explicitly authorized commit and deployment of the F9 timetable door-state static validation; no push or data migration was authorized.
- Work commit `c914b1a345739d8f1bbe57e47f9f9c8cf1468091` adds conservative `UNKNOWN/SECURED/OPEN` proof to the existing TimetableValidator traversal, reports a conflict at the first doorless/movement operation, and keeps runtime door fail-safes.
- The commit includes only six task-owned implementation/test/spec/research files. The existing reversed-departure assertion and all other dirty work remained uncommitted.

## Verification

- Exact staged/committed snapshot was cloned into a clean directory and passed `:SimpleATO:test`: 791 tests, zero failures/errors/skips, using a 4 GiB single test worker after the default and 2 GiB workers exhausted heap without assertion failures.
- Clean commit also built `:BukkitPlugin:shadowJar` and passed `verifySignalModuleBoundaries`.
- Candidate `JavaRailways.jar` SHA256: `e5b8433cd1c21f11d23d24286465586eccbd7eb121114a6ae4a042fc647f73b4`, 67,491,364 bytes.

## JavaRailwaysTest deployment

- Target: `user@192.168.1.230`, MCS instance `836082a48c4f48fcb1fea4e83c57dc96`; used `/run/host-ssh-agent/agent.sock`.
- Installed baseline matched direct ancestor `5d5a4b7a` artifact SHA256 `994c5e03f08199d29e9eba3ece105431b4a6e254a32c5fd4be69cc211817c9f3`.
- One online player was warned in game. MCSManager normal stop saved worlds/player data; old PID `948935` exited and listeners closed before mutation.
- Recovery point: `/opt/mcsmanager/backups/javarailways/door-state-20261107-c5525hbl/recovery.zip`, 150 files, SHA256 `78fe9066f7d86c1b5818eb89d497ea18a48ce4e155a67541f13ed260bd036c91`.
- Only `plugins/JavaRailways.jar` was atomically replaced. The other 149 inventoried files, SimpleSignals, TunnelBridgeBuilder and `new-timetable-9.json` remained unchanged.
- New PID `960260`, startup `Done (26.689s)`, ATO `RUNNING`; SimpleSignals installed at definition revision 117 with 14 blocks. Bridge health returned ok, local/public WebUI returned HTTP 200, and the served main script hash matched the candidate. No matching startup error was found.

## Boundary

Deployment proves exact artifact installation, startup and domain/HTTP health. It does not claim that the user has rerun and accepted the full global simulation. The larger snapshot-engineering audit task remains active and was not archived.


### Git Commits

| Hash | Message |
|------|---------|
| `c914b1a3` | fix(simpleato): validate open-door timetable handoffs |

### Status

[OK] **Completed**


## Session 37: Deploy SimpleATO physical-frame and authority fixes
<!-- trellis-session: v=2 fp=88aed122453cbadf -->

**Date**: 2026-11-07
**Task**: Deploy SimpleATO physical-frame and authority fixes
**Package**: javarailways
**Branch**: `codex/ato-per-car-rebuild`

### Summary

Committed and deployed the verified F1-F8 snapshot, rolling authority, native body speed limit, handoff and exact signal-boundary fixes to JavaRailwaysTest with a 150-file recovery archive; startup, ATO, SimpleSignals and WebUI health checks passed.

### Main Changes

- Committed the exact 51-file F1-F8 implementation, tests, specs and final check report; unrelated dirty files remained excluded.
- Built commit 31e4cf17 from a clean checkout and atomically replaced only plugins/JavaRailways.jar on JavaRailwaysTest.

### Git Commits

| Hash | Message |
|------|---------|
| `31e4cf17` | fix(simpleato): unify physical frame and authority handoff |

### Testing

- [OK] Final review: SimpleATO 809, Core 541, Basic focused 5 and SimpleSignals focused 48 passed; consumer compile, shadowJar and module boundaries passed.
- [OK] Deployment: recovery archive verified, server Done, ATO RUNNING, SimpleSignals revision 117, bridge and WebUI health OK, installed hashes matched.

### Status

[OK] **Completed**

### Next Steps

- User reruns the WebUI global simulation to verify Tick 14219 no longer exceeds the delay threshold on the deployed server.


## Session 38: Correct SimpleSignals host ABI deployment
<!-- trellis-session: v=2 fp=027bd1133fca19c9 -->

**Date**: 2026-11-07
**Task**: Correct SimpleSignals host ABI deployment
**Package**: javarailways
**Branch**: `codex/ato-per-car-rebuild`

### Summary

Diagnosed the post-deployment NoSuchMethodError as an old SimpleSignals JAR linked to the removed TrainSnapshot API, then deployed the matching SimpleSignals artifact from commit 31e4cf17 with a new recovery point and healthy restart.

### Main Changes

- Replaced only SimpleSignals.jar with the matching TrainFrameSnapshot-linked artifact; preserved the main JAR, TunnelBridgeBuilder and timetable document.
- Recorded the atomic host/add-on ABI deployment rule in the SimpleSignals spec.

### Git Commits

| Hash | Message |
|------|---------|
| `31e4cf17` | fix(simpleato): unify physical frame and authority handoff |

### Testing

- [OK] New SimpleSignals bytecode calls PerceptionSnapshot.getTrain returning TrainFrameSnapshot; installed hashes matched the manifest.
- [OK] Server Done, ATO RUNNING, SimpleSignals revision 117, bridge and WebUI health OK; no startup NoSuchMethodError.

### Status

[OK] **Completed**

### Next Steps

- User reruns the WebUI global simulation; startup health alone does not exercise the complete timetable simulation path.


## Session 39: 列车显示保帧与运营班次标志
<!-- trellis-session: v=2 fp=6424b3d18f64d82b -->

**Date**: 2026-11-12
**Task**: 列车显示保帧与运营班次标志
**Package**: javarailways
**Branch**: `codex/train-display-in-service-refresh`

### Summary

完成 isInService 全链、显示刷新保帧、默认字体及9/6秒语言XML；服务器仅备份替换XML，JAR与旧运行数据激活等待单独许可。

### Main Changes

- PASSENGER/DEADHEAD编译为必填Boolean，保存恢复与实际run投影保持；无currentRun关屏gate不变。
- 语义/样式刷新保留成功帧，新EMPTY及退休仍清理；三份XML在仓库和测试服一致。

### Git Commits

| Hash | Message |
|------|---------|
| `61963c90` | fix(displays): retain frames and honor run service status |

### Testing

- [OK] 前端537、Core599、Basic494、SimpleATO850、WebUI71、Decorations73通过；真实模板和受影响回归通过。
- [OK] Bukkit936项保留1个基线ZIP/behavior.js不一致失败与1个既有skip；已用git show基线核实，不改范围外ZIP。

### Status

[OK] **Completed**

### Next Steps

- 先取得旧new-timetable.js及指定runtime.json的16个运营字段处理、JAR部署和正常重启/资源重载许可，再做客户端验收。


## Session 40: 显示修复数据补齐与测试服部署
<!-- trellis-session: v=2 fp=8ed006190ac945cd -->

**Date**: 2026-11-12
**Task**: 显示修复数据补齐与测试服部署
**Package**: javarailways
**Branch**: `codex/train-display-in-service-refresh`

### Summary

用户批准后正常停服备份，按作者开关为脚本和快照各16段补isInService，严格恢复后部署61963c90产物并正常启动、重载资源。

### Main Changes

- 仅安装JAR、指定脚本与runtime三文件，停服清单其余1913文件不变，完整恢复归档已校验。
- 5列车25车厢均已自动保存到含新XML的包引用，13运营/3回送字段正确；11个其他插件JAR未变。

### Git Commits

| Hash | Message |
|------|---------|
| `192a4d00` | docs(displays): record verified test server deployment |

### Testing

- [OK] 三个一次性迁移测试及独立审查通过，生产OperationalWorldRuntime装配严格恢复6世界、5单位/5认领、16段；旧离线工具漏接provider已用临时生产装配验证替代，未修范围外源码。
- [OK] 新进程、健康端口HTTP200、ATO world RUNNING、命令帮助与资源重载1列车包/3轨道包成功；实际JS/CSS匹配新WebUI sidecar，无新恢复或显示失败。

### Status

[OK] **Completed**

### Next Steps

- 用户在实际光影客户端确认默认字体、9/6秒切换、回送规则和刷新无黑屏；未替代玩家操作或自动重置班次。
