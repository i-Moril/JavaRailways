# Implementation Plan

> Current slice: package display XML loading and source-package snapshots; see [approved contract](research/package-display-loading-contract.md). Independent XML/document JS is complete at SimpleDisplays `410af2e`. Standalone-JS notes below are historical; the old-format consistency child remains deferred until pre-acceptance.

## 1. Baseline and optional API loading

- [x] Record SimpleDisplays API artifact/version consumption for local development and release packaging.
- [x] Add the compile-only API dependency without shading API classes into JavaRailways.
- [x] Add `SimpleDisplays` to generated soft dependencies.
- [x] Implement a small optional-service loader and tests for present, absent, and incompatible provider cases.
- [x] Verify JavaRailways starts without SimpleDisplays.
- [x] Commit and stop for review.

## 2. Package declarations and style assets

2026-11-09 scope refinement: the immediate slice is [screen part definitions/schema/parser](schema/README.md).
The user approved implementing only the reviewed screen-part model/schema/parser slice. Style assets, package defaults,
new package/snapshot formats, rendering and distance config/commands are later slices. Existing model-tree serialization
and copies must preserve the new metadata; that does not authorize a separate persistence redesign.
The checklist below is not one implementation batch.

- [x] Write the schema draft and examples as `type: screen` parts, supporting existing inline/import forms. Remove the rejected car-level screen-list draft.
- [x] User confirmed the part-tree shape and inline `screen` definition (2026-11-09); runtime implementation remains pending.
- [x] Record initial visibility policy: exterior screens follow the car body; interior screens use config/player-command viewing distance without occupancy or other screen-specific culling (2026-11-09).
- [x] Record version decision for this additive slice: new displays.yml starts at 1; default-empty snapshot field preserves existing version/hash. No release/freeze history is assumed; later breaking changes still require the version gate.
- [x] Add screen-part model/parser support first; package style/default models are a separate later slice.
- [x] Integrate the reviewed shape into the official part schema and cover inline/import/nested parsing, invalid fields, copy preservation and screen-free hash stability.
- [x] Compile all PartType consumers; screen nodes currently provide only the ordinary transform/group behavior, without rendering screen content.
- [ ] Add and validate interior/exterior installation metadata, including pinned snapshots and fixtures; cabin occupancy geometry is not required by the initial policy.
- [ ] Parse and validate parent-relative position/rotation, dimensions, category, part references, style IDs, and package XML assets.
- [x] Include package display declarations, XML source and category defaults in source-package snapshots/hashes; point-specific style bindings remain later work.
- [ ] Add fixtures for screen-free packages, multiple points per category, point-specific defaults, and invalid references.
- [ ] Document recommended category names without restricting custom categories.
- [ ] Commit and stop for review.

## 3. Style selection and compiled-template cache

- [ ] Implement the precedence resolver for train category override, point default, and category default.
- [ ] Prepare compiled templates off the render owner path and publish an immutable cache.
- [ ] Define diagnostics for missing optional bindings and invalid declared styles.
- [ ] Test reload/pinned-model behavior and cache invalidation by content identity.
- [ ] Commit and stop for review.

## 4. Backend text visuals

- [ ] Extend `DisplayVisualContent` and `DisplayVisual` for text creation and updates.
- [ ] Implement native Bukkit `TextDisplay` support.
- [ ] Implement equivalent EntityLib packet text-display support.
- [ ] Verify metadata, transforms, brightness/background/alignment, viewer visibility, and cleanup parity.
- [ ] Add focused backend tests without disturbing unrelated existing render changes in the worktree.
- [ ] Commit and stop for review.

## 5. Car-mounted screen groups

- [ ] Specify interior-screen config keys, default/allowed distances, player command syntax, distance reference point and player-setting lifetime using existing view-distance contracts before implementation.
- [ ] Add server-configured interior-screen viewing distance and player command overrides; changing the setting refreshes that player's recipients.
- [ ] Exterior screens follow car-body visibility; enforce interior-screen distance filtering before spawning/sending. Test distance boundaries, players inside/outside cars, moving trains beside stationary players, and recipient removal without occupancy/FOV/occlusion checks.
- [ ] Build screen groups from car installation points and resolved templates.
- [ ] Convert top-left logical draw coordinates into the installation plane and fit the physical dimensions.
- [ ] Attach groups to car body motion, packet bundling, audience handling, rebuild, reverse, and destruction lifecycle.
- [ ] Avoid content metadata writes when neither values nor animation frame changed.
- [ ] Test curves, slopes, reversing, rebuild, unload, and partial construction failure.
- [ ] Commit and stop for review.

## 6. Operational display data

