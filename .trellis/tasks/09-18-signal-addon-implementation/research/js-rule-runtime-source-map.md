# Research: JS signal-rule runtime source map

- Query: 现有信号规则边界如何复用；按最大扫描距离提供自动前视数组还缺什么；DAG、选路、错误与真实复杂度是什么。
- Scope: internal, source-only；不选 JS 引擎、不实现运行代码、不修改 spec。
- Date: 2026-10-06

## Findings

以下路径相对于仓库；当前源码为依据，历史清单的未勾选不直接证明缺功能。

### Files found

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SectionSignalRuleRuntime.kt`：不可变规则输入、通用输出、binding、DAG 上规则执行与局部失败。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SectionOccupancyRuntime.kt`：统一 fixed-block / protection-route 占用观测、选择门控、完整快照发布、模型与 inspection 失效交接。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/OneWayInvalidationGraph.kt`：低频依赖编译和 provider → consumer 更新波。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/FixedBlockSignalCompiler.kt`：有向入口几何、显式下游引用及真实沿轨距离编译、具体阈值规则。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/ProtectionSignalCompiler.kt`：按已录制进路分别编译依赖、固定/当前入口混合规则图。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/ProtectionRouteSelectionRuntime.kt`：匹配真实准入请求、实际道岔和净空结果的共享唯一进路选择。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/presentation/SignalModelBinding.kt`：输出外观校验与主/附属设备的差异显示。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/signal/SignalEpochRuntime.kt`：世界串行 epoch callback 与未捕获宿主错误的停运边界。

### 1. 可直接复用的输入/输出接缝

- `SectionOccupancyObservation` 已有 `section`、`definitionRevision`、米制 `lengthMeters`、不可变 `occupants` 和 `available`（`SectionOccupancyRuntime.kt:34–42`）。JS 最小的自己的区间接口可映射 `hasTrain = occupants.isNotEmpty()` 和 `lengthMeters`，不需要新增 Basic section 类型或重扫车辆。
- `SectionKind` 已同时提供 `FIXED_BLOCK` 和 `PROTECTION_ROUTE`（同文件 `19–22`）。保护区不是整区统一占用位/统一长度：每条具体已录制进路有自己的几何和长度。`ProtectionSignalCompiler.kt:61–84` 复用 fixed-block 几何编译器构造进路观测；`SectionOccupancyRuntime.kt:339–347` 按固定进路 ready 或共享当前入口选择解析实际 section。普通 JS 应读已解析的 section，不自行特判保护区或猜长度。
- `SectionSignalRuleInput` 已提供同 stamp 的本 section、按距离排序的下游值及内部 `preparedRoute`（`SectionSignalRuleRuntime.kt:63–71,148–151`）。`DownstreamSignalView` 有 signal ID、从本逻辑入口沿轨道累计的米制距离、限速/不透明外观（`43–59`），**没有下游区间长度或占用字段**。不要直接把 JVM 内部对象/完整 prepared route 暴露给 JS；需窄只读 facade。
- `SectionSignalRule.evaluate(input)` 是可替换 provider 接缝（同文件 `74–76`）。JS 内 evaluation-local 的 `setSpeedLimit`、`setModelVariant` 可收集为最终一个 `SectionSignalOutput`；现有输出构造已经验证有限非负速度、非空白外观（`10–23`）。setter 不立即发布、申请资源或修改世界；脚本完成且校验通过后一次提交。
- 当前 production provider 在 `FixedBlockSignalCompiler.kt:105–117` 和 `ProtectionSignalCompiler.kt:139–147` 都是占用输出/下游最小速度阈值选档；已传入距离但具体算法未使用它。JS 距离逻辑替换此 policy，不新增并行 ATO constraint 或独立资源仲裁。

### 2. 距离、方向和现有唯一选路

- `FixedBlockSignalCompiler.kt:137–215` 的 `compileDownstreamPath` 不是寻路。它从控制 section 末端开始，初始累计包含控制 section 长度（`160–164`），沿真实 rail 长度到目标逻辑入口（`166–199`）；多出口必须有作者明示合法 continuation（`176–190`）。非法/未使用选择、重复有向 rail、同 entry 冲突选择都拒绝。
- 双向 block 按该规则入口方向构造 source 和 target geometry（`79–86`），不能沿 canonical AB 一律查找或借对端规则。设备模型摆放点也不是扫描起点。
- 已编译 dependency 保存沿路轨道影响集合和道岔条件；运行期用同 epoch immutable active continuation 做过滤（`SectionSignalRuleRuntime.kt:261–267`）。道岔不满足时不能临时读取 mutable RailView 或寻找其他分支。
- 保护规则的 downstream 依赖按 route ID 分组，依赖携带 source `protectionRouteId`（`ProtectionSignalCompiler.kt:115–129`）；运行期只激活当前已解析 route 的依赖。进路选择先由现有请求/授权/道岔/净空逻辑完成，JS 不能申请另一条进路。
- 当前保护 target geometry 对有多候选进路的当前入口取排序后的第一条 geometry（`ProtectionSignalCompiler.kt:85–86`），因为只是用共同入口定位目标信号。**自动扫描若要跨目标保护区到其出口，不能继续把第一条进路当作实际选路**；须消费同 epoch 的唯一已选进路，无选择/不ready 保持停车并终止该分支。

