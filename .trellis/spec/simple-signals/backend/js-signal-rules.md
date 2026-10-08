# JS 信号规则：同步执行与树形前视

## 1. 状态、范围与负责模块

这是分阶段契约。统一信号包解析、独立 GraalJS 执行、真实 world publication 到有向区间连接图的准备、
不可变树形观察计划编译器、显式设备绑定后的同步 epoch 填值／求值，以及插件启动时的包 catalog 安装均已实现
（`CURRENT`）。当前实现复用现有固定闭塞、保护区域、保护进路和规范化 `TopologyState`，按路径展开直线、分支、
保护进路和环终止。固定闭塞／保护逻辑设备在世界 current model 中只保存必填 `type`；Kotlin 属性名为
`signalType`。它是由 SimpleSignals catalog 解析的稳定 namespaced 类型身份，不保存 catalog 来源。
`signalId` 继续表示世界内的一台设备，不能从它、模型 ID 或文件名猜测类型。同一 signal type 的 catalog 快照现在也
决定生产模型；玩家通过 Wand 为既有固定闭塞入口主信号选择类型并移动轨道附着位置已是 **CURRENT**，创建新主信号及其他设备工具仍是 **TARGET**。
除此之外不增加时间、内存、节点、分支或整波预算；包作者负责规则的终止性和成本。

