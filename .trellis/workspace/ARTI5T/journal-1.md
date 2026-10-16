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
