# SimpleATO 运营选路与行车授权契约

## 1. 状态、范围、触发条件与 Owner

- **Status**：`TARGET`；现有 ATO、路线、时刻表与运营对象仍在 Basic，目标 `SimpleATO` Gradle 模块尚未创建。
- **Owner**：SimpleATO 拥有运营路线选择、运行方向、任务/时刻表决策和列车保护约束合成。
- **Inputs**：Core 发布的不可变 `TopologyState` 与同 epoch 物理观察；SimpleATO 自己发布的运营 overlay/plan revision；Basic 发布的统一`AuthoritySection`观察与资源准入结果。
- **Outputs**：一份绑定 topology revision 的冻结路线、资源准入请求，以及交给控制器的统一 authority/stop/speed constraint 结果。
- **Non-goals**：本页不定义 Core 图构建、Basic 资源推导/claim、Bukkit 渲染、信号模型、JS API 或持久化 schema。

触发：迁移或修改 ATO mission、route planning、运行方向、固定/移动闭塞选择、保护区域交互、时刻表 dispatch、MA/stop/speed constraint 合成。

## 2. 模块工作方式

一次正常自动行车决策按以下流程工作：

```text
coherent Core observation + TopologyState(revision)
  -> load one immutable operational-overlay/plan revision
  -> choose the operating route to the current operational target
  -> freeze one ordered directed traversal bound to topology revision
  -> submit that route, train identity and observation stamp to Basic
  -> Basic validates and either atomically grants resources or denies
  -> convert the result plus route-local observations into constraints
  -> reduce all candidates through the existing train-protection pipeline
```

SimpleATO 决定“列车准备走哪条路”，Basic 只证明“这条路在当前物理拓扑上是否合法且资源能否安全取得”。Basic 拒绝后 SimpleATO 可以在新的 observation/overlay revision 上重算，但 Basic 不替它换路；SimpleATO 也不得通过第二份 caller-authored resource list 指挥 Basic 跳过资源发现。

没有 SimpleATO 时，不产生自动受保护进路。Basic 可以保留经过物理锁和 active-claim 检查的显式手动道岔操作，但该操作不产生 movement authority。

## 3. 内部机制与关键算法

### 3.1 运营 overlay 与选路

运行方向、站台/停车点、线路、信号边界和限速是SimpleATO拥有、并引用Core edge/rail slice的运营overlay，不是另一张可独立修改的物理连通图。固定闭塞与保护区域由Basic拥有：Basic发布统一`AuthoritySection`观察，SimpleATO只读取已验证section状态并应用信号/闭塞运用策略。路线起终点落在edge内部时，选路查询使用临时virtual split；不得为了站台或信号永久切分`TopologyState`。

选路输入固定为一个 topology root、一个运营 overlay/plan revision、列车属性和当前运营目标。算法只在 Core 声明的合法 continuation 中搜索，再按方向、线路、车辆能力、停站与计划约束过滤或计权。通用最短路实现可以来自共享算法包，但运营 cost、约束和候选选择由 SimpleATO 拥有。结果必须是一条明确的有向 traversal；不得只给目的地让 Basic 自行寻找可行路线。

### 3.2 冻结路线与准入客户端

冻结路线保存其生成时读取的 `topologyRevision`、独立的 `routeRevision` 和有序 traversal。SimpleATO 向 Basic 提交路线、列车 ID、同一 observation stamp、当前 route progress 与期望 authority boundary；不提交 raw `InfrastructureFootprint`、可信列车长度或已枚举的 turnout/diamond 列表。

SimpleATO 将普通固定闭塞、保护区域和移动闭塞视为统一 authority-section 流中的不同贡献者，但不替 Basic 展开保护区域内部资源：

- 固定闭塞的授权粒度是完整区间；
- 保护区域从入口到满足出口净空的资源由 Basic 根据冻结路线验证并原子取得；
- 移动闭塞在没有不可分割区间时允许按路线与制动/跟车约束形成更精细的 authority。

固定闭塞与保护区域不得在同一方向上重叠；Basic在同一个authority-partition transaction中校验并拒绝冲突，SimpleATO不维护第二套section store或重复执行空间校验。两种section向信号策略暴露一致的占用、长度、`canEnter`和下游限制读取面；definition、资源推导和claim始终只有Basic一个owner。