- [ ] Define an immutable Bukkit projection from SimpleATO run/unit state.
- [ ] Expose destination, train/service type, current/next station, and ordered future stops.
- [ ] Extend SimpleDisplays API/XML syntax for repeated future-stop content and paging in its separate repository.
- [ ] Refresh only affected screens as calls complete, stopping patterns change, or short turns occur.
- [ ] Test two runs of the same train package with different stop patterns and pass stations.
- [ ] Commit and stop for review.

## 6.5 配置格式一致性子任务（最终验收前）

- [ ] 完成其余SimpleDisplays集成功能后，执行子任务 [11-09-model-config-schema-consistency](../11-09-model-config-schema-consistency/prd.md)。
- [ ] 内联部件版本归属与命名动画定义格式调整在该子任务独立实现/审查/提交；当前继续功能开发，不提前修改旧格式。
- [ ] 子任务完成并同步所有相关schema、解析器和样例后，再进行父任务最终综合验收；日常开发测试不受此排序限制。

## 7. Overrides, validation, and smoke checks

- [ ] Add server-side train/category style overrides with the agreed non-runtime configuration entry point.
- [ ] Test precedence across package and server sources.
- [ ] Run `./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar` and SimpleDisplays compilation/tests.
- [ ] Run Paper and Folia smoke scenarios with both renderer backends and with SimpleDisplays absent.
- [ ] Add command/JS runtime switching questions to `TODO.md` without implementing the interface.
- [ ] Review diffs, update relevant specs, and commit the final verified slice.

## Commit discipline

Each completed phase ends in one focused commit and a user report before proceeding. Stage only paths changed for this task because the JavaRailways worktree contains unrelated changes.

## Stage 1 evidence (2026-11-09)

See [validation report](research/stage1-validation.md) for API/artifact provenance, seven passing integration tests, packaging, successful Paper startup with/without SimpleDisplays, and the unrelated full-suite expression-test failure. Full-suite success is not claimed. Review this stage before starting stage 2.

## Screen-definition slice evidence (2026-11-09)

Model/schema/parser implementation and independent review are complete. See [validation record](research/screen-definition-validation.md): 29 scoped tests pass, official schemas/examples validate, packaging and developer compilation pass. The full isolated suite has 747 tests with the same expression-test failure recorded in stage 1 (1 failure, 1 skipped). No screen visuals or view-distance controls are implemented. Stop after the focused slice commit for review.

## Independent SimpleDisplays JS slice (user approved 2026-11-09)

This slice precedes template/package wiring and does not implement the entire integration plan.

- [x] Add public JS compile/context/template interfaces without Graal/Bukkit implementation types in the API artifact; retain existing XML compile/draw.
- [x] Implement structured generic data, explicit viewer inputs and elapsed ticks, with a real injected placeholder resolver hook (no PAPI plugin dependency yet).
- [x] Add text/icon draw commands, strict complete-frame validation, invocation isolation, cancellable deadlines, bounded inputs/output and close lifecycle.
- [x] Package runtime dependencies for standalone SimpleDisplays startup; test real GraalJS behavior and existing XML regressions.
- [x] Verify the API-only artifact and actual plugin service loading independently of JavaRailways; record runtime/packaging limits.
- [x] Review, document the library API, commit the independent library slice and stop for review.

Source location: `/home/user/.codex/worktrees/ddf5/SimpleDisplays`, `codex/js-rendering`; never build this slice in either original main checkout.

Completed locally as SimpleDisplays `db9c65e`. See [JS validation record](research/simpledisplays-js-validation.md) for 13 passing tests, API-only consumer compilation, actual standalone Paper service probe, artifact hashes and remaining limits. The broader integration task stays in progress; stop here for review.

## XML/document JS revision (2026-11-09)

The user superseded the standalone JS design and asked to redo from `21f7686` without a revert commit. The authoritative current slice is [XML/document JS contract](research/xml-document-js-contract.md). Earlier standalone-JS implementation/evidence is historical and not the current API. Package template wiring remains deferred.

### Replacement XML/document implementation checklist

- [x] Redo from `21f7686` without a revert commit; remove the standalone JS template/API approach.
- [x] Compile XML with inline scripts and preserve plain XML/scalar binding semantics.
- [x] Add per-viewer structured context and fresh document-style element operations, followed by existing layout.
- [x] Bound/validate DOM mutations and full-frame output; verify isolation, cancellation and resource close.
- [x] Test actual Graal execution, API-only consumer and standalone Paper service loading.
- [x] Review library source/docs, record replacement evidence and commit the focused slice.

