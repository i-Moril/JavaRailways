# Research: distance inputs and cyclic signal-output dependencies

- Query: 完成实施清单尚未勾选的距离输入/循环信号依赖评审，核实当前能力、限制、成本及最小后续选择，不直接实现新规则策略。
- Scope: internal
- Date: 2026-10-06
- Baseline: 调查开始 HEAD `31df173b`；结束直接核实 HEAD `4021112e fix(simplesignals): restore unpublished schema version baseline`，分支 `dev/signal-system`。当前 SimpleSignals 定义格式是 schema 1。本研究没有修改版本、运行代码或测试。

## Findings

### 后续决定（2026-10-06，取代下文的待选分支）

用户选择JS实现距离敏感逻辑，并批准先写契约、伪代码及测试要求；不扩展距离条件配置表。
下文保留当时选项分析作为研究记录，不再将该选择描述为阻塞问题。
[JS草案](../../../spec/simple-signals/backend/js-signal-rules.md)尚未实现／冻结；完整环线预告仍属独立策略评审。

### CURRENT：距离已经如何得到并进入规则

生产代码以下统一前缀为 `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/`。

- `signal/FixedBlockSignalCompiler.kt:23` 的 `DirectedSignalGeometry` 借用对应正/反向 traversal 和长度，没有新建闭塞资源或监测区。
- `signal/FixedBlockSignalCompiler.kt:137` 的 `compileDownstreamPath` 从消费信号对应入口计算至目标信号对应入口的沿轨距离。
  `:160-162` 先取自己区间的长度及出口；沿已指定的连续通路加每条轨道尚未走完的弧长（`:170`），最后加目标轨道上的剩余入口距离（`:199`）。
  多出口道岔须有作者明确的 continuation；不是最短寻路，也不是世界坐标直线距离。
- 该结果保存为 `runtime/SectionSignalRuleRuntime.kt:80` 的 `DownstreamSignalDependency.distanceMeters`，单位米，从消费者信号起算；编译中同时记录真实路径 rail IDs 和所需道岔状态。
- `signal/ProtectionSignalCompiler.kt:125` 对每条候选保护进路复用同一编译器，分别派生距离和所需 continuation；反向固定信号目标使用上一批已实现的独立有向几何。
- `runtime/SectionSignalRuleRuntime.kt:302-315` 把已选中的有效路径依赖投影为 `DownstreamSignalView(signalId, distanceMeters, output, effectiveSpeedLimitMetersPerSecond)`。
  值类型见 `:43`；规则输入同时有 `controlledSection.lengthMeters`（区间观测含长度）及同周期 stamp。
  当前规则输入没有逐列车制动能力、任意世界 lookup 或 JS engine。
- 方向距离已有数值测试：`SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/signal/DirectionalFixedBlockSignalsTest.kt:54-61` 验证自己区间长 16m、两端下游距离各 20m；`:208-211` 验证 mixed protection/fixed 路径的对应距离。

结论：距离数据面不缺。不能再次加作者手填距离、另一个距离模型或每 tick 轨道扫描。

### CURRENT：生产具体规则确实不使用距离

`signal/FixedBlockSignalCompiler.kt:105-116` 的算法是：本区间有车则选择 configured occupiedOutput；
否则取所有当前有效 downstream 的最小**速度**，按 clear-level 的 minimumDownstreamSpeed 选择 output。
它不读取 `distanceMeters`、`lengthMeters`、依赖身份差异或列车制动参数。

保护信号的具体规则相同，见 `signal/ProtectionSignalCompiler.kt:139-146`；准备进路和资源/净空检查仍由既有选择器决定。
这是可配置的速度阈值规则，不是已经实现的距离自适应规则或通用脚本系统。
相关 owning spec `.trellis/spec/simple-signals/backend/index.md:409-424` 如实规定这一算法；
本次未发现“合同承诺使用距离但实现漏用”的已批准缺陷。

### CURRENT：短区间链举例

`S1 -> S2 -> S3 -> S4 -> S5` 的有向下游**读取**可以在现有 DAG 下工作；provider-to-consumer 的传播箭头反向为 `S5 -> ... -> S1`。
某个信号也可显式依赖更远的多个信号，不限于相邻一个；编译器会派生各自距离，条件由真实道岔分支筛选。

例如用户讨论过的 `S2(YG) -- S3(Y) -- S4(Y) -- S5(R)`，名字在这里只表示玩家配置的 modelVariant，
runtime 不解释 Y/YG/R。若 S2 同时依赖 S3/S4，但两者限速相同，当前 min-speed 算法把它们折叠为一个相同最小值。
再把 S3/S4 的入口距离从 30/60m 改成 300/600m，保持速度和阈值表相同，S2 的输出仍相同。
这是当前规则**无法自动因短区间查看更远风险并按距离区分输出**的具体限制。
它不意味着整个 ATO 没有制动：ATO 仍按现有入口限速、前车、停车点和授权做单次感知/制动约束。

