# Basic 路线资源准入契约

## 1. 状态、范围、触发条件与 Owner

- **Status**：`TARGET`。当前 `ProtectedRouteClaimService`、保护区域 catalog/window 与部分编译器仍是迁移期实现；它们不能继续冻结为 Basic 的长期领域模型。
- **Implementation progress**：内部 `PhysicalRouteResourceCompiler` 已能从投影后的冻结路线和显式路线范围推导完整物理底线及准备、占用检查、释放元数据；当前 `ProtectedRouteRequestValidator` 仍作为保护窗口策略适配器调用它。公开通用 intent、插件附加资源和生产 caller 迁移尚未完成。
- **Owner**：Basic 拥有通用资源注册、冻结路线验证、不可省略的物理资源推导、原子 claim、设备准备、grant 生命周期与车尾释放。
- **Caller**：SimpleATO 提交冻结路线与列车/观察身份；具体信号系统插件可以在同一请求中附加自己注册的资源要求。
- **Non-goals**：Basic 不定义保护区域、固定闭塞、信号显示、联锁表、侧面防护规则、最远瞭望策略或国家信号制式。

触发：修改自动进路申请、route-bound resource、turnout/diamond claim、外部资源注册、设备准备、rolling extension、grant 或车尾释放。

## 2. 模块工作方式

一次正常申请只有一份冻结路线和一份最终 claim：

```text
SimpleATO selects one frozen route and desired authority boundary
  -> a signalling plugin may attach additional registered resource requirements
  -> Basic validates route/topology/observation coherence
  -> Basic derives the non-removable physical resource floor from the route
  -> Basic validates and unions caller additions with that floor
  -> Basic checks authoritative occupancy and atomically claims the union
  -> registered resource handlers prepare the claimed resources
  -> READY publishes a grant; denial publishes no wider authority
  -> the existing route cursor and release queue shrink the claim behind the train tail
```

Basic proves physical consistency; the signalling plugin decides signalling policy. A protection-region plugin may inspect its own definition and the submitted route, choose an authority boundary beyond the region exit, and add flank-protection or approach resources. Another plugin may implement route tables and sectional release without defining a `ProtectionRegion` at all.

## 3. 内部机制与关键算法

### 3.1 不可删除的物理资源底线

Basic projects the requested route prefix/suffix against the exact retained `TopologyState` root. In one route-order pass it derives:

- one canonical AB rail interval for every traversed physical rail slice;
- one stable, direction-independent resource for every traversed turnout;
- every route-bound shared physical conflict, such as an at-grade diamond, matched by the catalog bound to the same topology root;
- route-ordered turnout selections and physical occupancy query geometry from the same projection.

This derived set is the **mandatory physical footprint**. The request cannot replace it, remove members from it, or supply an alternative turnout/diamond identity. Basic may merge adjacent intervals of the same rail but must not infer a cross-rail global interval.

### 3.2 插件附加资源

A signalling plugin may add stable resources that it owns or is allowed to request, for example a flank turnout, an approach-lock resource or a whole-block token. These additions are data, not executable callbacks inside Basic's route scan. Basic validates each identity through the resource registry and checks that any requested preparation capability is registered for that resource.

The plugin remains responsible for computing why the addition is needed and how far the desired authority must extend. Basic does not call a chain of region/provider resolvers, ask one region to inspect its successor, or synthesize protection coverage. The final footprint is:

```text
mandatoryPhysicalFootprint(frozenRoute, requestedRange)
  union validatedAdditionalResources
```

The union is unique by exact resource identity and is committed once. Occupancy query geometry, preparation requirements and release entries are metadata for validation/operation/lifecycle; none is a second claim footprint.

### 3.3 申请、准备和释放

Basic first completes route/resource/occupancy validation, then atomically claims the final footprint. Claim ownership prevents conflicting claims but does not mean a movable resource is ready. Registered resource handlers prepare only already-claimed resources and report `PREPARING`, `READY`, `ABORTED_SAFE` or `UNSAFE` according to the generic preparation contract. A grant is published only after all requirements are `READY`.

Committed resources enter one route-ordered release queue owned by the live grant. Each observation advances the existing monotonic frozen-route cursor from the authoritative train tail. Basic atomically removes only the eligible queue prefix, then removes the same prefix locally. A signalling plugin may supply a stricter release boundary for its added resource; it cannot release a mandatory physical resource earlier than Basic's physical clear boundary.

Rolling extension keeps the same frozen route and cursor. Basic compiles only the farther suffix, derives its physical floor, unions extension additions, reserves/prepares only the delta, and appends committed release entries. A changed route is a replan at a safe operational boundary, not a whole-claim replacement.

## 4. 签名与合同

The following shapes are conceptual until the public API batch freezes package and type names:

```kotlin
data class RouteResourceIntent(
    val requestId: String,
    val ownerId: String,
    val trainId: UUID,
    val observation: RailwayStateStamp,
    val route: FrozenRoute,
    val desiredAuthorityBoundary: RouteBoundaryRef,
    val additionalResources: List<AdditionalResourceRequirement>
)

data class AdditionalResourceRequirement(
    val resource: InfrastructureResource,
    val holdUntil: RouteBoundaryRef? = null
)
```

The caller supplies neither raw mandatory rail intervals nor turnout/diamond selections. `holdUntil` may only retain an added resource longer; it cannot shorten Basic's compiled physical release boundary. Train length, occupied intervals and facing come from the referenced authoritative observation, never caller fields.

