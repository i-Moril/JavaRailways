# Implementation Plan

> Current: [interior distance](research/interior-distance-contract.md), implemented with compilation only; user defers integrated testing until the remaining work is complete. Operational data changes remain uncommitted. Culling is assessment only; fixed-unit style overrides are implemented with compilation only.

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

本批已提交a4604a43。宿主定向50项、Decorations35项全过；全量宿主776项仅有既有ProgramExpressionsTest失败和1skip，0errors。打包/Dev编译/thinJAR gate与实际Paper双后端XML挂载、来源隔离、移动、退休清理及缺失插件组合通过。见[验证记录](research/static-extension-mount-validation.md)。等待本批提交许可；数据/PAPI/动态刷新/车内距离策略后续分批。

## 当前实施：动态帧与基础列车输入

上一批静态挂载已提交a4604a43；PAPI适配移出本任务。按[动态合同](research/dynamic-train-input-contract.md)实施。

- [x] 宿主owned session/immutable input/串行合并刷新和关闭完成。
- [x] 同一physics frame的基础列车/车厢投影及stream身份接线。
- [x] Decorations真实结构化DisplayContext、复用私有模板及重复draw。
- [x] 动态文字复用/数量变化/EMPTY/清理，自动验证及实际Paper双后端。
- [x] 同步文档、记录证据，提交前报告。

本批已提交09da5fbc。宿主定向43项、Decorations39项通过；宿主全量787项仅有既有ProgramExpressionsTest失败、1skip、0errors。打包/Devcompile/thinJAR gate通过；真实Paper双后端XML/document JS的结构化数据、elapsed ticks、实体复用、grow/EMPTY/recover及清理通过，缺失插件组合通过。见[动态验证](research/dynamic-train-input-validation.md)。运营站点/目的地/种别、逐观看者画面、车内距离设置后续分批；PAPI适配不属于本任务。

## 当前实施：实际运营显示输入

动态刷新已提交09da5fbc；按[运营输入合同](research/operational-display-input-contract.md)实施。

- [x] SimpleATO fresh owner batch/unit identity/revision/current berth proof。
- [x] Bukkit immutable authored values/remaining stop occurrences/aliases projection。
- [x] post-dispatcher sampling and stale-context invalidation/clear/cadence。
- [x] 回归、打包与实际Paper双后端，规范/验证记录及提交前报告。

本批实现与验证完成，尚未提交。SimpleATO824、Core546、Basic469通过；宿主全量799仅有既有ProgramExpressionsTest失败、1skip、0errors。宿主定向39与Decorations39、打包/Devcompile/Javaconsumer/thinJAR gate及实际Paper双后端运营投影/JS/失效清屏/恢复通过。详见[运营输入验证](research/operational-display-input-validation.md)。下一批候选为车内观看距离/config/player命令；逐观看者画面和覆盖式样仍另分批，PAPI不属于本任务。

## 当前实施：车内屏幕视距

用户确认按玩家距屏幕计算，整体验证留到全部完成之后；详见[视距合同](research/interior-distance-contract.md)及[Culling评估](research/interior-display-culling-assessment.md)。

- [x] 宿主通用 immutable audience policy、逐屏幕最终坐标与玩家眼睛距离、候选/可见观众分离。
- [x] root-only移动、父级/动画变换、已有观众合并刷新、命令即时刷新、late replay复查。
- [x] 无可见观众暂停draw、warm-open不默认draw、保留最新输入与source clock、返回补帧。
- [x] Decorations默认32/最大128格严格config、0关闭、查询/设置/reset命令、权限、quit/disable清理。
- [x] 准备host/addon回归案例；源码和测试源码编译检查（不执行测试）。
- [x] culling可行性/复杂度与限制记录；没有启用背面/视锥/遮挡算法。
- [ ] 最终统一执行测试套件、真实Paper双后端、Folia和客户端验收（用户要求延后）。

本批未提交、未部署。视距改变了旧Paper无观众探针的前提；后续统一验证需要真实/显式观众，不能复用此前无观众仍draw的旧通过记录作为当前验收证据。下一批为样式覆盖，先确认覆盖对象和生命周期。

## 当前实施：样式选择流程

用户确认先不加额外剔除策略，完成流程后统一验证。按[样式合同](research/style-selection-contract.md)先接独立的屏幕默认模板 > 包类别默认模板与严格来源引用校验。运行期覆盖的对象/生命周期已提出文字澄清；它决定解编重联继承与是否持久化，相关实现等待答复，不把提案写成已确认决策。

- [x] 屏幕可选template默认、精确来源引用校验及schema/案例；源码/测试源码编译通过，尚未运行测试。
- [ ] 按用户选择补运行期覆盖对象、生命周期和替换会话合同，再实现覆盖入口。
- [ ] 最后统一执行测试与服务端/客户端验收。

静态模板默认编译记录：/tmp/jr-style-default-compile.log，BUILD SUCCESSFUL（3s），未运行测试/服务器、未提交。用户要求比较运行期覆盖方案，尚未选择；当前只实现不依赖该选择的静态默认。额外culling已明确不加入。


## 当前实施：固定单元样式覆盖（2026-11-10，方案2）