如果某个配置主动把远方 R 也加入 min-speed 输入，其零速度会决定 minimumDownstreamSpeed=0 档；
这仍是作者给定档位选择，不自动计算“这段距离足够显示 Y 还是 YG”。
不能把增加几个依赖或改模型名冒充距离自适应实现。

### CURRENT：物理环线、路径循环与输出依赖循环是三件事

1. **物理轨道成环**：Basic/ATO 冻结路线可以有限次重复经过轨道/闭塞。既有真实资源测试
   `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockStopContinuationTest.kt:244`
   验证 degree-two 物理环线重复经过，同一 token 延长并在最后车尾净空后释放。
   这不证明循环信号输出规则可用。
2. **单条下游几何查找循环**：`signal/FixedBlockSignalCompiler.kt:162` 建 directed-rail visited 集合，`:195` 拒绝重复访问。
   有限的局部环线通路如果在重复 directed rail 前到达目标可以成立；找不到目标而转圈必须拒绝。
   特别是目标位于起始 directed rail 的后方、须绕圈再返回该 rail 的情形，也会被当前 visited 规则拒绝。
   不应笼统声称“任何两点绕环的距离都能计算”，也不能直接去掉 visited 造成无限 prepare。
3. **信号最终输出彼此循环读取**：`S1 reads S2; S2 reads S3; S3 reads S1` 不论物理路径各自能否验证，都是输出依赖环。
   `signal/ProtectionSignalCompiler.kt:89-94` 先检查全部 authored fixed/protection output edges，**在几何路径和 unavailable 过滤之前**拒绝循环。
   `runtime/OneWayInvalidationGraph.kt:112-113` 的 Kahn 排序无法覆盖全部节点时拒绝；自环还在 `:11` 直接拒绝。
   动态 turnout 当前只开放某一条分支不能绕过完整 authored graph 的环检查。

完全环状线路上三条独立信号若都只按自身占用输出、没有 output dependencies，当前可以编译；
但它们不会显示由前方多区间风险推导的逐级预告。
物理三轨环线上 S1/S2/S3 每个局部目标可达，也仍不能声明完整输出环。
人为断开一个依赖可形成 DAG，但断点会使用“无下游=末端最高档”语义，不能把这种不对称配置称为已经完整模拟环线预告。

### CURRENT：拒绝时点和状态原子性

`runtime/SimpleSignalsWorldRuntime.kt:601-608` 在完整 candidate prepare 时调用 mixed compiler，捕获 IllegalArgumentException 并返回 preparationRejected。
manager `:1267` 把此结果映射为 `VALIDATION_REJECTED`；edit permit 获取在 `:1358` 以后，持久化/source/publication 交换更晚。
因此输出环属于 load/authoring 阶段的有意禁止，不是运行时发现死锁后收紧，也不会先保存一半环状定义。
旧文件、当前 publication/source/模型及资源 claim 保持原样。
当前 unknown/暂失下游保持 Unavailable 的策略不改变这个环拒绝顺序。

已有 graph 专项测试 `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/runtime/OneWayInvalidationGraphTest.kt:74` 确认真正双向 output feedback 拒绝。
`signal/FixedBlockSignalCompilerTest.kt:65` 的 cycles 用例使用同轨道的线性 block fixture（`:274`），且只断言抛错；
不能据此声称已验证“物理局部路径均可达的完整三信号环在 production manager 拒绝且磁盘/source 原子不变”。
该更强的针对性测试本次未找到，可列为下一轮验证候选，不等于已复现生产错误。

### CURRENT：冷启动和变化成本

- 首次 `runtime/SectionOccupancyRuntime.kt:325` 要求完整 occupancy baseline；`runtime/SectionSignalRuleRuntime.kt:329` 要求每条 configured signal 得到 Available/Unavailable。
  未有 baseline 前，inspection 的 configured entry 为 0，不能凭缺状态开放。