### 3.3 单一列车保护管线

车辆跟随、运营停车点、未获准进路、固定闭塞、信号限速、临时限速和线路限速都产生不可变 constraint candidates，随后由现有 ATO/ATP 控制骨架做一次最严格结果归约。MA 是该共享约束状态的输出/诊断视图，不是与 `FollowConstraint` 并行的第二套扫描器或控制器。

每个 controlled train 在一个成功 physics epoch 中最多执行一次前方路线 perception。区间、车辆和外部 provider 从这次扫描及 world-level 索引读取数据；任何策略都不得为了自己的信号显示或资源判断再做一次完整路线遍历。Basic 的 grant/denial 作为候选输入，不在 SimpleATO 中复制资源冲突计算。

## 4. 接口与数据合同

以下仅为目标概念 shape；完整类型名和 public package 必须在 API 实现批次冻结：

```kotlin
data class FrozenOperationalRoute(
    val topologyRevision: Long,
    val routeRevision: Long,
    val traversal: List<DirectedTrackEdgeRef>
)

data class AutomaticRouteIntent(
    val requestId: String,
    val ownerId: String,
    val trainId: UUID,
    val observation: ObservationStamp,
    val route: FrozenOperationalRoute,
    val progress: RouteProgressRef,
    val desiredAuthorityBoundary: RouteBoundaryRef
)
```

`observation`中的 physics epoch 必须与选路所读列车状态一致；`route.topologyRevision`是唯一 topology 绑定，request 不再复制第二个 expected revision。Basic 返回 typed grant/denial；SimpleATO 只消费结果，不读取或修改 Basic 内部 claim registry。

运营 overlay 引用优先保存 stable rail UUID + canonical AB interval 或独立 stable operational ID；revision-local node/edge ID 只可作为已解析 runtime reference，不能默认作为跨重启身份。

## 5. 不变量、生命周期、线程与版本

- 同一次选路只能组合一个 topology root、一个 coherent physics observation 和一个已应用运营 plan/overlay revision；不得拼接“最新”组件。
- topology、overlay、mission 或 route target 改变后，旧 route 不会被就地改写；生成新 `routeRevision` 并重新准入。
- Basic denial 不扩大 authority。现有安全 authority 保持或收紧，直到新 grant 成功。
- live grant 的 resource lifecycle 由 Basic 持有到目标列车进入并由车尾完全清除；SimpleATO mission replacement、插件 disable 或 route replan 不能直接提前释放。
- applied timetable 在生效前完成路线/信号配置校验，并通过 host lease 锁定所引用基础设施；draft 保存不产生 lease。
- physics worker 只消费不可变输入并产出 constraint/result；拓扑编辑、资源 claim、Bukkit side effect 按各 owner 的线程合同执行，SimpleATO callback 不阻塞 physics publication。

## 6. 校验与错误矩阵

| 条件 | SimpleATO 行为 | authority/资源 |
| --- | --- | --- |
| topology/observation/overlay revision 不一致 | 放弃本次计算，等待 coherent state 重算 | 不申请；authority 不扩大 |
| 找不到满足方向和运营约束的路径 | mission/plan typed failure | 不申请 |
| 冻结 traversal 被 Basic 判定断裂或非法 | 将 route 标记失效；仅在输入 revision 改变后重算 | Basic 无 mutation |
| controlled resource 缺少完整保护覆盖 | 报告稳定配置错误 `PROTECTION_REQUIRED` | 不逐 tick 重试；authority 截止在安全边界 |
| 物理占用或资源冲突 | 按 dispatcher 排队/重试策略处理 | Basic 无 partial claim |
| grant 成功 | 将 grant boundary 转为 constraint candidate | 不绕过 Basic 扩大边界 |
| provider/模块失效或约束无法定位 | fail closed，撤销其放宽效果并保持/施加限制 | live claim 交由 host recovery |
| route 有 no-controlled-resource 且无需 claim | 接受 `NOT_REQUIRED` 并继续普通约束计算 | 不创建空 claim |