用户选择 fixed unit + category，runtime-only；详见[确认合同](research/style-selection-contract.md#confirmed-fixed-unit-runtime-slice-user-selected-option2)。本节取代上方等待 scope 答复的历史状态。

- [x] 世界/actual unit ID/类别 store、全部 captured catalog 原子校验、交集查询、重建/解编/重联保留。
- [x] 公开 DisplayStyleService、玩家查询/设置/reset 命令、OP权限、entity scheduler、world unload/disable清理与epoch。
- [x] host 通用 metadata update/render revision/注册刷新；隐藏只更新绑定；切换清旧输出拒绝旧帧、按需替换private模板、保留source clock。
- [x] 准备 store/routing/command/host deferred 回归；host及Decorations源码/测试源码编译通过。
- [ ] 统一执行测试、Paper双后端、Folia、真实客户端与解编重联/重建/卸载验证。

编译日志 /tmp/jr-unit-style-compile.log（BUILD SUCCESSFUL，5s）；未执行测试/服务器，未提交/推送/部署。最后提交仍09da5fbc。当前树包含此前运营投影、视距、静态默认及本批覆盖，不能将旧批测试通过当成当前验收。逐观看者挂载内容、schema一致性子任务及总验收仍未完成。


## 后移逐观看者内容（用户确认，2026-11-10）

逐观看者挂载画面与 PlaceholderAPI 一起后移，不作为本次验收前置。当前只验收共享画面及每玩家独立视距；独立 SimpleDisplays 库已有 viewer 输入能力保持不变。下一阶段为已登记的 schema 一致性子任务，再统一验证。之前“逐观看者挂载内容仍需验收前完成”的状态由本节取代。


## 车辆信息显示切换时机后移（2026-11-10）

当前阶段仅跟随 actual currentRun，在实际运行线期间保持显示。提前切换到下一运行线、独立displayRun或切换动作均不实现；后续讨论项登记在PRD TODO。站点输入改为完整有序站点序列+精确索引+到站/在途状态，替代本轮最初的completedStops额外列表提案；该新接口仍待实现，不宣称已完成。


## 当前实施：完整站点序列与进度接口

用户允许按[站点接口合同](research/stop-sequence-contract.md)修改。最小owner为SimpleATO的call/到站进度，宿主仅做immutable JSON投影；保持实际currentRun，无提前切换显示。

- [x] 完整有序stops、exact occurrence索引和到站/在途/未知/结束状态。
- [x] 宿主投影、模板接口文档、deferred回归与语义失效接线。
- [x] 串行源码及测试源码编译、静态检查；不执行统一测试或部署。

本批已实现、静态复查与源码/测试源码编译通过；详见[证据](research/stop-sequence-validation.md)。测试执行与Paper/Folia/client仍待统一验收，未提交或部署。


## 本侧/对侧开门接口

用户确认复用实际开门的折返与逐车安装方向语义，按[本批合同](research/door-side-display-contract.md)实现。原始开门侧由SimpleATO拥有，逐车操纵端换算由宿主复用已有helper，Decorations只比较固定安装侧。继续仅编译/静态检查，测试及服务器验收后置。

本侧/对侧开门数据接入已完成：SimpleATO有效站停门侧、host逐车操纵端、Decorations固定安装侧比较；源码/测试源码编译与独立静态复查通过。详见[证据](research/door-side-display-validation.md)。未执行测试、未提交或部署；实际模板和客户端验收随后。


## 实际S100模板资源（2026-11-10）

- 已提交此前集成批次：cad8d2cd。
- 新增真实XML/document-JS：车包内部前显8.5:1、候选2:1、现有侧显候选8:3；独立4:1门区 `cabin-route.route`。固定300tick语言切换，使用真实运营metadata/有序站序/安装侧输入。
- Decorations启用时捕获独立样式包，合并本地catalog，严格拒绝重复和坏引用；绘制顺序有界深度偏移；补回归源码，仅编译不运行。
- 已挂载车头2面与门区30面；body_head/door_display静态LCD内容改为黑色实体背板，保留原树与镜像变换。
- 发现此前位置解释错误：2:1内嵌面实际朝车内，外侧被壳遮挡；已请求确认车体拼接处是否应使用已有8:3 side_display。答复前不新增外部2:1几何，也不擅自激活侧显。详见research/s100-screen-mount-inventory.md。
- `scripts/display-assets/package.py` 生成s100.zip、cabin-route-style.zip和resourcepack-s100-displays-1.21.11-26.2.zip；资源包保留既有透明度/版本overlay，仅补显示素材。
- 当前批次未提交、未部署、未运行测试或SDL画面；编译和静态检查记录后续更新。统一测试与Paper/Folia/client验收仍后置。

本批检查记录：`/tmp/jr-display-resources-compile.log`（BUILD SUCCESSFUL，生产/测试源码编译），`/tmp/jr-display-resources-schema.json`（schema转换dry-run零变更）。XML/JS词汇和语法静态检查通过；车包/独立样式ZIP逐文件等同源码，客户端包保留原pack.mcmeta与overlay，新增10个显示素材、仅修改3个既有显示模型。独立静态复查通过，未运行测试或SDL脚本画面，未部署。车体位置问题仍待用户答复。


## 移除误识别的2:1模板（用户确认）

2:1模型面是门区4:1屏幕外壳的一半，不是额外屏幕。已删除body-destination.xml及其catalog/default引用，修正清单与模板合同，重建车包与样式/客户端归档。车头种别徽章仍使用E002矩形图元，因此保留该通用图元。已有8:3侧显模板保留，挂载仍待下一步；不新增任何屏幕几何。本项覆盖上文等待2:1位置确认的历史状态。未提交或部署，统一测试继续后置。
