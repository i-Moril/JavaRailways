# Train-mounted displays through Decorations

> 2026-11-11 current slice: requirements 2/3 implemented in source. Every declared RUN has a separate default operational trip; explicit cross-run/per-unit definitions retain priority. Ordinary-train display fallback removed. Decorations returns EMPTY outside actual `currentRun`, including static template output. SimpleATO848, host-related127, Decorations71 and SimpleATOBukkit2 tests passed; independent review and Decorations JAR boundary check passed. User authorized committing and deploying this batch2026-11-11; game-client acceptance remains pending. The prior UUID implementation is unchanged and included in this authorized submission.

> Current: [ordered stops and exact display progress](research/stop-sequence-contract.md), approved after cabin/exterior layout discussion. Prior operational inputs, interior distance, style overrides, metadata and schema-consistency changes remain uncommitted. Integrated verification is deferred until all work is complete; no extra culling or PAPI.

> Completed milestones: Decorations foundation `95935639`, template compilation `84b804fc`, native/packet text backends `3aaa3af3`, static mounting `a4604a43`, dynamic sessions/basic train input `09da5fbc`. Independent SimpleDisplays XML/document JS is at `410af2e`. Current module boundaries and contracts supersede historical built-in screen/loading notes below; schema consistency remains a separate child before acceptance.

## Confirmed module boundary (2026-11-10)

JavaRailways supplies generic namespaced part definitions, immutable extension package resources and provider registration. The independent `Decorations` plugin owns `decorations:display`, display resource semantics and the optional SimpleDisplays connection. SimpleDisplays remains a separate reusable XML/document-JS library/plugin.

Without Decorations, a train must load normally. Unknown namespaced nodes retain their configuration, transforms, animations and complete child subtree. A diagnostic identifies the unavailable extension. Unknown unnamespaced part types remain errors. No reflective loading from saved type IDs is permitted.

The approved current foundation slice is [the Decorations contract](research/decorations-foundation-contract.md). It migrates existing definitions/resource loading/service discovery without implementing visuals. Main-package and exact per-car source snapshots capture raw extension data without a provider. Late provider registration validates already loaded packages; reload publication must reject stale provider generations. Provider disable retires its registration without removing model trees.

## Current foundation acceptance

- [x] Host has no SimpleDisplays API dependency, adapter or descriptor dependency.
- [x] Decorations registers display semantics through public host API, with lifecycle cleanup and a separate thin plugin JAR.
- [x] Missing add-on preserves extension nodes, transforms, animations, imports and children.
- [x] Declared extension directories capture immutable UTF-8 resources with safe paths and deterministic snapshots, including per-car source packages.
- [x] Invalid installed-provider config fails preparation; late registration reports per-package failures; stale candidates cannot publish across registration changes.
- [x] Existing built-in/no-extension snapshot hashes remain fixed; current schema versions remain unchanged.
- [x] Schemas, examples, targeted tests, packaging and available runtime smoke evidence agree.

Validation: [foundation record](research/decorations-foundation-validation.md). Complete host suite retains one previously recorded expression failure; do not claim whole-suite green. No commit yet.

## Milestones and remaining integration

Completed: independent SimpleDisplays XML/document-JS410af2e; generic extension definitions/resources and Decorations95935639; compile validation/cache84b804fc; native/packet text3aaa3af3; static train-part mounta4604a43. Current uncommitted slice adds owned render sessions,10-tick dynamic refresh and basic same-frame train/car values; see [contract](research/dynamic-train-input-contract.md).

Remaining after this slice:

- Project actual operational unit metadata, destination/run-type author values, current/next station and ordered stop occurrences through direct library inputs. Reuse TimetableUnitDisplaySnapshot, preserve unresolved branches and real fixed-unit membership; do not guess from line station order. When no destination is explicitly set, default to the terminal station of the actual current run, preserving available station-name translations; never use a later run’s terminal.
- Deferred by user2026-11-10: per-viewer mounted output on both backends, alongside PlaceholderAPI. Current acceptance covers shared mounted content; independent SimpleDisplays viewer input remains available.
- Preserve confirmed visibility policy: exterior screens follow car-body audience; interior screens use configurable per-player distance with config and player command. Occupancy/facing/frustum/occlusion remain outside initial policy.
- Extend category defaults with point defaults/train overrides as reviewed later. Runtime style switching addressing/commands/persistence remain deferred until target/composition semantics are agreed.
- Run deferred11-09-model-config-schema-consistency after functional work and before acceptance.
- Final live train movement/curve/slope/reversing/audience/rebuild/unload verification on real Paper/Folia and game clients. Server-side probes and static tests do not prove client rendering.

Bukkit PlaceholderAPI adaptation is explicitly excluded from this task by user decision2026-11-10. It is not the train-data transport.

## Workspace and provenance

Use only the dedicated `ddf5/JavaRailways` worktree on `codex/simpledisplays-train-integration`; the main checkout contains unrelated work. Independent SimpleDisplays XML/document JS is at `410af2e`, reimplemented from `21f7686` without a revert commit. Earlier built-in screen and root displays.yml formats were unshipped development shapes and are replaced directly, with no legacy runtime reader or speculative version increase.

Historical discussion below records prior decisions; this confirmed module boundary and foundation contract supersede obsolete ownership/format claims.

## Confirmed visibility decision (2026-11-09)

