# Basic 保护区域准入（当前迁移实现）

## 1. 状态、范围与 Owner

- **Status**：`TRANSITIONAL`。本页只记录当前仍在 Basic 中运行的保护区域、单窗口准入和回归语义。
- **Current owner**：Basic 当前保存公开 definition/provider contract、区域 catalog、窗口解析适配器和
  `ProtectedRouteClaimService`；这是迁移起点，不是长期模块边界。
- **Target owner**：`JapaneseSignals` 拥有保护区域定义、图切割、窗口策略、authoring 和持久化。Basic 最终只保留
  [通用路线资源准入](./route-resource-admission.md)所定义的物理资源底线、原子 claim、准备、grant 和释放。
- **Non-goals**：本页不再保存固定闭塞、`AuthoritySection`、递归 contributor closure、provider route callback 或
  其他未实现历史方案。

触发：修改或迁移 `ProtectionRegionDefinition`、`ProtectionRegionCatalog`、
`ProtectionRegionWindowResolver`、`ProtectedRouteRequestValidator`、`ProtectedRouteClaimService` 及其测试。

## 2. 当前模块如何工作

```text
ProtectionRegionProvider publishes definitions for topology revision T
  -> catalog validates every definition and atomically publishes one retained snapshot
  -> graph resolver cuts rails at authored boundaries and derives one interior component
  -> caller submits one topology-bound frozen route plus zero or one ProtectedRouteWindow
  -> window resolver validates entry/interior/exit against the retained topology root
  -> transitional validator checks every controlled resource is inside that one window
  -> PhysicalRouteResourceCompiler derives the route's physical resource floor
  -> authoritative same-epoch occupancy is checked before one atomic claim
  -> claimed turnout preparations complete before acquire becomes READY
  -> retained route cursor and release queue shrink the claim behind the authoritative train tail
  -> an extension may append only a farther suffix of the exact same frozen route
```

The null-window/`NOT_REQUIRED` branch and `PROTECTION_REQUIRED` are current compatibility behavior only. They must disappear
when production callers use the generic intent and protection policy moves to `JapaneseSignals`; they are not copied into the
new Basic API.

## 3. 内部机制与关键算法

### 3.1 Directional boundary graph cut

`ProtectionBoundaryDefinition.position` uses canonical rail AB metres. Its facing points from the outside half of the cut
into the proposed interior; it is not an operating-direction rule. Boundaries must remain strictly inside a rail because a
rail endpoint may touch multiple branches and cannot identify one unique half-edge.

For one catalog rebuild, `ProtectionRegionTopology` retains one exact immutable `TopologyState` root. The graph resolver:

1. groups and sorts boundaries by rail;
2. splits affected rails into temporary `SegmentKey(railId, index)` slices without modifying Core topology;
3. chooses the inside/outside slice at each cut from the boundary facing;
4. performs BFS from the first inside slice, following endpoint connectivity but never crossing a cut;
5. requires every inside slice to belong to that component and every outside slice to remain outside it;
6. converts the visited segments back to canonical AB interior slices.

Disconnected interiors return `DISCONNECTED_INTERIOR`; an outside half reconnecting around another path returns
`OUTSIDE_RECONNECTS`. Different regions may touch at a boundary but positive-length interior overlap is rejected. Catalog
publication is all-or-nothing: any provider, topology, definition or overlap failure preserves the previous snapshot.
Rebuild cost is `O(V + E)` for the captured component plus boundary sorting and overlap checks; it runs on registration,
explicit refresh or revision change, never per train tick.

### 3.2 Frozen-route projection and physical compilation

`TopologyRouteProjector` validates the caller-selected `RouteSnapshot` linearly against the same retained topology root. It
converts each facing-local segment into canonical rail/edge coordinates, proves continuity and legal structural-node
continuations, and derives route-ordered turnout traversals. Stable turnout identity is computed from the sorted persistent
endpoint set; revision-local topology node IDs remain validation-only.

`ProtectionRegionWindowResolver` reuses that projection. It proves that entry crosses inward, exit crosses outward, and every
route occurrence between them lies in the resolved interior. It does not search for another route.

After the transitional window-coverage check succeeds, `ProtectedRouteRequestValidator` delegates physical work to
`PhysicalRouteResourceCompiler`. Given the projected route and explicit route range, that compiler derives without any region
dependency:

- one canonical `RailIntervalResource` for every traversed rail slice, merging same-rail adjacent/overlapping intervals;
- every traversed stable turnout resource and its route-ordered selection;
- every route-bound shared resource hit, including diamond conflicts;
- one normalized `OccupancyQueryGeometry`;
- one release entry per exact claimed resource and one `CompiledRouteProgressPath`.

These values belong to one `CompiledRouteAdmission`. Only its footprint enters the registry; preparations, occupancy geometry,
release entries and progress path are metadata, not alternative footprints.

### 3.3 Route-bound resource catalog