- 图编译低频执行，当前 PriorityQueue Kahn/稳定排序成本有 `O((V+E) log V)` 上界；所有声明几何路径总规模 P 的验证为 O(P)。
- 普通变化 wave 只调度实际到达的 N 个节点和 E' 条边，传播为 `O((N+E') log N)`；每个规则还扫描自己的 continuation/downstream/level（O(c+d+l)）。
  共享 consumer 在本 wave 最多执行一次，相同 output 停止传播。相关 idle/unchanged 测试见 `runtime/OneWayInvalidationGraphTest.kt:16,35,55` 和 `runtime/SectionOccupancyRuntimeTest.kt:505`。
- **不能把整个 callback 说成只与 N 有关**：`runtime/SectionSignalRuleRuntime.kt:237` 复制 previous states/resolvedSections；
  `:242` 遍历 authored unavailable，snapshot `:169-175` 把完整 states/resolved map 排序冻结。
  S 个 signal 的复制/排序还有 O(S)/O(S log S) 开销；occupancy candidate 也复用现有全表复制。
  本次不借题重构快照或加入另一套缓存/epoch 框架。
- 新距离敏感 rule 如只消费已经给出的 d 个 view，额外计算最多是该规则 O(d)；不需要 lookup 世界或再扫描轨道。
  这是静态分析，不是实际延迟或 Folia 性能实测。

### TARGET / 未批准：最小后续路径与选择

**建议先保留 DAG，距离与完整输出环分开处理。** 本次研究完成的是明确列出的评审；没有授权增添 runtime 算法。

最小的距离-only 实现候选应只扩展当前具体 rule 的**策略**，从现有 `lengthMeters` 和 `(distanceMeters, speed)` 选 configured output；
不改变 inspection、Basic grant、ATO 动态制动曲线、模型 vocabulary、资源或世界扫描。
可以选择仍用配置表但允许依据距离选档，也可以等后续自定义规则 provider/JS 工作。
两者不是同一个已批准功能；当前没有脚本加载器，不能说“玩家写 JS 就已经可用”。
不应在用户选择前添加距离字段、刹车常数或另一个 table 类型；距离阈值与制动能力并非能由现有最小速度阈值表无歧义自动推导。

该候选批次的最小测试集：

- 距离/长度进入真实 rule input 的精确值，跨 rail、反向和保护进路目标；相同速度但不同距离能按已选策略给出不同 output。
- 连续短区间至少检查两个后继，长区间与短区间边界；模型名始终 opaque，输出由配置决定。
- 缺 baseline/无活动路径/非法 rule output fail-close；只有相关输入变化重算，unchanged 停传播；DAG cycle 仍拒绝。
- 若改变配置 shape，同步 current fixture/codec/编辑原子性，但保持当前未发布 schema 1；不自动升版。

**完整环线预告是独立策略扩展，不是距离-only 的顺便修复。** 若既要无延迟、无 fixed-point，又希望所有信号完全对称，
不能继续让每个信号都互读下一信号的最终输出。
一种可能的未来策略是：信号直接读取预编译、声明最远距离内的原始区间占用/可用状态，自身计算；
section provider 到 signal consumer 仍可保持 DAG，资源/占用仍只有现有一份。
这不增加逐列车 ATO 扫描，但当前 rule input 只暴露自己的 section；`binding.observedSections` 虽能触发图更新，
未把全部 observed section 值交给规则，因此仍需新的小型输入/策略合同，不能宣称当前接口已支持。
包含保护区域时还须选择消费哪项合法授权/准备状态，不能把“未占用”当作可开放。
在未批准该策略前，不设计 section 预告缓存、循环求解器、扫描字段或整个框架。

用上一 tick 输出打破环会引入多周期传播、冷态及过期宽松输出问题；当前事件源无变化时不会持续反复求值，
不能把它当作免费的一次循环遍历，也不能无声增加轮询。此方案不建议作为最小修复。

上述策略选择已由用户确认：使用JS，不扩展距离条件配置表，先审查所属契约再实施。
本批保持输出 DAG；若另要完整循环预告，需要独立明确批准原始区间输入等策略，不混入此次JS契约批次。

### Files found / Related specs

- `.trellis/tasks/09-18-signal-addon-implementation/research/signal-comparison-corrections.md`：三个修复完成、距离及循环剩余评审的明确边界。
- `.trellis/tasks/09-18-signal-addon-implementation/implement.md`：本次评审项及完成记录；当前状态以实施清单为准。
- `.trellis/spec/simple-signals/backend/index.md:373-445`：既有 DAG、距离几何、规则输入/速度阈值和更新合同。
- `.trellis/spec/simple-signals/backend/bidirectional-fixed-block.md`：已完成独立方向规则及真实入口身份。
- `signal/FixedBlockSignalCompiler.kt`、`signal/ProtectionSignalCompiler.kt`：生产距离编译与距离未使用的规则。
- `runtime/SectionSignalRuleRuntime.kt`、`runtime/OneWayInvalidationGraph.kt`、`runtime/SectionOccupancyRuntime.kt`：规则输入、完整结果、DAG/冷态与更新路径。
- `runtime/SimpleSignalsWorldRuntime.kt`：低频候选拒绝及许可前的原子失败边界。
- 上述命名测试 files：当前证据与下一批 targeted tests 的归属，不代表本次已执行测试。

### External references

无。此评审是内部源码/已批准契约审查，不涉及最新日本铁路规则或线上库 API。

## Caveats / Not Found

- 没有构建、测试、服务或 Git 写操作；仅在指定 research 文件写入本报告，保留所有其他会话及用户脏文件。
- 距离使用和完整循环输出策略均未实现、未获本次实现授权；不能将当前有意 DAG 禁止误报成缺陷。
- 未发现现有具体规则按距离自动选档、可供玩家使用的 JS loader，或上述更强的 production 环状原子性专项测试。
- 参数、API、schema 字段、扫描/仲裁框架均没有新增；策略提案保持 TARGET。