### 3. 最大扫描距离自动前视数组：尚未实现的真实补项

目前仅有显式 `downstreamSignals` 引用；没有按 signal 声明的 max scan distance 自动查找后续 section 的 builder/定义字段。不是改 `evaluate` 一处就能完成。

建议 TARGET 契约把以下工作说明清楚（未实现、API 名称未冻结）：

1. 以逻辑有向入口为起点，插件低频编译已知区间入口/出口与连接关系索引；将扫描最远距离所覆盖的有序 section/signal 候选、沿轨距离、continuation 条件和影响 rails 加入现有 publication/binding，不另建世界 tick 查询。
2. 若数组需要 `lengthMeters`，在编译结果保留目标 section 的已验证几何元数据；目前 DownstreamSignalView 只有输出/距离，不能声称已有完整数组。
3. 对自然唯一 continuation 继续；遇到保护区域用已有固定进路表及同 epoch 请求选择。不得用最短路、当前道岔碰巧可达、列表第一项代替运营进路。无选择时可读该入口零速结果，不能越过它猜后续。
4. 定义扫描范围外、轨道尽头、移动闭塞/未划区间 territory、保护入口无选择、缺少信号规则的终止语义。不能把配置错误/不可达包装成“无下游所以最高速度”。扫描距离是插件准备数据的上限；是否采用多少前方区间和具体灯色/速度判断仍由 JS 决定。
5. 候选分支/保护进路的 occupancy/selection/signal/continuation 兴趣编入原有 provider → consumer 反向依赖；运行时只选择唯一已编译路径并建立只读数组。不得每次脚本调用重复沿图 lookup，或为每列车新增 scanner。
6. 确认新的 JS 数组是否只读有效下游限速，还是也给下游 occupancy/length。追加原始占用读取必须追加对应 section provider 依赖，不能只在 output 不变时保持不唤醒而漏报脚本输入变化。

已批准“不需要预先声明几个区间，只声明最远扫描距离”，不能用固定数量上限重新制造该配置；脚本执行预算仍是另一件事。

### 4. DAG 与 JS 的关系

- 脚本只取代单个 node 的运算，不取代 `OneWayInvalidationGraph`。图低频 Kahn compile 拒绝自环/未知/重复/cycle（`OneWayInvalidationGraph.kt:74–114`）；更新按拓扑序每 node 至多一次，输出不变停止向上游传播（`46–70`）。
- 依赖所有可能保护进路/道岔分支的**并集**仍先检查 cycle，即使某些分支运行时不同时 active（`ProtectionSignalCompiler.kt:89–103`）。不能因为 JS “本次没读取那个元素”便声称不存在输出循环。
- 若 JS 可看数组里任何下游最终输出，要在执行前登记整份最大窗口的可能输出依赖；自动扫描可能新增 A→B→A，因此仍须拒绝候选并保留旧 publication。完整环线支持不是接入 JS 后自然获得，不引入旧 epoch 许可值或 fixed-point 求解。

### 5. 失败影响范围与授权边界

- `SectionSignalRuleRuntime.kt:280–299` 在执行前门控 section unavailable、未准备进路、无 active 已配路径和 unavailable downstream。脚本不能绕过这些条件开放保护信号。
- 普通 rule Exception 由现有 boundary 捕获，当前 signal 为 `Unavailable(reason)`、有效速度0；只沿依赖消费者上游传播（`318–326`）。下游 unavailable 跳过消费者 rule（`294–298`），旧许可值不会继续留在结果中。其他独立规则节点继续产生结果；错误不应按整个世界清零。
- 但未捕获的内部 Error、source callback/host publication 错误仍可能逃出串行 `SignalEpochRuntime.process`，使对应世界 epoch Stall（`SignalEpochRuntime.kt:23–27,178–205`）。JS 无限循环不抛异常则会阻塞该世界 callback；仅承诺 try/catch 不够。执行预算/取消/超时必须最终转换为上述可控局部失败，不能假定引擎已具备可靠抢占。
- `SectionOccupancyRuntime.kt:330–353` 完成整份规则波后一次发布 occupancy/signals/selection/indicators，再交模型和 changed declaration；规则内部 setter 不应分次公开半成品。
- 缺初始 baseline 不视为空闲；`SectionSignalRuleRuntime.kt:329–332` 要求首次齐全。stamp 代表本帧一致输入，非要求所有未变化输入每 tick 重算。