`RouteBoundResourceCatalog` publishes a complete immutable snapshot for one exact `TopologyState` object. Complete-empty is
different from unavailable. Each binding maps one stable resource to positive-length canonical rail anchors. A diamond has two
distinct anchors but one shared resource identity, so either line detects the same conflict without creating cross-line
connectivity.

Anchors are indexed per rail by canonical start and a prefix maximum end. A route occurrence query first performs a binary
search over the prefix maximum, then scans only candidates whose start may overlap the occurrence. Per occurrence cost is
`O(log a + c)` for `a` anchors on that rail and `c` remaining candidates. Leaving and later re-entering the same controlled
resource is rejected rather than silently deduplicated. Query completion rechecks both catalog publication and topology root;
missing or stale state fails closed.

### 3.4 Claim, preparation and release lifecycle

The claim service resolves the exact `RailwayObservation`, train and stamp used by validation. Occupancy queries reuse Core's
same-epoch swept-occupancy index, exclude the requesting train and return deterministic blocker train IDs. No registry mutation
occurs before route, window, resource and occupancy validation finish.

After one atomic claim, `InfrastructurePreparationActuator` receives the complete ordered preparation batch. Current live
turnout preparation resolves and validates every direction before the first in-memory selection write. `READY` completes
acquire; `PREPARING` retains the reservation without widening authority; `ABORTED_SAFE` may remove only the new request/delta;
`UNSAFE`, handler failure or an unprovable abort retains the safest reservation and stalls the world. There is no fictional
turnout-selection rollback or default-state restoration by the train.

Each successful admission retains one monotonic `CompiledRouteProgressCursor` and one ordered release queue. A coherent later
observation projects the authoritative train tail forward on the same route. `LiveClaimTailRelease` inspects only the contiguous
eligible queue prefix, atomically shrinks those exact identities in the registry, and only then removes the same local prefix.
Wrong stamp/train, backward or unmatched progress, and unexpected registry results latch fail closed.

The service retains the live-grant record after the current queue becomes empty so the same cursor can accept a later suffix.
An explicit authenticated end removes that record. Same-route extension requires the exact route value, train, topology and
owner, a strictly farther frontier and at most one pending extension. It compiles/query/reserves/prepares only the suffix delta;
old resources continue tail release while pending. Only a finalized READY delta appends committed release entries and advances
the grant frontier. A changed route is a replan at a safe operational boundary, not whole-claim replacement.

## 4. 当前接口与数据边界

The current public transition surface is defined by source values in `basic.api.route`:

- `ProtectionBoundaryDefinition`: namespaced boundary ID plus canonical AB position/facing;
- `ProtectionRegionDefinition`: region identity/revision, topology revision and immutable boundary list;
- `ProtectionRegionProvider`: complete definition snapshot for one requested topology revision;
- `ProtectedRouteWindow`: one caller-selected entry, exit and exit-clearance boundary on one frozen route;
- `ProtectedRouteRequest`: owner, stable train ID, exact observation stamp, `RouteSnapshot` and optional window;
- `ProtectedRouteClaimResult`: current typed transition result, including `NOT_REQUIRED` and `PROTECTION_REQUIRED`.

The request does not carry train length, live train/world objects, occurrence index, progress coordinate or a replacement
footprint. Basic resolves physical state from the exact observation and derives its own resources. Provider registration and
definitions are runtime values; this page creates no persistence schema or compatibility promise.

The target `RouteResourceIntent`, additive plugin resources and public grant service are defined only in
[route-resource-admission.md](./route-resource-admission.md). Do not extend the protected-route types into that target API.

## 5. 当前不变量、线程与版本

- Definition, region catalog, projected route and route-bound catalog must retain the same topology root, not merely equal
  revision numbers.
- Provider callbacks run only during catalog registration/refresh, must return a coherent immutable snapshot promptly and are
  never called per train tick.
- Catalog update and publication are atomic; rejection preserves the previous complete snapshot.
- A protected route is one caller-selected frozen traversal through one caller-selected window. The validator never chooses an
  alternative route or discovers successor regions.
- Region identity itself is not claimed. The footprint contains actual rail intervals and shared physical conflict resources,
  allowing disjoint physical paths through one geometric region when their resources do not overlap.
- Claim/preparation/release execute on the world owner; physics crosses threads only as immutable observations.
- Explicit provider unregister removes its definitions from new window resolution, but it cannot justify dropping a host-owned
  live claim beneath a train. Automatic platform-disable wiring and reload recovery remain unfinished and must fail closed until
  the target lifecycle is implemented.
- Runtime claims, cursors and pending handles are not approved persistence schemas.

## 6. 当前校验与错误语义