SimpleSignals 负责包加载、脚本执行、前视投影、输出校验和信号结果发布。Basic 仍只负责物理资源校验与仲裁，SimpleATO 仍负责路线前视、资源申请、动态制动和最严格约束合成。JS 不得选路、扳动道岔、授予资源、直接制动列车或修改世界。包分发与玩家可见类型见[统一信号包](model-packages.md#统一信号包modellogic-与一等-signal-类型)。

其他插件若要扩展 SimpleSignals catalog，必须等待未来单独评审的 add-on API；该 API 不属于当前实现，也不能把来源写入
world `type`。另一套完整信号系统插件可以拥有自己的 domain/runtime，但不是 SimpleSignals 的 Kotlin signal type。

旧的 Kotlin 输出表、`SectionSignalRuleRuntime` 和 provider union 已删除。当前逻辑信号只执行 catalog 类型解析后的 JS；通用状态、publication、inspection 与拓扑 guard 仍复用。源码边界见[研究记录](../../../tasks/09-18-signal-addon-implementation/research/js-rule-runtime-source-map.md)。

## 2. 一次规则执行如何完成

1. 插件启动 worker 从 `signal-packs/` 读取元数据 `pack.yml`、`model/`、成对的 `logic/*.yml`／`logic/*.js`
   和 `signal/*.yml`，以稳定 `modelId`、`logicId`、`signalType` 编译并校验完整 catalog。每个 world 再把显式
   `deviceId -> signalType` 绑定编译为自己的 epoch runtime；同一个 catalog signal 的 materialized model 进入既有
   `PreparedSignalModels`，世界只提供可选摆放。当前 Wand 只更新既有固定闭塞主信号，创建新设备仍属后续批次。
2. 拓扑或配置变更后，SimpleSignals 在低频准备阶段从信号入口编译前视计划。计划包含扫描范围内的所有合法后继，以及运行时筛选分支所需的道岔、保护进路和区间身份；它不包含某一 tick 的占用结果。
   当前 `SignalObservationGraphBuilder` 已从同一 `SimpleSignalsRuntimePublication` 的真实 section/topology
   publication 构造连接图，`SignalObservationPlanCompiler` 再把它变成不可变路径树；运行时只给该计划填入当前 epoch 的事实。
3. Core 完成物理 worker 并捕获同一 epoch 的物理观测后，进入已有串行 signal epoch。
4. 对显式 `SignalTypeBinding(deviceId, signalType)`，SimpleSignals 用该 epoch 的占用、选路、资源准备和设备状态填充前视计划，生成只读树形输入。
5. JS 在这个信号阶段内同步调用 setter。调用正常返回且输出完整有效后，才构造信号结果。
6. 同一 signal epoch 的所有 publication 均成功后才推进完成 epoch。SimpleATO 在下一次控制计算读取已完成结果；模型显示读取同一个不透明外观名，不另跑一遍脚本。

脚本执行不是异步任务，不使用“完成后回填”的 future，也不另建第二个信号 epoch。包发现、文件读取、编译和拓扑计划准备可以离开 tick 热路径；`evaluate` 本身必须位于上述同步信号阶段。

## 3. 树形前视的内部机制

### 表示与编译

每个逻辑声明一个最大扫描距离，单位为沿轨米数。该距离属于逻辑定义：同一逻辑和不同模型组合时保持一致，也不成为每台已放置信号可任意覆盖的参数。它只决定插件向 JS 暴露多远，不代替 JS 的灯色或限速判断。

低频编译从有向控制入口展开所有合法后继，而不是只沿当前选中的支路：

- 普通二度轨道只贡献距离，不要求 JS 认识普通拓扑节点；
- 道岔暴露候选分支以及当前选中分支；
- 保护区域暴露可用进路分支及其选择、准备状态，JS 仍无权选择进路；
- 固定闭塞和保护区域都以有向区间节点出现在同一视图，规则无需按实现类型另做 lookup；
- 每条分支分别累计沿轨距离，兄弟分支的距离不能相加。到达最大距离的分支独立结束。

运行时只向已编译计划填值，不能每次 JS 字段访问都查询世界、运行 Dijkstra 或启动第二套 ATO 扫描。同一物理图节点可以在内部共享，但交给 JS 的只读视图必须保留每条到达路径的分支语义。

当前内部表示为 `SignalObservationGraphNode`、`SignalObservationContinuation` 和 `SignalObservationScanState`。
扫描状态由区间身份、行驶方向、入口边界和可选保护进路上下文共同确定；连接边保存区间之间未被区间覆盖的沿轨距离、
道岔所需 continuation 以及可选保护进路身份。`SignalObservationPlanCompiler` 从自己的区间入口以距离0展开，
当前区间长度和连接距离都计入后继入口的累计距离。开始位置仍小于上限的短区间会作为完整节点暴露；其区间内到达上限时，
该节点以 `distance-limit` 结束。分叉的每条边独立携带累计距离；汇合到同一物理状态会生成两个路径 occurrence，不折叠公开树。

编译结果同时保存实际可见的区间、非循环下游信号、道岔入口和保护进路依赖集合，供后续 publication/dirty 索引接线；
它们只是不可变计划元数据，不读取当前道岔、请求、占用或信号输出。

`SignalObservationGraphBuilder` 不另建物理拓扑。它要求固定闭塞快照、保护区域编译快照和保护进路选择索引都绑定
同一个 `TopologyState` 对象及定义 publication，然后复用已有 traversal 和 `RequiredActiveContinuation`；普通连接、道岔合法
连接、确定性 loop anchor 和尽头直接从规范化拓扑读取。区间之间的普通轨道只累计沿轨距离。相同位置只有同一保护入口的
多条进路可以成为并列候选，其他重叠入口是歧义并拒绝整个候选。一个保护进路可以同时绑定 fixed-route 与 current-entry
逻辑信号，因此节点保存全部逻辑 signal ID；附属显示器只复用主信号 root，不创建第二个逻辑节点。

构造完成的 `SignalObservationGraphPublication` 与 topology、world definitions 一起作为完整不可变值进入
`SimpleSignalsRuntimePublication`；普通定义准备和局部拓扑重验都必须在 publication 可见前完成构图。未受影响的保护进路
对象可以跨无关 topology revision 复用；一致性由外层 `ProtectionRouteSelectionIndex.topology` 的精确对象身份和 authored
definition 再校验保证，不能错误要求被复用对象内部保存的旧 revision 数字等于新 revision。

### 环终止

展开某条分支时维护该分支已经经过的有向扫描状态。状态身份至少包含轨道／区间身份、行驶方向、入口边界和已选保护进路上下文；仅用 rail UUID 会把反向经过或不同进路误判为环。当前编译器使用 branch-local state→path-index 表做常数时间回边检查，并把子树发现的最早循环起点沿递归返回，使每个路径 occurrence 最多标记一次；不能在每条回边上重新遍历整个循环段，否则多回边图会退化出 `O(V + E)` 之外的额外开销。若下一个状态已在当前分支出现：

1. 不再展开重复状态；
2. 只把该叶标成 `cycle`，记录到达距离以及可计算时的环起点和环长；
3. 不沿该回边建立递归下游输出依赖；
4. 继续生成其他兄弟分支。

这不是 SCC、fixed-point 或迭代收敛算法。包含环的分支仍可把环之前的物理区间事实交给 JS；回边之后不存在“下一圈”的递归信号输出。为避免 `A -> B -> C -> A` 在同一 signal epoch 中变成求值顺序相关的输出环，编译器把第一次出现到重复状态之间标为该分支的循环段：循环段内的信号 occurrence 不提供本轮或上一轮的 `downstreamSignal`，只提供同 epoch 的物理区间事实和最终 `cycle` 终止原因。环外的无环依赖仍可按既有顺序读取本轮已经确定的下游输出。零长度有向环无法形成有意义的距离边界，应在准备阶段作为非法拓扑／配置拒绝。

真实拓扑扫描还维护 branch-local 的有向 cursor→沿轨距离表。若在遇到下一个区间前回到相同 cursor，正长度的无区间环
直接成为该分支的 `cycle` 终止，并把相对环起点/环长转换成信号 root 的绝对沿轨距离；兄弟分支继续。只有零长度重复
cursor 被拒绝，不能把整个正长度环或包含它的包判为非法。

## 4. JS 输入与输出契约草图

下面只冻结语义，不冻结最终字段名或磁盘形状。实现批次可以调整命名，但不能退化成只返回当前活动路径的扁平数组。

```ts
type SignalInput = {
  readonly self: { readonly occupied: boolean; readonly lengthMeters: number };
  readonly forward: ReadonlyArray<ForwardBranch>;
};

type ForwardBranch = {
  readonly distanceMeters: number;
  readonly section: ReadonlySectionFacts;
  readonly selection: "selected" | "not-selected" | "not-applicable";
  readonly routeReady?: boolean;
  readonly downstreamSignal?: { readonly speedLimitMetersPerSecond: number };
  readonly next: ReadonlyArray<ForwardBranch>;
  readonly end?: {
    readonly reason: "distance-limit" | "track-end" | "cycle" | "route-unresolved" | "topology-unavailable";
    readonly cycleStartDistanceMeters?: number;
    readonly cycleLengthMeters?: number;
  };
};

type SignalOutput = {
  setSpeedLimit(metersPerSecond: number): void;
  setModelVariant(name: string): void;
};

function evaluate(input: SignalInput, output: SignalOutput): void;
```

每条可见分支都提供区间是否有车及区间长度；不属于循环段的后续信号可以提供其本轮已确定的逻辑入口限速。`distanceMeters` 从当前逻辑信号的控制入口沿该路径计算，不从模型位置或玩家视线计算。保护区域没有确定合法进路时以结束原因表达未知通路，不能伪装成 `track-end` 或空闲末端。循环段内的 `downstreamSignal` 必须缺失，JS 依靠同 epoch 物理事实与 `cycle` 终止作决定；宿主不能用上一轮许可补齐它，也不能因规则求值次序不同而让某个根看到新值、另一个根看到旧值。

两个 setter 每次必须各调用一次。重复、缺失、非法类型、非有限／负速度、空外观或未声明外观均使本次结果无效。输出槽只在调用期间有效；脚本不能保存 setter 延迟写入。`setSpeedLimit(0)` 表示列车到逻辑入口位置时速度应为零，SimpleATO 仍按距离计算制动曲线，不是要求列车调用当下已经停车。外观名和安全速度彼此独立，禁止 `return "YG"` 或根据外观反查速度。

第一版不暴露原始列车／世界对象、资源 registry、可变道岔、可变进路表、`availability()`、`reservation()` 或任意世界写操作。是否放行和资源授权先由现有适配器与选择器收紧；JS 只处理给定的只读信号事实。

## 5. 生命周期、线程、沙箱与版本

所有输入必须来自同一物理 epoch、同一拓扑／定义 revision 和仍有效的世界绑定。拓扑、包或定义替换使对应前视计划失效；旧调用不得覆盖新绑定。完整结果原子发布，消费者不读取半更新状态。

插件管理器拥有一份共享 `SignalPackCatalog` 和 `SignalLogicRuntime`。包目录只在启动 worker 加载一次，不在 world
回调或 JS 字段读取时重读。包含 signal type 绑定的 world 在 catalog 就绪前安装限制性 runtime：所有相关设备发布
`Unavailable` 和速度 0；catalog 成功后，只有 world runtime、publication 和 topology 仍与准备起点完全相同时，
才能通过原子 source replacement 换入编译后的 `SignalPackEpochRuntime`。迟到结果不得覆盖已经替换或卸载的 world。

固定闭塞和保护信号的 current schema 1 JSON 必须显式包含 namespaced `type`。该 sidecar 尚未发布，因此本次直接
修正 current model，保持 schema version 1，不添加旧 reader、upcaster、alias 或运行时兼容路径；旧 `provider`、
`packageSignalId`、输出表和显式主信号 `model` 都是严格未知字段。两类主信号只另存可选 `placement`，modelId 与
logicId 均由 catalog signal 唯一决定。`inspectionLeadTimeSeconds` 仍是每设备共同字段。

静态观察图使用 topology 与 definitions 的对象身份作为 cache identity，而不是只比较数值 revision。候选构图失败时，
定义准备失败并保留旧 live publication；局部拓扑重验也必须生成同一新 topology 上的图后才能原子替换派生 publication。

`SignalLogicRuntime(catalog)` 是已实现的 SimpleSignals 私有执行边界，不依赖 BukkitPlugin 私有实现类。它使用
Graal 24.2.2，共享一个 Engine 和按 logic ID 缓存的 immutable Source；构造时用临时 Context 验证语法和 `evaluate` 入口。
每次 `evaluate(logicId, JsonElement)` 都创建一个全新 Context/realm，在同一调用栈同步求值，然后在 `finally` 中
`close(true)`。fresh realm 阻止闭包和 `globalThis` 状态跨设备／调用串扰；强制关闭取消脚本偷偷启动但没有返回的 guest job，
不会为 async 增加后台等待、future 或 timeout。Context 和 runtime 调用由 `@Synchronized` 串行化，runtime 关闭后拒绝求值。

运行时禁止 Host class lookup、宿主对象遍历、文件、网络、Bukkit、线程、进程、native access、environment access、
polyglot access、动态导入、Promise／异步回调以及任意 I/O。stdout/stderr 丢弃，guest `console` 为 no-op；`Date`、
`performance`、`crypto` 和 `Math.random` 不可用。唯一 HostAccess 是带 `@HostAccess.Export` 的 evaluation-local setter bridge。
输入先从 `JsonElement` 文本在 guest realm 内 `JSON.parse`，再用启动时捕获的内建函数递归冻结，脚本不能通过宿主引用或
篡改 `Object.freeze` 绕过只读边界。

本批保持统一包 `config-version: 1`，没有旧开发格式 reader；Graal 作为 SimpleSignals 自己的 Paper library 声明，普通插件
JAR 明确拒绝包含 `org/graalvm/` 或宿主 class。真实 Paper 下载／加载仍未部署验证，不能用离线 JAR 检查冒充运行验收。

## 6. 校验与失败处理

- 包、脚本、入口或引用非法：拒绝完整候选，保留旧 live publication；不存在旧候选时该设备不可用。
- 启动 catalog 尚未就绪时，只允许不新增或改绑 `signalType` 的定义编辑；新绑定必须等待 catalog 校验。
  catalog 就绪后的候选编辑必须严格编译，未知 signal 或非法包不能借限制性 fallback 提交。
  若 catalog 恰在候选准备与原子提交之间就绪，该候选按过期准备拒绝并要求重试，不能用先前的限制性
  runtime／无模型绑定覆盖刚安装的同一 catalog 结果。
- 初始安装或拓扑安全重验可以用限制性 runtime 承接任意包编译失败，确保相关设备为 `Unavailable`／0；显式定义编辑
  不能用该机制掩盖坏配置。失败或迟到 attach 不得替换当前 permissive publication。
- 扫描距离不是有限正数、零长度有向环或计划无法与当前拓扑一致：拒绝准备结果，不安装半张树。
- catalog/topology 对象不属于同一 publication、区间入口歧义、路径要求冲突的道岔连接或区间几何长度不一致：拒绝候选并保留旧 publication。
- 自己区间无有效观测、保护入口未 ready、路径未确定或拓扑失效：结果保持停车／不可用，不把未知当空闲。
- 调用抛错、异步返回或 setter 合同无效：丢弃两个 setter 的全部输出，相关信号为 `Unavailable`、安全速度为 0。
- 环：不是执行错误；只结束当前分支并把 `cycle` 告诉 JS，兄弟分支照常可见。
- 模型创建／显示失败：不撤销安全限速；显示层按自己的默认外观退化，不能借故保留许可。
- 返回 Promise／async 结果或 detached async 越过调用边界写 output：规则失败；其他未返回的残留 guest job 也会随
  临时 Context 强制关闭而取消，不能影响已经结束的同步输出边界。
- 无限循环或耗时过长：未来接入后会阻塞同步 signal epoch。这是包作者／管理员承担的配置责任；运行时不伪造超时结果，不留下后台脚本，也不加入未获批准的 watchdog 或预算。

诊断必须定位包、逻辑、设备和失败类别；重复异常可以限频，但不得把反复失败改成成功许可。

## 7. 性能、复杂度与退化

真实图准备只在定义／拓扑候选准备或局部重验中执行；它沿每个可见分支扫描到下一区间、终止点或首个重复 cursor，
不进入 tick 热路径，也不重新寻路。设一个已编译前视的可见路径出现次数为 `V`、展开边数为 `E`，当前编译器以 branch-local 哈希索引检测环，
计划编译和不可变视图规模为 `O(V + E)`；汇合的物理节点会按不同到达路径产生独立 occurrence，公开语义始终是路径树。
运行时填值和 JS 遍历也至少与脚本实际访问的可见节点数成正比。没有“lookup 等于重新寻路”：单个已投影字段访问为 `O(1)`，文件和拓扑搜索不在调用中进行。

最大扫描距离分别截断各分支，不限制节点数或分支数，也不把所有分支长度相加。插件不设置执行时长、内存、节点、分支、递归深度或整波总量预算；这意味着错误脚本确实可以拖慢或阻塞服务器，属于明确接受的作者责任。运行时仍应避免每设备重复文件读取、每显示器重复执行和无关变化触发：一台逻辑信号在一个 signal epoch 至多求值一次，多个显示设备复用结果；输入未变时可以不求值，输出未变时不重复发布显示更新。

当前 foundation 每次求值创建并强制关闭一个 Context，以换取调用间状态隔离和 detached async 取消；Engine 与 Source
仍复用，文件不重读。这一固定成本必须在 signal epoch 接线批次用代表性包测量，不能声称等同于普通 Kotlin callback；
也不能在没有同等隔离证据时改回长期共享 Context 或引入未批准的 context pool。

## 8. 具体情形

### 连续短区间

`S2` 可以沿树查看 `S3`、`S4` 直到逻辑声明的最大距离，而不是只查看固定数量的后续信号。JS 根据区间长度、累计距离和各信号限速自行决定 `Y`、`YG` 等外观；Kotlin 不解释这些名称，也不替作者选择阈值。

### 道岔与保护区域

树同时包含未选和已选分支，`selection`／`routeReady` 告诉 JS 当前状态。JS 可以用它们显示道岔开放方向，但调用 setter 只能提交当前信号的速度与外观，不能改变选择或资源所有权。保护区域作为区间节点出现，普通信号逻辑不需要另查一套保护区域 API。

### 环线

若路径为 `A -> B -> C -> A`，从 A 展开到第二次 A 时，该叶为 `cycle` 并停止；B、C 之前的区间事实仍可读取。JS 可以根据终止原因采取自己定义的保守外观。运行时不继续第二圈，不执行 fixed-point，也不把 `cycle` 当作线路尽头。
若一个道岔分支进入没有任何区间边界的正长度环，真实拓扑扫描在第一次回到相同有向 cursor 时生成同样的 `cycle` 叶；
另一条通往正常尽头或区间的道岔分支不受影响。

## 9. 必需测试与断言点

- 真实包 fixture 编译 JS，验证 Host、文件、网络、线程、Promise 和跨调用可变状态不能越界。
- 物理阶段先完成，JS 随后在同一串行 signal epoch 同步运行；结果只在完整 epoch 成功后可见。已由
  `SignalPackEpochRuntime` 的同 epoch 填值、求值和原子快照回归覆盖。
- 分叉同时暴露全部候选，选择变化只更新运行事实；每条分支独立累计距离，兄弟长度不求和。计划编译和运行填值均已接线。
- 连续短区间可扫描到距离边界，不存在固定“几个后继”的截断。计划编译已覆盖。
- 保护区域与普通闭塞使用同一树节点语义；保护分支保留入口和进路身份，未知进路不能成为空数组或线路尽头。静态结构和运行期 `routeReady` 均已接线。
- `A -> B -> C -> A` 只在当前分支终止，原因是 `cycle`，兄弟分支继续；环检测区分方向、入口和保护进路上下文。计划编译已覆盖。
- 环回边不建立递归下游输出依赖；循环段内不暴露本轮或上一轮下游输出，改变规则求值顺序不改变输入；零长度环在准备阶段拒绝，旧 publication 保持不变。依赖集合和 publication 原子保留均已验证。
- 真实 catalog/topology 组合覆盖双向闭塞、普通轨道间距、保护进路分支、道岔 continuation、同入口多逻辑信号、附属显示 root、不可用区域、歧义入口保留旧 publication、跨 revision 保留未受影响进路，以及正长度无区间环只终止命中分支。
- setter 缺失、重复、非法速度、未知外观和异常都原子失败并收紧到 0；显示失败不扩大许可。
- schema 1 的两种逻辑信号都必须显式写出 namespaced `type`；旧 `provider`、`packageSignalId`、规则表或显式主信号模型
  均严格拒绝。启动缺 catalog、未知 signal type、
  无效包和迟到 attach 均不能保留或恢复 permissive 输出；catalog pending 时不涉及新绑定的编辑仍可提交。
- 同一逻辑的多个显示器不增加求值次数；无关变化不求值，字段访问不触发拓扑寻路或文件读取。
- 用真实 GraalJS 和代表性大分支包记录耗时与分配量，作为作者调优证据；测试不据此加入隐藏预算。

## 10. 迁移、实施顺序与错误做法

第一批新信号包发现、两阶段引用解析和 GraalJS 最小受限执行已经完成。第二批实现有向区间图到不可变观察树的
编译；第三批已从当前 world publication 构造真实连接图，并把完整图绑定到定义准备和局部拓扑重验 publication。
同步填值批次已经通过 `SignalPackEpochRuntime` 接入既有 section `SignalEpochSource`：低频编译显式设备绑定和计划，
回调内先更新占用／选路，再按非循环下游依赖顺序求值，每台逻辑设备每个回调至多一次，最后一次性替换完整快照。
当前不存在 Kotlin 信号规则路径。所有逻辑设备都在一个 JS dependency DAG 中按同 epoch 下游优先顺序求值；环形物理
扫描仍按前述 branch-local 规则终止，不能形成输出依赖环。定义索引、inspection 声明、拓扑失效和声明 invalidation
独立于 JS 求值保留。source replacement 尚未取得新 baseline 时，未变 signal definition 以同一对象身份读取最后完成的
旧 source；已变 definition 不复用该结果，拓扑 guard 仍优先收紧。
生产启动／持久化绑定批次已经完成：两种逻辑设备通过 `type` 保存 `signalType`，插件启动时加载共享 catalog，
world manager 按 exact publication/topology 身份安装 epoch runtime，并在 catalog 缺失或无效时保持限制性结果。
共享 runtime 随 manager 清理关闭；单元测试覆盖 runtime 自身关闭，真实 Folia 下的 manager 清理和在线生命周期仍待部署验证。

生产模型绑定也已完成：逻辑与模型从同一不可变 catalog signal 解析，catalog 就绪后用精确
runtime/publication/topology 守卫替换显示绑定；缺失或未知 signal type 不创建错误 handle。模型创建／提交故障仍只属于显示，
不能改变已经发布的安全输出。仍待实现的是玩家通过 Wand 放置／选择 signal type、错误诊断载荷完善和热重载命令。

错误做法：每次 `lookup` 重新走拓扑；遇环拒绝整个包；用 future 超时但让脚本后台继续；把保护区域另做一套查询；让 JS 直接修改世界；以旧绿灯作为异常 fallback。

正确做法：低频编译只读树计划；运行时填充同 epoch 数据；每分支检测重复有向状态并报告 `cycle`；setter 结果原子校验；任何规则失败只收紧相关消费者，且不释放真实资源或扩大授权。