稳定配置错误由 topology/overlay/plan 事件驱动重试；暂态占用冲突才按明确调度策略重试。不得把所有 denial 都变成每 tick 全量重新寻路。

## 7. 性能、复杂度与退化行为

- 路线搜索复杂度取决于选定算法，至少使用 `TopologyState` 邻接与 revision-keyed overlay projection；不得从 live rails 每 tick 重建图。
- 一旦路线冻结，前方 perception 对实际扫描长度为线性；一个 controlled train、一个 physics epoch 只做一次，constraint 归约对候选数线性。
- world occupancy、区间状态和 Basic grant 状态应先按 rail/resource 建索引；禁止 `allTrains × allSections` 或每个信号各自 lookup 全世界。
- unchanged stable denial 使用缓存的 typed result 与 revision key；相关 revision 不变时不重复昂贵解析。
- 索引缺失、provider 异常或 revision 在计算中改变时 fail closed；不得退化到另一套 live graph/资源扫描并继续授权。

## 8. Good / Base / Bad cases

- Good：列车去往运营停车点，SimpleATO 选择一条包含道岔和 diamond 的 traversal；Basic 从同一 topology root 验证并收集资源，SimpleATO 从 grant 生成唯一 authority constraint。
- Good：保护区域出口后的列车净空进入固定闭塞；SimpleATO 仍只提交一条路线，Basic 在一次准入中完成必要闭包，区域本身不查询后继。
- Base：纯直线移动闭塞路线没有 controlled resource；Basic 返回 `NOT_REQUIRED`，SimpleATO 仍用车辆/停车/速度约束控制列车。
- Bad：SimpleATO 同时提交 route 与自己猜测的 turnout/diamond footprint；两份事实可能漂移，必须只提交 route。
- Bad：每个信号脚本重新遍历前方路线和所有列车；必须消费一次 perception 与索引化 section observation。
- Bad：Basic 因资源冲突自行换另一条路后授权；运营选择只能回到 SimpleATO 产生新 route revision。

## 9. 必需测试与断言点

- route selection：方向限制、edge 内 virtual endpoint、零/一/多 turnout、纯环、无路线，且输入相同得到确定 traversal。
- revision binding：route 与 topology revision 独立；任何 observation/topology/overlay stale 都不发请求、不扩大 authority。
- admission client：请求没有第二份资源清单；`PROTECTION_REQUIRED`、conflict、`NOT_REQUIRED` 和 grant 分支均保持规定 lifecycle。
- section composition：固定闭塞完整区间、保护区域、移动闭塞及出口后连续贡献；断言保护区域不查询后继。
- control pipeline：车辆、停车、信号和 grant candidates 只做一次 perception，并归约到最严格 speed/authority。
- retry：稳定配置错误 revision 不变时不逐 tick 重算；占用释放事件允许重新准入。
- migration parity：迁移前后同一 fixture 的 route、stop/speed constraint、dispatcher priority 与 manual-mode 行为一致。

## 10. 迁移、未决事项与 Wrong / Correct

迁移顺序是：先稳定 Core `TopologyState` 与 Basic route-validation seam，再把当前 Basic 中的运营 route producer、mission/constraint owner 和 Dispatcher client 整体迁入 SimpleATO，最后迁移 Destination、Station、Depot、Line、Timetable 及其持久化/composition。每批只允许一个可写 owner。

尚未冻结：完整 public type/package、路线 cost policy、overlay 持久化边界、信号 section 读取接口、JS setter/capability/预算、provider heartbeat，以及 WebUI 是否独立成模块。这些问题必须在对应实现批次单独审查；本页不替它们选择兼容格式。

```text
Wrong: SimpleATO selects a destination; Basic searches and chooses the physical route
Correct: SimpleATO submits one explicit topology-bound traversal; Basic only validates and claims it

Wrong: MA and FollowConstraint each scan and control the train independently
Correct: one perception pass produces candidates; one train-protection pipeline reduces them

Wrong: Basic and SimpleATO both own mutable copies of the operational graph
Correct: Core owns TopologyState; SimpleATO owns disposable revision-bound overlay projections
```