Replacement committed as SimpleDisplays `410af2e`, directly parented by `21f7686`; no revert commit. See [replacement validation](research/xml-document-js-validation.md): 15 passing actual Graal tests, independent full review, API-only consumer and successful standalone Paper probe. Stop for review; whole train-integration task remains in progress.

## 当前执行：显示模板包加载与快照

用户已批准按[本批合同](research/package-display-loading-contract.md)实施。

- [x] 正式displays.yml schema、严格配置和包内XML源码加载。
- [x] LoadedTrainPackage/ModelManager来源资源和既有包快照/hash接入。
- [x] 固定无显示基线、显示内容/默认映射、路径/引用、来源隔离和失败重载测试。
- [x] 独立审查、打包/编译检查、文档与局部提交；随后停止供复核。

本批已实现并通过26项定向测试/独立审查；完整Bukkit759项仍有已记录的同一表达式失败，打包/Dev编译通过。见[验证记录](research/package-display-loading-validation.md)。下批候选为模板编译缓存与类别默认选择。

## 2026-11-10 计划修订：Decorations（先调查，未启动实施）

用户将SimpleDisplays适配迁至新的Decorations附属插件，本轮只做display。先明确宿主通用parts注册、来源资源快照和挂载生命周期，详见[边界调查](research/decorations-extension-boundary.md)。原先下一批直接在宿主实现模板编译缓存的安排被取代；缺失扩展策略及注册时序方案确定并审阅后再进入实现。配置一致性子任务仍保持最终验收前执行。

## 当前实施：Decorations基础与既有display迁移

按[当前合同](research/decorations-foundation-contract.md)实现。用户明确缺失插件保留变换/子树、提交前报告，本批不创建提交。

- [x] 宿主通用part类型/配置及插件owner注册与late validation。
- [x] manifest通用资源目录捕获和不依赖addon的来源快照/hash。
- [x] 独立Decorations模块，迁移display配置/可选SimpleDisplays适配。
- [x] composition/schema/文档/样例和完整验证；提交前报告模块与改动。

本批基础迁移已完成，尚未提交。最终相关46+18项测试全过；完整Bukkit758项保留同一ProgramExpressionsTest失败和1skip。打包/Dev编译及三个真实Paper启动组合通过；见[验证记录](research/decorations-foundation-validation.md)。实际挂载渲染仍为后续批次。

## 当前实施：Decorations模板编译

基础已提交95935639；用户允许继续。按[模板编译合同](research/decorations-template-compilation-contract.md)实施。

- [x] API-free桥接、启动顺序和生产provider编译接线。
- [x] 每连接有界精确源码缓存及退出/并发/失败清理。
- [x] 单元测试、独立审查、真实Paper编译/坏候选保留验证。
- [x] 文档与验证记录；实际挂载另批。

本批已提交84b804fc。Decorations28项和宿主28项定向回归全部通过；真实Paper三个组合通过，实际编译验证确认坏XML/JS拒绝与旧snapshot保留。见[验证记录](research/decorations-template-compilation-validation.md)。下一阶段为通用列车part运行时挂载与双后端文字渲染。

## 当前实施：双后端文字

模板编译已提交84b804fc。按[文字后端合同](research/text-backends-contract.md)实施。

- [x] Text descriptor/setText与native/packet两种后端。
- [x] 样式一致、现有变换/观众/清理路径复用与回归测试。
- [x] 独立审查、串行测试/打包/Dev编译、真实Paper后端状态探针。
- [x] 同步规范/验证记录并报告；runtime挂载随后单独做。

本批已提交3aaa3af3。定向73项全过；全量764项仍有既有表达式失败1项和1skip。shadowJar/Dev编译及实际Paper双后端状态/移动/销毁通过。见[验证记录](research/text-backends-validation.md)。

## 当前实施：静态扩展挂载

文字后端已提交3aaa3af3。按[静态挂载合同](research/static-extension-mount-contract.md)实施。

- [x] 公共renderer值接口与宿主worker/准确owner附件生命周期。
- [x] 模型group/root/变换/观众/清理集成及来源快照接线。
- [x] Decorations真实XML静态帧与物理尺寸转换。
- [x] 审查、自动测试/打包与真实Paper双后端探针。
- [x] 规范和验证记录，提交前报告。

本批完成、尚未提交。宿主定向50项、Decorations35项全过；全量宿主776项仅有既有ProgramExpressionsTest失败和1skip，0errors。打包/Dev编译/thinJAR gate与实际Paper双后端XML挂载、来源隔离、移动、退休清理及缺失插件组合通过。见[验证记录](research/static-extension-mount-validation.md)。等待本批提交许可；数据/PAPI/动态刷新/车内距离策略后续分批。
