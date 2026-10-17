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