| Condition | Current result | Mutation |
| --- | --- | --- |
| provider/definition/graph validation fails | catalog `REJECTED` with diagnostics | previous catalog retained |
| route, catalog or topology root is stale | `STALE_TOPOLOGY` | none |
| observation is not the current exact published stamp | `STALE_OBSERVATION` | none |
| route is disconnected, directionally invalid or repeats a controlled resource | `INVALID_REQUEST` | none |
| null window and no turnout/route-bound hit | `NOT_REQUIRED` | none |
| null window with a controlled resource | `PROTECTION_REQUIRED` | none |
| controlled turnout/resource lies outside entry-to-exit window | `PROTECTION_REQUIRED` | none |
| another train overlaps compiled occupancy geometry | `PHYSICALLY_OCCUPIED` | none |
| footprint identity/provider is invalid | `INVALID_RESOURCE` / `RESOURCE_PROVIDER_FAILED` | none |
| another live claim overlaps the footprint | `RESOURCE_CONFLICT` | none |
| claim succeeds but preparation remains pending | pending handle | reservation retained; no wider authority |
| preparation reaches `READY` | `ACQUIRED` | claim committed; live release installed |
| preparation proves `ABORTED_SAFE` | `PREPARATION_FAILED` | only new request/delta removed |
| preparation is unsafe/unprovable or lifecycle invariant fails | `WORLD_STALLED` | safest reservation retained |

`NOT_REQUIRED` and `PROTECTION_REQUIRED` are deliberately listed only because production migration has not removed them yet.
The target generic service always derives requested rail intervals and has no Basic protection-policy failure.

## 7. 性能与退化行为

- Region graph cut is rebuild-time `O(V + E)`, not a tick operation.
- Frozen-route projection and physical compilation are linear in traversed route occurrences, plus per-rail interval sorting/
  merging where needed.
- Route-bound lookup is indexed `O(log a + c)` per occurrence rather than a world-wide binding scan.
- Occupancy reuses Core's once-per-epoch rail/bucket index; Basic does not rebuild a train index or scan all trains per region.
- Release uses the retained route cursor and queue; it does not reproject the complete route or query world occupancy per
  resource.
- Missing indexes, stale roots, provider failures and invariant mismatches fail closed; there is no fallback live-world scan.

## 8. Good / Base / Bad cases

- **Good**：one region encloses a complete multi-turnout throat; the frozen path produces all rail/turnout/diamond resources,
  all turnout preparations are validated before the first write, and release follows the train tail.
- **Good**：two paths through the same region share no physical resource and therefore do not conflict merely because their
  region ID matches.
- **Base**：the current null-window straight route returns `NOT_REQUIRED`; this is migration behavior, not the target API.
- **Bad**：a caller supplies a handcrafted footprint or Basic searches another route after validation fails.
- **Bad**：a stale catalog is treated as complete-empty, or provider disable releases a live claim beneath a train.
- **Bad**：new signal/fixed-block policy is added to these Basic transitional classes instead of `JapaneseSignals`.

## 9. 必需回归测试

- `ProtectionRegionGraphResolverTest` / catalog tests: direction-aware cuts, disconnected interior, outside reconnect, duplicate
  boundaries, overlap, stale topology and atomic publication.
- `TopologyRouteProjectorTest` / `ProtectionRegionWindowResolverTest`: AB/BA coordinates, zero-length endpoint marker,
  continuity, turnout/loop continuation, entry/interior/exit validation.
- `ProtectedRouteRequestValidatorTest`: null-window current behavior, uncovered resources, multi-rail clipping, zero/one/multiple
  turnouts, route-bound resources, occupancy geometry, release entries and extension suffix.
- `PhysicalRouteResourceCompilerTest`: complete physical floor without any protection region.
- `ProtectedRouteClaimServiceTest`: exact observation, blockers, atomic claim, pending/ready/safe-abort/unsafe preparation,
  release cursor, prefix shrink, same-route extension and explicit end.
- `LiveInfrastructurePreparationActuatorTest` and `LiveClaimTailReleaseTest`: complete prevalidation before write and
  shrink-before-pop fail-closed behavior.

Validation for a behavior-changing migration remains:

```bash
./gradlew :Core:test :Basic:test
git diff --check
```

## 10. 迁移与删除条件

Migration must preserve behavior through the generic kernel rather than preserve these Basic policy types:

1. expose and test the generic route intent, additions, preparation and grant services;
2. migrate production automatic/`MANUAL_ROUTED` callers away from `ProtectedRouteRequest`;
3. move definitions, graph cut, catalog/window policy, authoring and persistence ownership to `JapaneseSignals`;
4. run parity/lifecycle tests using the same simple- and multi-turnout fixtures;
5. delete Basic protection-region API/internal classes, `PROTECTION_REQUIRED` and the transitional null-window shortcut.

No runtime compatibility layer is implied. If development world data needs conversion, it requires a separately reviewed,
auditable one-time migration with dry-run and recovery point under the repository persistence rules.

```text
Wrong: keep obsolete Basic signal-policy abstractions as historical alternatives
Correct: retain only current migration evidence here; keep the target contract in route-resource-admission.md

Wrong: move the old single-window public API unchanged into JapaneseSignals
Correct: reuse its graph-cut tests and region semantics, then integrate through Basic's generic intent/addition API
```