### 6. 真实性能：不能只写“只重算受影响节点”

- 地图 lookup 与已经编译数组索引是常数时间；输入构造、条件过滤和脚本遍历前视元素是 O(窗口长度 + continuation 条件 + 脚本实际工作)。物理路径编译的 sum P 在低频 prepare 发生；若为多个目标分别调用现有 compileDownstreamPath，会重复走共享前缀，不能假设自动数组天然一次线性编译。
- DAG 更新波成本约 O((A+E_A) log A)，只执行到达节点（`OneWayInvalidationGraph.kt:55–69`）；compile 使用 PriorityQueue、排序，不能描述成严格纯 O(N+E) Kahn。
- **现实现还有全量快照复制/排序成本**：`SectionSignalRuleRuntime.kt:238–239` 每波复制全部 states/resolvedSections；`SectionSignalSnapshot` 构造 `toSortedMap`（`163–174`）；`SectionOccupancyRuntime.kt:265,330` 和 `SectionOccupancySnapshot:57–69` 也复制/排序全部区间。因此整体波不是严格只与 A 有关。新增 JS 不应引入额外全图复制；是否优化现有复制属于独立风险与验证，不提前搭复杂存储框架。
- JS 不逐 tick 执行：SignalEpochRuntime 对无相关变化 source 跳过 handler（`178–187`）。无需把已有同帧物理占用计算再做一遍；首次 baseline 会执行全部规则，加载/预算需覆盖这个峰值。
- 此研究不含耗时基准、实服容量或引擎测试；不以离线 unit gate 证明 lookup 延迟或 JS 吞吐。

### 7. 模型输出还需要的 JS 特有校验

- 当前 model prepare 从静态 `occupiedOutput/clearLevels` 枚举并验证主/附属所有 variant（`SignalModelBinding.kt:54–59,73–95`）。动态 JS 可能返回原配置枚举外名称，需定义“脚本允许输出的外观集合/绑定模型兼容性”或等价运行期校验。
- 当前 unknown explicit variant 在显示提交处捕获并报告，不能反过来认定规则安全失效（`241–252`）。主会话需明确：非法速度/空 variant 一定信号0；模型缺失/显示失败是否仍只是显示错误。不能把模型名映射成速度。
- 主/附属显示都按主 signal ID 读同一输出；Unavailable 用包 default scenes（`255–256,245`），只更改实际差异（`246`），继续用现有 public model handle，不赋予 JS Bukkit/entity/world 写权限。

## Related specs

- `.trellis/spec/simple-signals/backend/index.md:370–443`：现有依赖图、rule 边界、距离/方向编译和具体固定闭塞 provider。
- `.trellis/spec/simple-signals/backend/protection-region-signal-selection.md`：固定/当前入口唯一请求选择及授权/净空门控；无默认进路。
- `.trellis/spec/simple-signals/backend/bidirectional-fixed-block.md`：两入口独立规则、同一物理 token/占用。
- `.trellis/spec/javarailways/simpleato/reactive-signal-epochs.md`：宿主串行 epoch、异常世界边界、changed-only source callback。
- 当前 task `prd.md` / `design.md` / `implement.md`：JS 尚未实现；用户本轮已选择 JS，距离配置表方案不再作为拟实施策略。

## External references (docs, versions)

- 无；本批明确不选择/比较 JS 引擎，不下载、不查询外部服务。仅静态读取现有源码。

## Caveats / Not Found

- 未发现 JS engine/loader、扫描最远距离定义、自动前视数组 builder 或硬预算执行设施；只存在 future JS provider 注释。
- 未读取 implement/check.jsonl：research 角色隔离禁止加载；主会话提供的 dispatch 顺序在此按角色约束收窄。
- 未改源码/spec、未执行构建或服务；此文件是证据和 TARGET 补项建议，不是实现/测试完成声明。
- 尚待确认数组字段及各终止原因语义、跨保护区前視规则、动态 variant 验证层与执行预算强制方式。引擎选择留给后续批准批次。