- The user selected the distance-based initial policy above. The user also approved inline/imported screen parts with required `category`, `interior`, `width` and `height` under the extension-owned `config` (original built-in `screen` shape superseded by Decorations). The user subsequently required player-to-screen distance (2026-11-10); current implemented choices and defaults are in research/interior-distance-contract.md, with integrated verification deferred. The player command is for viewing distance, distinct from deferred category-style switching.
- Continue work in `/home/user/.codex/worktrees/ddf5/JavaRailways` on `codex/simpledisplays-train-integration`, starting from `ce2bcd44`. The main checkout has other work and must not be used for implementation or builds of this task.

## Template discussion (2026-11-09)

- The user proposes reusing SimpleDisplays XML directly and adding placeholder API adaptation rather than designing another display-template language.
- Existing SimpleDisplays supports `<span bind="key">fallback</span>` and `DisplayTemplate.draw(Map<String, String>, elapsedTicks)`; the host can supply scalar values without rewriting XML or duplicating the compiler.
- Confirmed: “placeholder API” means the external Bukkit PlaceholderAPI plugin. The user also asks about JS-generated dynamic elements inside SimpleDisplays. JS input/output shape, template storage and refresh policy remain under discussion; the user subsequently authorized the scoped SimpleDisplays JS implementation below.

- Confirmed: as a reusable library, SimpleDisplays must allow the same display instance/template to produce different content for different viewers. Viewer-specific content is a supported capability, not restricted to a JavaRailways-only special case. Identical train information may still be reused, but callers must not be forced to share one frame among all viewers.

## Authorized SimpleDisplays JS slice (2026-11-09)

- Implement only the independent library's JS compile/render API: generic structured display data, explicit per-viewer input, elapsed ticks and renderer-neutral text/icon output. Preserve the existing XML API.
- An injected placeholder resolver may be called from JS; the actual Bukkit PlaceholderAPI adapter is later work. JavaRailways sends data through the direct library API, not through PAPI.
- Execute one bounded render invocation without cross-viewer mutable script-state leakage. Validate full output before returning a frame and clean up script resources on template/service/plugin close.
- Do not implement JavaRailways script resource loading, train data capture, native/entitylib mounted text output, visibility settings or commands in this slice.
- Independent source worktree: `/home/user/.codex/worktrees/ddf5/SimpleDisplays`, branch `codex/js-rendering`, base `21f7686`. Keep the original SimpleDisplays and JavaRailways main checkouts unchanged.

- Completed independent library slice as SimpleDisplays `db9c65e`; API documentation lives in that repository README. See [validation](research/simpledisplays-js-validation.md). Actual PAPI adaptation and JavaRailways use of JS remain pending.

## XML/document JS revision (2026-11-09)

The user superseded the standalone JS design and asked to redo from `21f7686` without a revert commit. The authoritative current slice is [XML/document JS contract](research/xml-document-js-contract.md). Earlier standalone-JS implementation/evidence is historical and not the current API. Package template wiring remains deferred.

Replacement library slice completed as `410af2e` with XML embedded scripts/document API. See [replacement validation](research/xml-document-js-validation.md); package template loading and train rendering remain pending.


## Scope update — 2026-11-10

User confirmed per-viewer mounted content can be done later, alongside PlaceholderAPI; it is not a blocker for this task's current acceptance. Existing mounted screens share content while retaining per-player viewing distance. Operational input, viewing distance, static template defaults and fixed-unit/category runtime styles are implemented; latest slices have compilation evidence only. Next: the registered schema consistency child, then unified tests and live backend/Folia/client acceptance. No extra culling. No new commit/push/deploy authorized by this scope update.


## 显示运行线范围与后续 TODO（2026-11-10）

用户确认当前阶段在实际运行线期间保持相应显示，沿用 actual currentRun 的真实执行范围；不新增 displayRun、提前切换或根据下一站推测运行线的策略。段外继续保留当前未知/null语义，不自动沿用上一段或提前绑定下一段。

- [ ] TODO（后续讨论，不属于当前实现）：车辆信息显示的切换时机。覆盖跨线站、终点折返、站内等待及下一班始发时线路名称、种别、目的地等信息何时切换；届时再确定是否需要独立展示上下文或显式切换动作。

站点输入方向按用户建议调整为当前运营车次的完整有序站点序列、精确 occurrence 索引和到站/在途状态；替代此前新增 completedStops 列表的提案。此接口的具体 owner/未知状态/到站与驶离语义需按后续实现合同落实，不能把 completed 等同于已经驶过。

## 车外版式与共享语言轮播（2026-11-10）

- 车外屏幕采用【种别（独立背景色）】目的地，不包含车内线路图、下一站和开门提示。种别、目的地支持中英文，种别背景色为模板消费的运行线metadata展示约定，不参与运营决策。
- 用户明确车内/车外均按固定时间自动切换语言，不按玩家语言或viewerData选择。相同屏幕对所有观看者共享同一语言页；此要求不依赖后移的逐观看者内容或PlaceholderAPI。
- 用户指定：每页15秒，中英文交替。使用宿主已有elapsedTicks source clock（20ticks/s，即每300ticks切换），模板内可调整间隔。暂停/无观众/恢复时跟随既有source-clock语义，不新建每屏定时器，不在draw之间保存JS全局状态。若用户之后要求真实墙钟秒，则另明确时钟契约。
- 车内两语言共用固定画布、相同线路图几何；车外轮播也不得改变物理屏幕尺寸。

- 车外版式细化：目的地在种别以外的右侧区域居中；未配置种别时移除整个左侧色块和占位，目的地整屏居中。优先使用较大字体，长目的地按可用区域处理；语言/种别有无不改变屏幕物理尺寸，游戏内可读性仍待客户端验收。