Resource registration is namespaced and world-local. Registration defines stable identity validation, conflict/overlap behavior, optional preparation handler and lifecycle ownership. It does not grant permission to mutate Basic's claim registry directly.

## 5. 不变量、生命周期、线程与版本

- Route, observation and route-bound resource catalog must refer to the same topology root/revision.
- The mandatory physical footprint is always present in the committed union, regardless of caller additions.
- Validation/preparation failure never publishes a wider grant. Unsafe or unprovable device state retains the safest claim and stalls that world rather than releasing protection.
- Signal-plugin disable prevents new policy grants. Existing live grants and release metadata are host-owned until physical clearance or explicit safe recovery; plugin unload cannot drop them beneath a train.
- Runtime claims are not persistence schema. Stable resource definitions belong to the plugin or infrastructure owner that defines them; Basic persists only a boundary explicitly approved later.
- Registry mutation and resource preparation obey the world owner thread; physics observations crossing threads are immutable values.

## 6. 校验与错误矩阵

| Condition | Result | Mutation |
| --- | --- | --- |
| observation/topology/route mismatch | `STALE_STATE` | none |
| disconnected or illegal frozen traversal | `INVALID_ROUTE` | none |
| mandatory physical resource cannot be resolved | `INVALID_INFRASTRUCTURE` | none |
| additional resource is unknown or not requestable by owner | `INVALID_ADDITION` | none |
| occupancy intersects compiled physical geometry | `PHYSICALLY_OCCUPIED` | none |
| final union conflicts with another claim | `RESOURCE_CONFLICT` | none |
| all validation passes | claim union, then prepare | one atomic claim |
| preparation still running | `Pending` | claim/reservation retained; no grant |
| preparation proves safe abort | typed denial | only this request/delta is removed |
| preparation becomes unsafe/unprovable | `WORLD_STALLED` | safest claim retained |

There is no Basic `PROTECTION_REQUIRED`: missing protection-region coverage is a configuration/policy error reported by the concrete signalling plugin before it asks for a permissive route. Basic still denies any request whose physical route or resource union is invalid.

## 7. 性能、复杂度与退化行为

Let `R` be projected route slices in the requested range, `B` route-bound catalog hits, `A` additional resources and `Q` occupancy-query candidates. Compilation is `O(R + B + A + Q)` after indexed lookup; registry conflict cost follows the resource indexes. Basic must not invoke every installed signal plugin, scan all regions or execute arbitrary JS during this pass.

Unchanged state performs no request compilation by itself. SimpleATO/resource inspection decides when a request is needed. Missing indexes, stale revisions or resource-handler failure fail closed; Basic must not fall back to a live-world full scan that silently grants authority.

## 8. Good / Base / Bad cases

- **Good**：a Japanese-signalling plugin requests a throat route, adds a flank turnout and holds it to a later boundary; Basic also inserts all traversed rails, turnouts and diamond conflicts before one atomic claim.
- **Good**：a different interlocking plugin submits route-table resources and approach locking without creating any Basic protection region.
- **Base**：a straight route has only mandatory rail intervals and no plugin additions.
- **Bad**：a plugin submits only its region ID and expects Basic to discover region policy or scan successor regions.
- **Bad**：a plugin omits a traversed turnout or supplies another identity; Basic still derives the canonical turnout resource.
- **Bad**：Basic treats plugin additions as authoritative replacement footprint or lets JS directly mutate a claim.

## 9. 必需测试与断言点

- Route projection covers AB/BA, zero/one/multiple turnouts, loops and route-bound diamonds with deterministic identities.
- Omitting every caller addition still produces the complete mandatory physical footprint.
- A caller cannot remove/replace a derived rail, turnout or diamond resource.
- Valid additions join the same atomic footprint; unknown/unauthorized additions and conflicts leave no partial claim.
- Occupancy geometry and preparation/release metadata never become alternate claim sets.
- Immediate and delayed preparation publish no grant before `READY`; unsafe failure retains the safest claim.
- Tail release and same-route extension retain the existing cursor, release only a contiguous eligible prefix and never shorten plugin-specified holds.
- Plugin disable/reload tests prove existing grants remain fail-closed and releasable without executing plugin policy again.

## 10. 迁移、未决事项与 Wrong / Correct

Migration keeps the implemented generic pieces—topology projection, canonical turnout identity, route-bound catalog, occupancy query, atomic registry, preparation, cursor/release queue and suffix extension—and removes protection-region assumptions from their public target. `ProtectionRegionDefinition`, region graph-cut/catalog/window resolution, fixed-block partitioning and `PROTECTION_REQUIRED` move to `JapaneseSignals` or a helper library owned by that plugin.

Still open for later API batches: exact public names/packages; permission model for requesting another plugin's resource; persistence/recovery boundary; and plugin-local script API identifiers. The concrete product name `JapaneseSignals` does not change the physical-floor invariant or freeze those API identities.

```text
Wrong: the caller supplies the complete footprint and Basic trusts omissions
Correct: Basic derives the non-removable physical floor and only accepts additive resources

Wrong: Basic owns one universal protection-region/fixed-block model
Correct: each signalling plugin owns its policy model and compiles it into generic additions and restrictions

Wrong: Basic invokes a recursive provider closure to decide signalling policy
Correct: the signalling plugin decides the requested boundary/additions; Basic validates and claims one union
```
