# Basic / Core 工程审查临时记录

> 状态更新（2026-10-19）：下文51项保留发现时的证据与行号，不代表当前均未修复。用户确认后的修复已提交；见文末“提交与剩余范围”。本轮只有静态验收，没有运行测试或构建。

- 日期：2026-10-19（UTC）。
- 仓库：`/workspace/minecraft/JavaRailways`；分支：`dev/signal-system`。
- 第一轮基线：`8f156900413e38e45e24f064c2b9dba889c7bf8c`。
- 第二轮最终核对基线：`9ed278181a577ebc1450bd307fe466d47ed91f96`。
- 第三轮工程专项基线仍为 `9ed278181a577ebc1450bd307fe466d47ed91f96`；新增条目为静态源码与调用关系审查，没有重跑前两轮复现。
- 本轮没有创建 Trellis 任务、修改业务代码或提交。工作区已有的任务文档修改不属于本次记录。
- 以下共 51 项，包含行为缺陷和工程维护性判断；第三轮新增 12 项，并补充 BC-10 的具体依赖证据。均未由本次审查实施修复；第一轮条目按当时证据保留，不以本记录代替修复前的现状复核。

## 证据等级及使用方式

- **源码重编译局部复现**：目标源码重新编译；部分依赖使用现有构建产物。不是全仓构建或服务器验证。
- **现有产物局部复现**：运行已有编译类，并核对当前源码；不能据此宣称最新 HEAD 全量构建通过。
- **静态确认**：有源码、调用关系和具体推导，未运行对应复现。
- **结构建议**：维护性判断，不等于运行时故障。
- 注入无效输入或插件异常的复现只证明边界行为，不证明正常生产路径已经触发。
- 路径相对仓库根目录；行号来自对应审查时点，后续变更后需重新定位。

## 第一轮发现

### BC-01 — Core：Tick 指标在对象注销后持续保留

- 位置：`Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt:48`、`:205`。
- 证据：源码重编译局部复现。
- 问题：注销清理 tickingObjects 和 pendingCommits，却不清理 asyncObjectStates；无过期或容量限制，每条状态保留 100 个历史样本。
- 真实调用：动画及延迟效果反复创建、注册、注销短生命周期对象。
- 复现：100 个对象全部注销后，仍可取得 100 条指标。
- 影响：长期积累指标内存，排名混入已退出对象。
- 建议：注销时清理；确需历史诊断则明确保留容量或过期策略。

### BC-02 — Core：跨事件继承层级的优先级失序

- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/event/processor/TypedRailEventBus.kt:95`。
- 证据：源码重编译局部复现。
- 问题：外层遍历事件类型，内层才遍历优先级，违背公开的 HIGHEST → LOWEST 顺序。
- 复现：`[concrete LOWEST, base HIGHEST]`。
- 建议：按优先级遍历全部匹配类型，或汇总匹配监听器后稳定排序；补跨层级测试。

### BC-03 — Basic：配置提交静默跳过未注册项并清除 dirty

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/impl/config/ConfigUIRegistry.kt:359`。
- 证据：源码重编译局部复现。
- 问题：找不到 key 时跳过，最后清空全部 dirtyKeys；构建事件允许动态添加未进入全局注册表的配置项。
- 复现：动态项编辑提交后 `global=null, pending=false`。
- 建议：明确动态项提交归属；提交失败应反馈失败并保留未提交状态。

### BC-04 — Basic：动态项依赖遗漏在刷新触发集合之外

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/api/ui/config/ConfigUI.kt:198`。
- 证据：源码重编译局部复现，并追踪 Bukkit DialogRenderer 消费。
- 问题：triggerKeyIds 只遍历原始 group，不包含构建事件添加的项与分组。
- 复现：动态项声明依赖 control，触发集合仍为空。
- 建议：从本次完整候选树捕获依赖，包含可能因输入变化而显示的隐藏项。

### BC-05 — Basic：ConfigItem 抽象与写入实现不匹配

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/impl/config/ConfigUIRegistry.kt:141`。
- 证据：源码重编译局部复现。
- 问题：注册接受 ConfigItem，但写入仅支持 ConfigItemImpl；其他实现被当作未注册并替换索引，分组仍持有旧对象。
- 复现：同次写入后 registry=new，group=old。
- 建议：统一值状态 ownership，或明确限制注册类型；不要静默替换。

### BC-06 — Basic：尾释放保留第二套无生产调用的扩展路径

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/LiveClaimTailRelease.kt:183`；`RouteGrantTailRelease.kt:113`（同目录）。
- 证据：静态确认、全仓调用搜索。
- 问题：installCommittedSuffix/validateCommittedSuffix 及包装仅测试调用，另维护 committedQueueTail。生产使用 stageExtendedHolds → validateExtendedSuffix → installExtendedSuffix，两套排序约束不同。
- 建议：移除旧入口及专属状态，将有效断言迁到实际生产扩展生命周期测试。

### BC-07 — Basic：内部封闭集合使用开放接口与运行时兜底

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/CompiledRouteAdmission.kt:13`；`InfrastructurePreparationActuator.kt:73`（同目录）。
- 证据：静态确认。
- 问题：InfrastructurePreparationRequirement 仅有两种生产实现，但使用普通 internal interface；未知类型先 error，再捕获成 Rejected。测试还虚构第三种实现验证此分支。
- 建议：sealed interface + 穷尽 when；真实插件扩展仍通过现有 descriptor/provider 表达。

### BC-08 — Basic：核心规划算法测试归属下游模块

- 位置：`SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/routing/TopologyRoutePlannerTest.kt:21`。
- 证据：静态确认。
- 问题：12 个直接验证 Basic 规划器的测试仅使用 Basic/Core/JUnit 类型，却放在 SimpleATO，执行 :Basic:test 不会运行。
- 建议：移入 Basic；SimpleATO 保留适配与运营规则组合测试。

### BC-09 — Core：CoreWorld 职责集中（结构建议）

- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt:330` 起的拓扑准备逻辑及整类。
- 证据：职责及状态边界分析，不是按行数判错。
- 问题：运行时装配、列车索引、拓扑准备/提交、占用/帧发布、存档恢复集中在一个类中。
- 建议：优先提取拓扑准备与验证的内部协作者；锁、状态及提交权仍保持单一归属，避免增加任意 setter。

### BC-10 — Basic：RailCurveFactory 混合多类几何职责（结构建议）

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/curve/RailCurveFactory.kt:124` 及整文件。
- 问题：模板选择、并行偏移、Hermite 求解、超高梯度限制、端点验证混合。
- 建议：按独立几何算法拆内部实现，不新增没有实际需要的通用策略框架。与 BC-20 的旧实现清理分别处理。

## 第二轮新增发现

### BC-11 — Core：车厢恢复读取旧 direction 计算新 normal

- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreCar.kt:686`。
- 证据：目标源码重编译局部复现；其余依赖使用已有 Core/testFixture 产物。
- 调用链：SavedTrainV1.load → CoreTrain.restorePersistedPlacement → CoreCar.restorePersistedState → publishOwnerChanges。
- 问题：direction 赋值写 active slot；下一行 projection(direction) 却读取 published slot，加载时仍为默认方向。
- 复现：45°坡道 direction=(0.7071,0.7071,0)，normal=(0,1,0)，点积 0.7071，应为 0。
- 建议：恢复复用既有纯几何求解，再统一写缓冲；最小修复用局部新方向计算法向量。增加加载前后方向、法向量及正交性断言。

### BC-12 — Core：TrainSnapshot 的单 facing 参数丢失尾部本地方向

- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/physics/train/TrainSnapshot.kt:33`。
- 证据：目标源码重编译，使用真实 Core world/train 局部复现。
- 复现：列车跨反向轨道接缝时 actual head=AB, tail=BA；snapshot head=AB, tail=AB。
- 生产调用：TimetableDispatcher.kt:2745 从 head 取 facing 传入。MissionRouteLayout.project 严格匹配本地方向，Dispatcher 的尾部投影失败后保留旧进度。
- 建议：从完整 published frame 转换，保留两端独立方向；单个 facing 参数不能表达此领域状态。
- 边界：已复现快照字段错误，未完整运行 Dispatcher 场景。

### BC-13 — Core：反向曲线保存静默丢失反向语义

- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/rail/curve/ReversedCurve.kt:65`。
- 证据：曲线目标源码重编译局部复现。
- 问题：save() 直接 originalCurve.save()；CoreRail.save 直接使用其结果。
- 复现：10 米反向直线起点 x=10，save/load 后起点 x=0。
- 关联工程问题：Basic VerticalCurveRebuilder.reversedCopy 通过重新采样绕过此边界，底层责任泄漏到上层。
- 建议：统一按当前曲线方向保存，或在类型上隔离不可持久化的查询视图。
- 边界：未复现完整建轨—重启链路。

### BC-14 — Basic：限速 metadata 暴露可变列表别名

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/rail/RailSpeedMetadata.kt:22`。
- 证据：现有产物局部复现，核对当前源码。
- 问题：原样保存传入 List；store 原样保存 metadata，snapshot 仅复制 map，signal 同样持有该值。
- 复现：外部清空构造用 ArrayList 后，原值及既有 snapshot 的限速从 10.0 变为 null；追加同类型两项绕过唯一性检查。
- 建议：值边界防御性复制，暴露不可变列表，并覆盖 serializer/copy 的构造路径；不要在多个消费者各补复制。

### BC-15 — Core：扩展持久化 ID 冲突静默覆盖

- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreCar.kt:769`、`CoreBogie.kt:149`、`CoreTrain.kt:1356`（同目录）。
- 证据：当前 CoreCar 源码重编译局部复现。
- 问题：三处收集器直接 savedExtension[id]=payload；不同 plugin slot 不保证保存 ID 不冲突。
- 复现：不同插件 slot 0、1 返回相同 ID，保存结果仅剩 second，数量为 1，无报错。
- 建议：统一收集逻辑，重复 ID 明确拒绝并报告宿主及冲突来源。未找到允许后写覆盖的合同。

### BC-16 — Basic：RailObject 注册异常留下半注册状态

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/provider/RailObjectStore.kt:21`。
- 证据：现有产物局部复现；使用主动抛异常的公开 RailObject 实现注入 provider 故障。
- 问题：先写 byRef，再调用 location.rail.id getter 建第二个索引；失败时不回滚。
- 复现：register 抛错后 stillRegistered=true；重试报 already registered。
- 调用证据：Station 注册在 store.register 返回后才加入调用方回滚列表，失败对象不在其中。
- 建议：先捕获验证外部输入，再修改索引；注销也应使用已保存的注册信息，避免写到一半执行外部 getter。
- 边界：正常 StationBerth 位置固定；未发现正常对象移动导致索引错误的实际路径，不把该猜测计入发现。

### BC-17 — Core：采样点校验未保证插值 frame 可用

- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/rail/curve/PrimeCurveImpl.kt:38`、`:168`。
- 证据：曲线目标源码重编译局部复现。
- 问题：相邻切线分别合法但相反，中点线性插值为零，normalize 产生 NaN；相反 normal 也有同类风险。
- 复现：构造成功，中点 tangent=(NaN,NaN,NaN)。
- 建议：在构造/decode 边界验证区间适用当前插值算法，或明确更换插值策略；不增加每 tick 全量扫描。
- 边界：未证明常规 Basic 建轨器生成此输入。

### BC-18 — Basic：辅助 ManualController 使用上帧速度移动

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/controller/ManualController.kt:67`；`Core/src/main/java/work/art1st/javarailways/core/api/control/TrainController.kt:130`。
- 证据：静态确认。
- 问题：子类写 active speed 后 super.tick 读取 published speed。本帧速度更新与本帧移动不一致。
- 推导：静止、加速度 1m/s²、1000ms tick，发布速度 1m/s，但移动使用旧 0；制动也落后一帧。
- 范围：BukkitPluginDev 命令及部分测试使用；正式人工驾驶的 RealisticManualController 不走此路径。
- 建议：显式计算并提交同次 step 的距离/速度；明确辅助策略归属，避免以它验证真实动力学。

### BC-19 — Core：minorDeltaS 遗留为第二份位置状态

- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/rail/RailLocationWithFacing.kt:24`、`:46`、`:192`、`:203`。
- 证据：静态推导，未执行复现。
- 问题：正常构造/移动均写零，load 却保留非零 minorDeltaS；position、arcLength、reversed、save/load 使用不同位置含义。
- 推导：长度 10、point arc=2、minor=0.1，反向弧长为 7.8 而非 7.9；AB save/load 后 arc=2.2。
- 建议：当前业务模型只保留一个精确弧长；明确拒绝或按政策迁移旧输入，不散布兼容余量状态。
- 边界：未发现当前正常生产路径生成非零 minorDeltaS，不声称普通运行已发生漂移。

### BC-20 — Basic：曲线旧算法与无用 facade 继续由测试维持

- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/curve/StandardRailCurve.kt:232`、`:255`、`:422`、`:429`；同目录 `CurveEditor.kt:3`。
- 证据：静态确认、调用搜索。
- 问题：旧 Euler positionAt/pointAt 与生产使用的 Gauss 积分 positionAtAccurate/pointAtAccurate 并存；旧入口仅测试直接消费。旧 positionAt 对 step=0 无校验，s>0 时循环不推进。
- 关联冗余：Builder 的 build/buildAndGenerate 系列仅测试消费，生产依赖其常量；顶层 SuperelevationTransition 与嵌套 enum 重复且无消费者；CurveEditor 是无引用空类。
- 建议：统一生产求点实现，迁移有效断言后清理旧入口、重复类型和空类。不仅为减少文件数而重构。

## 尚未列为正式发现的候选与排除项

- TrainController.setCommand 对负值静默 return 并保留旧命令：只找到测试调用，作为低优先级遗留 API 候选，未计入 20 项。
- TickManager 指标 getter 若在 finally 中抛错，可阻止 countDown：尚未找到真实 generic tick 生产触发者，未声称发生死锁。
- CoreWorld.removeTrain 接受其他 world 的 train 可影响其 car ownership：生产 caller 均使用正确 owner；作为公开 API 边界候选，未计入正式发现。
- planner/input/result 位于 basic.route 符合现有 API 边界规范，已撤回“包位置错误”的候选。
- 准入与拓扑事务中的宽 catch 多数明确保留资源或 stall，不凭 catch 范围判定吞错。
- 构造失败时调用方 car ownership、部分 extension 回滚及 close/quiesce 限制有既有合同，不把明确的边界限制冒充新缺陷。

## 已检查范围与剩余空白

### Core

- 深入：CoreCar、CoreBogie、TrainPersistence、TrainCoupling；CoreTrain 构造、摆位/恢复、移动、结构准备/提交、换向、销毁、控制器与保存路径。
- 深入或重点追踪：TickManager、事件派发、PhysicsCoordinator 生命周期/帧间操作/发布链、extension 容器与 codec、CoreWorld 拓扑事务关键段、曲线与两种 RailLocation、RailView、RailTopologyEditor。
- 不代表完整验证：所有碰撞组合、数值算法数学完备性、关闭/取消/异常的全排列、平台 extension 资源释放、真实多世界并发压力。
- 空间索引、拓扑构建与 preparation plan 相关文件未逐一做完整算法审计。

### Basic

- 深入或重点追踪：准入 engine/runtime、准备执行器、claim/tail release、资源 registry、路线规划与投影、occupancy tracker、配置 UI、控制器、metadata/store、方向/限速 materializer、安全监测及持久化。
- 曲线侧深入 StandardRailCurve、VerticalCurveRebuilder、测量与 ConstraintSolver/Factory 调用链。
- Anchor、parallel geometry、partition、smoother 主要做入口和调用搜索，未完成逐行数学审查。
- 尚未系统验证所有 resource preparation/cancel/release 故障组合与第三方扩展行为。

### 测试与运行证据

- 第一轮 241 个生产 Kotlin 文件的扫描仅表示结构/异常入口搜索，不表示逐文件审查完成；之后仓库新增了运行时文件。
- 未执行完整 Gradle 测试、服务器/Folia 验证或压力测试。
- 局部复现不证明整个模块正确，也不能证明其他输入、时序或下游集成没有问题。

## 是否可能还有未检查出来的问题？

**可能，而且目前没有依据声称已经找全。** 已确认的是本记录所列问题与证据；不能把尚未发现的问题当成既成事实，也不能把“某段没发现问题”当成正确性证明。

后续建议按以下顺序补齐覆盖，而不是继续随机搜索关键词：

1. 双缓冲所有写入后读取路径：构造、恢复、换向、结构提交、停机；BC-11/18 显示同类误用已重复出现。
2. 快照、持久化和值对象边界：方向、单位、可变别名、重复身份、失败前是否已经写入状态。
3. 曲线/轨道输入和拓扑事务：退化 frame、非法数值、非零遗留偏移、嵌套失败及回滚。
4. 资源准入状态机：prepare/cancel/release 与 topology invalidation 的交错及插件异常。
5. 生命周期与并发：新 WorldExecutionOwner、关闭和 worker 退出、跨世界组合操作；必须以最终稳定提交为基线。
6. 测试有效性与结构收敛：测试是否覆盖生产入口、辅助实现是否偏离真实实现、职责提取是否保持单一状态 owner。

## 临时复现文件索引

以下为会话中的本地临时文件，不是提交进仓库的回归测试，可能被系统清理：

- `/tmp/core-engineering-audit/Probe.kt`：第一轮指标生命周期、事件优先级。
- `/tmp/jr-config-audit.kt`：第一轮配置提交、依赖与自定义 ConfigItem。
- `/tmp/core-engineering-audit/RestoreProbe.kt`：坡道恢复法向量。
- `/tmp/core-engineering-audit/FacingProbe.kt`：真实列车跨轨两端方向。
- `/tmp/core-engineering-audit/ExtensionCollisionProbe.kt`：扩展保存 ID 冲突。
- `/tmp/jr-curve-second-audit/Probe.kt`：反向曲线保存、中点 NaN。
- `/tmp/BasicMetadataAudit.java`：限速列表别名。
- `/tmp/BasicRailObjectAudit.java`：注册失败残留。


## 第三轮：按工程要求补充审查

本轮重点不是再找数值错误，而是错误合同、抽象成本、文件职责及同类代码的一致性。以下均为静态审查；设计建议不等于已经发生的运行故障。没有修改业务代码、创建任务或执行完整测试。

### BC-21 — Core：错误的事件处理函数签名被静默忽略

- 类别：无效错误处理／应在注册边界失败。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/event/processor/TypedRailEventBus.kt:38`。
- 已标记 HandlesEvent 的函数，如果参数数量不正确或参数不是事件，直接 return@forEach；最终仍返回正常注册句柄。插件编程错误因此表现为“注册成功但永不响应”，失去启动时可定位的错误。
- 建议：对畸形签名报告包含类名、方法名的注册错误；先完成签名验证再安装 handler，保持注册原子性。合法事件属于另一条总线时可以跳过，不能把跨总线 listener 当作非法；运行期间隔离 handler 异常也应保留。
- 当前 event-bus 规范描述了静默忽略行为，本项是建议调整该设计，并非声称违反现行规范。

### BC-22 — Basic：配置类型重复表达，泛型安全转换并不能实现所暗示的兜底

- 类别：抽象缺口／误导性错误处理。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/api/ui/config/ConfigKey.kt:24`；`Basic/src/main/java/work/art1st/javarailways/basic/impl/config/ConfigUIRegistry.kt:131`。
- T、defaultValue 和 valueType 是相互独立的输入，构造器允许 String 默认值配 NUMBER；key 相等性只比较 id。registry 的非 reified 泛型 `as? T` 在类型擦除后不能验证实际 T，同 id 不同类型读取不能可靠返回 null/default，而会在调用方转换时失败。
- 下游 DialogRenderer 按 valueType 再次转换，错误类型可能变成 false、最小值、空字符串，缺少 EnumOptions 则不生成控件。错误声明被转成看似正常的 UI。
- 建议：在定义／注册边界统一值类型和控件提示，明确验证 id 对应类型；让读取依赖已验证的合同，或携带真实类型检查能力，不把未经检查的泛型转换伪装成安全 fallback。不需要另建通用类型框架。
- 与 BC-05 不同：BC-05 是 ConfigItem 实现可替换性，本项是 key/value 类型合同。

### BC-23 — Basic：曲线结果的可空字段允许互相矛盾的状态

- 类别：不合理抽象／不一致的结果处理习惯。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/curve/RailCurveFactory.kt:36`、`:129`；消费端 `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/RailWand.kt`。
- RailCurveBuildResult 和并行结果用独立 nullable curve/error 等字段表达成功失败，公开构造和 copy 允许“成功带错误”“失败无原因”。isSuccess 不提供 Kotlin 类型收窄，所以工厂及上层反复使用 `!!`、`listOfNotNull`、缺失错误文本兜底。
- 同包 ConstraintSolveResult、ParallelAnchorMatch 已使用 sealed 成功／失败，类似合同采用不同编码习惯，调用者需要记住两套规则。
- 建议：各自使用小型 sealed 结果，成功持有非空值，失败持有原因。无需为了两种结果再造通用框架。当前规范记录 nullable 形状，不代表其维护成本合理。

### BC-24 — Basic：一个 PreparedDynamicsChange 混合两个准备阶段

- 类别：抽象边界不合理／必需字段可空。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/control/ConsistDynamics.kt:17`、`TrainDynamicsExecutor.kt:50`、`HandleTrainActuator.kt:56`；消费者 `SimpleATO` 的 AbstractATO 初始化。
- executor 准备 profile/update，actuator 再补充制动减速度和反应时间；两阶段复用同一类型，后两字段 nullable。但完整 actuator 结果的消费者立即 requireNotNull。
- 接口允许新实现返回一个消费者立即拒绝的“准备成功”结果；必需合同留给调用者记忆。
- 建议：内部绑定准备结果与 actuator 完整结果分开，完整结果的制动参数非空；用简单组合即可，保留已有具实际用途的安装 token。

### BC-25 — Core：渲染 trait 继承链保留已经迁移的状态职责

- 类别：过度抽象／文件与职责归属／过时语义。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/render/DynamicRenderable.kt:6`；`Core/src/main/java/work/art1st/javarailways/core/util/trait/render/DynamicRenderableTrait.kt:4`、`ExtendableRenderableTrait.kt:11`；`Core/src/main/java/work/art1st/javarailways/core/util/trait/TrainComponentTrait.kt:7`。
- 多层 trait 现在主要保存 lastState、组装初始值、遍历扩展初始化／销毁；泛型宿主依靠 `this as T`，没有真实自类型约束。初始化职责藏在 render 继承链中。
- lastState 只有构造赋值，没有逐帧更新；真实读取剩平台渲染帧建立前的位置兜底。注释仍描述不存在的 dirty/commitEvent 逻辑，公共可写“上一状态”与现行不可变帧发布协议脱节。
- 建议：明确初始渲染状态归属，共享扩展操作用显式传 host 的小函数收敛；不要为了名称补一套逐帧 lastState 更新，更不要删除有真实跨线程用途的 frame 抽象。

### BC-26 — Core：无消费者的 after-commit 队列保留误导性阶段 API

- 类别：无用抽象／命名与实际合同不一致。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/util/tick/TickManager.kt:11`；`Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt:28`、`:138`、`:159`。
- scheduleMainThreadAfterCommitTask 全仓只有声明、实现和测试 fake override，没有实际调用；却保留专用队列、drain、计时和替身接口负担。
- 实际该队列在 postTick 内处理，宿主随后才 drainPendingCommits 并执行平台提交。规范甚至需要解释历史名称不代表平台 commit 完成。
- 建议：删除没有业务所有者的阶段；真实 postTick 注册注销队列与提交 mailbox 保留。若将来需要平台提交后回调，应由拥有实际完成时机的宿主提供。

### BC-27 — Core：控制器基类混入仅测试使用的命令解释器

- 类别：职责过载／测试工具进入生产 API／虚假依赖。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/control/TrainController.kt:25`、`:47`、`:130`；同包 `ControlCommand.kt`。
- setCommand 调用只在测试；正式手动控制器和 ATO 覆盖 tick，自行计算并调用 moveControlledTrain。基类仍承担 pending command、命令完成和非法命令等整套策略。构造参数 world 完全未被读取，却要求所有子类传入。
- 建议：基类保留真实共享的协调、方向及发布机制，测试定速／分步命令放入 fixture；删除未使用参数和无生产消费者的命令解释协议。此项是职责问题，不重复 BC-18 的速度缺陷。

### BC-28 — Basic：内部结果模型保留不会产生的错误分支

- 类别：无效错误处理。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/TopologyRouteProjector.kt:72`；同目录 `RouteBoundResourceCatalog.kt:44`、`PhysicalRouteResourceCompiler.kt:76`、`RouteResourceAdmissionEngine.kt:333`、`:668`。
- projector 的拒绝均为 INVALID_ROUTE，却声明 STALE_TOPOLOGY 并让两处 engine 维护映射。catalog 的 InvalidRoute 也只有声明和消费，没有构造点。
- 这些是当前封闭内部实现中无法到达的分支，增加读者对恢复／重试路径的错误预期，也扩张穷举处理成本。
- 建议：删去无生产者的变体及映射。其他层确实检测拓扑失效的 STALE_TOPOLOGY 必须保留，不能按名字全局删除。

### BC-29 — Basic：过渡 compiler 入口已无独立消费者，却重复验证

- 类别：遗留抽象／多余入口与重复防御。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/PhysicalRouteResourceCompiler.kt:56`、`:88`。
- compileResolved 注释称提供给已查询 hits 的调用者，但全仓只被本类 compile 调用；两层重复相同 routeEnd/range 验证。Compiled.controlledResources 只有测试读取，实际生产消费 admission。
- 建议：收敛唯一入口，把算法实现设为私有并明确已验证输入，移除无人需要的结果投影。保留外部输入边界真正需要的校验，不能以去重为由一并取消。

### BC-30 — Basic：完整动力学预演放在 comparator 中反复求值

- 类别：不合理工程实现／同一函数内策略不一致。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/control/HandleTrainActuator.kt:131`；同目录 `TrainDynamicsExecutor.kt:73`、`:99`。
- 稳速分支在 compareBy 中调用 dynamics.preview，比较时反复计算当前最优候选；N 个候选通常需要 2(N−1) 次预演。每次还构造车厢输入并运行动力学。同函数加速分支已经先 map 结果，避免重复求值。
- 现行纵向动力学规范要求每个候选当前 tick 只预演一次。
- 建议：先计算候选及预测速度，再比较普通字段，保留 tie-break 顺序。这里没有声称已测得卡顿，也不建议引入跨 tick 缓存。

### BC-10 补充：具体的错误依赖方向与重复工具实现

- `Basic/src/main/java/work/art1st/javarailways/basic/curve/ConstraintSolver.kt:257` 的 computeFrame 是基础 tangent/frame 操作，却归属特定约束求解器；`RailCurveFactory.kt:993` 的 roll 旋转也属于基础几何。
- AnchorGeometry、VerticalCurveRebuilder、RailGeometryMeasurements、safety/RailSpeedCalculator 和 StandardTurnoutFactory 为这些小工具依赖高级 Solver/Factory，纵断面重建甚至同时依赖两者。
- ConstraintSolver:134 又有一份 Rodrigues roll 公式，与 Factory 的实现存在弧度／角度和修改／复制输入差异。修改阈值或法向处理需要同步两处及不同使用约定。
- 建议提取小型内部 frame/roll 运算，统一单位和输入不变合同，让高层算法单向依赖它；不需要策略接口。此证据比“文件过长”更直接，原 BC-10 的拆分建议据此收窄。

## 工程维度索引与剩余覆盖

- 无效错误处理：BC-03、07、21、22、28、29。
- 过度／缺失抽象：BC-05、06、19、20、22～27、29。
- 文件／包／职责结构：BC-08、09、10、25、27。BC-09 仍为结构建议，没有因文件较大而认定必须机械拆分。
- 编码习惯一致性：BC-15 的重复保存逻辑、BC-20 的重复枚举与实现、BC-23 的结果表示、BC-30 的候选求值方式，以及 BC-10 的单位和可变性约定。
- 本轮深入了事件注册签名、配置类型合同、曲线结果消费者、基础几何依赖、动力学准备与选择、渲染 trait、调度阶段、控制器基类、资源投影结果和 compiler 入口。
- 尚未逐文件完成包结构、公开可见性、异常政策、所有边界验证与第三方扩展合同的一致性检查；并发关闭、取消／释放故障矩阵及真实服务器行为仍未验证。当前记录不是已找全问题的证明。


### BC-31 — Basic：贡献校验分散，内部合并错误被归咎于插件

- 类别：缺少合理共性提取／错误捕获边界过宽／职责分散。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/RouteResourceAdmissionEngine.kt:376`、`:425`、`:703`、`:722`；同目录 `CompiledRouteAdmission.kt:68`。
- 普通请求与换向请求各自维护 collect、owner 映射、范围与身份检查、merge 和异常转换；withContributions 又做去重，并在编译值对象的方法内部调用外部 validator。
- 为捕获 validator 异常，Engine 包住整个 withContributions；内部 footprint 构造和 create 不变量失败也被转换成 INVALID_CONTRIBUTION，并诊断为 resource owner validation failed。这是内部错误与外部输入拒绝的具体混淆，不是泛指 catch 都不合理。
- 建议：归一公共贡献认证／唯一性／范围验证，普通请求特有规则仍留原入口；合并函数只处理已验证值，外部回调异常只在调用处捕获。保持 Engine 为唯一状态与提交 owner，不把整个状态机拆成框架。

### BC-32 — Basic：Registry 私有接口没有变化轴，身份检查后再比较同对象字段

- 类别：不必要抽象／永远不能失败的检查。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/InfrastructureCoordinationRegistry.kt:73`、`:91`、`:252`、`:565`、`:602`、`:740`。
- Stored 私有接口仅有 ClaimHeld 一个实现；blockers 的两个调用都传 claims.values 和同一 LIVE_RUNTIME_CLAIM 常量，没有真实多态变化。
- sameHeld 先验证 `first === pending.after`，然后比较同一对象的 platformOwner、owner 两个 val，这两项不会增加任何保护。末尾 mutable footprint 检查才有独立意义。
- 建议：直接接收 ClaimHeld 集合，固定 claim blocker kind；保留对象身份与 footprint 校验，删除无效字段自比较。

### BC-28 补充：稳定引用迁移后还残留无效 revision 合同

- `Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/InfrastructureCoordinationRegistry.kt:31` 的 claim request topologyRevision 只做非负校验，acquire 不读取；`:207` 的 beginAppendClaim 同名参数完全未使用。
- `Basic/src/main/java/work/art1st/javarailways/basic/api/infrastructure/InfrastructureDependencies.kt:54` 的 dependency request revision 不参与校验或 snapshot；对应 mutation STALE_TOPOLOGY 也没有产生点。InfrastructureMutationStatus.STALE_TOPOLOGY 同样无产生来源，却仍保留消费映射。
- 这不是建议恢复全局 revision 拒绝：当前稳定引用合同允许无关拓扑修改，应删除已经取消规则的接口残留。真实 edit-permit revision、catalog freshness 和路线依赖失效检查必须保留。

新增 BC-31 补充错误处理、共性提取和职责结构维度；BC-32 补充过度抽象与无效防御维度。


## 第四轮：扩大包与接口职责审查

基线仍为 `9ed278181a577ebc1450bd307fe466d47ed91f96`。用户指出 infrastructure 和 trait 是示例，不限制原始审查范围。本轮同时审查配置、route 归属、rail/safety/observation、actuation、Core 扩展容器与删除接口。新增 BC-33～41；均为源码与调用关系证据，没有运行测试或修改业务代码。

### BC-33 — Basic：infrastructure 将完整准入子系统与跨用途共享机制平铺，文件中还混放编辑入口

- 类别：包结构／文件职责。不是因为有 18 个文件就判定需要拆分。
- `Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/PhysicalRouteResourceCompiler.kt:150` 同次生成准入用 occupancy、progress path 和 release metadata；同目录 `RouteGrantTailRelease.kt:57` 将观察输入连接到 registry shrink；`InfrastructurePreparationActuator.kt:37` 执行准入准备。这是一条完整且内聚的请求→编译→reservation→准备→grant→释放流水线。
- 建议只新增 `basic.infrastructure.admission`，容纳 RouteResourceAdmissionRuntime、RouteResourceAdmissionEngine、PhysicalRouteResourceCompiler、CompiledRouteAdmission、InfrastructurePreparationActuator、CompiledRouteProgressPath、OccupancyQueryGeometry、CompiledTailReleaseQueue、LiveClaimTailRelease、RouteGrantTailRelease 十个文件。暂不再细分 compile/runtime/release 等子包。
- 其余共享机制保留 infrastructure：Registry 同时协调 claims、dependencies、edit permits；TopologyRouteProjector 同时被通用 planner、编辑依赖 compiler 和准入使用；LiveTurnoutSelection 同时被手动操作和准入 preparation 使用。不能把这些共享机制跟着准入一起藏起来。
- `PhysicalTopologyResources.kt:16` 的内部资源身份／anchor 推导，与 `:80`、`:126`、`:164` 的三个 public 编辑影响 resolver 混在同文件。后者消费者是 Bukkit 的 RailWandTopologyEditPlan，变化原因是编辑申请语义；前者服务资源目录与编译。建议将三个紧密相关 resolver 及其 helper 移至同包 `TopologyEditImpactResolvers.kt`，无需造三个新包。
- 维护代价：当前包无法表达哪部分是共享底层机制，哪部分只属于准入；按文件名寻找编辑入口时还会落入资源枚举文件。
- 边界：Registry 必须保留统一状态与锁；移动文件不等于拆分事务 owner。Kotlin internal 是模块级可见性，子包本身不是访问控制保证。公开 API 的 route/infrastructure 合同也不随实现文件机械搬迁。

### BC-34 — Basic：配置扩展拒绝静默成功，而且处理结果取决于注册顺序

- 类别：应显式拒绝却继续／注册合同不一致。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/impl/config/ConfigUIRegistry.kt:95`、`:112`、`:254`。
- 对已经存在且 allowExtensions=false 的组，registerToGroup 返回 Unit 并静默忽略。调用者无法区分成功注册和被拒绝。
- 对尚不存在的组，贡献先进入全局 items 和 pending；随后注册不允许扩展的组，applyPendingExtensions 直接返回，贡献仍留在全局索引和 pending，却不属于可见组。相同的禁止规则因注册顺序产生不同残留。
- 建议：明确“待解析贡献”和“已接受注册”的边界；已知拒绝立即返回明确结果或抛出带 group/key 的声明错误，目标组到达时统一结算 pending，拒绝贡献不进入正式索引。若允许长期未解析目标，应有显式检查入口，不能无声悬挂。
- 本项针对扩展准入，不重复 BC-03 的会话 commit 或 BC-05 的 ConfigItem 替换问题。

### BC-35 — Basic：运营策略迁移后仍保留仅 SimpleATO 使用的类型，且混合不同归属的声明

- 类别：模块归属／文件职责。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/route/RouteAllocationTiming.kt:4`、`:31`；同目录 `RouteBuildResult.kt:5`。
- RouteAllocationTiming 的注释就是 SimpleATO authority 延伸时机，生产调用仅 SimpleATO controller/TimetableDispatcher；RouteBuildResult 的生产构造与消费也都在 SimpleATO 的运营规划、站点和时刻表逻辑。Basic 自己的 TopologyRoutePlanner 已有独立的 TopologyRoutePlanningResult。
- RouteAllocationTiming 文件同时放置 TurnoutSafetyDefaults，后者被 BasicWorldData 与 Bukkit 世界创建使用。把整文件搬走会把共享配置错误带入运营模块。
- 建议：按声明拆分，Timing 和运营 RouteBuildResult 归 SimpleATO 的 routing/运营策略；TurnoutSafetyDefaults 单独成文件，保留共享用途归属。不能把 Basic 的通用 planner、snapshot 和制动原语一并迁走。
- 这是当前使用证据与模块 ownership 的维护性判断，不以单一消费者作为自动删除理由；Timing 的运营策略语义是迁移依据。

### BC-36 — Basic：test-only 制动兜底写入生产执行器合同

- 类别：抽象合同／测试便利侵入生产策略。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/TrainActuator.kt:34`；`Basic/src/main/java/work/art1st/javarailways/basic/control/HandleTrainActuator.kt:96`；SimpleATO controller 的包络及感知边界约束构建。
- 接口默认 brakingEnvelope=null，注释明确称 test-only fallback。测试 KinematicTrainActuator 依赖该默认实现；生产 ATO 因而在约束计算保留常减速度兜底，并仅在 envelope 非空时生成感知远端停车约束。测试执行器缺失此能力会让测试走不同策略路径，不只是采用较简单的数值模型。
- 正式 HandleTrainActuator 对非空 segments 生成包络；空路线也返回 null。因此当前 null 混合“无路线输入”和“执行器未实现制动能力”两种含义。这里不声称正式控制器正常路线已降级。
- 建议：测试执行器显式提供确定性制动能力；真正需要多个策略时显式表达。无输入与缺少能力分别建模，避免靠未覆写可选方法选择生产策略。无需为了测试引入复杂继承框架。

### BC-37 — Basic：静态限速查询要求无用途的 live Train 参数

- 类别：无效依赖／接口语义不一致，低优先级。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/rail/OperationalSpeedLimitSignal.kt:28`。
- getSpeedLimit(train) 并非 override，专门抑制 UNUSED_PARAMETER；类合同说明所有列车返回同一限速，实际只读 resultingLimits。SimpleATO 两处消费者仍传入 live train，测试也为常量查询创建列车。
- 建议：保留 nullable 限速语义，改只读属性或无参查询。列车个性化脱轨安全计算是另一条实际路径，不能因这个参数存在就认为这里支持 per-train policy。

### BC-38 — Core：canDestroy 无调用者，却暴露弱于真实删除事务的准入判断

- 类别：遗留 API／重复且失效的决策入口。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/util/Destroyable.kt:5`；`Core/src/main/java/work/art1st/javarailways/core/physics/rail/CoreRail.kt:88`；真实删除在 `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt:1221`。
- 全仓只有默认声明、CoreRail 和 OperationalSpeedLimitSignal 的 override，没有调用。CoreRail 判断 trains.isEmpty，而真实删除走世界拓扑事务与占用／受影响轨道验证。
- 维护代价：公共方法暗示它能回答可否删除，实际并不驱动删除，判断也不能替代真正的事务许可。增加了一个未来容易被误用的第二入口。
- 建议：删除无消费者的 canDestroy，保留 destroy 生命周期操作；删除准入仍由真实事务 owner 决定，不为了保留方法再补一份复杂检查。

### BC-39 — Core：遍历接口携带无人使用且语义含混的映射重载

- 类别：过度抽象／命名与实现不一致。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/util/plugin/Extendable.kt:28`；同目录 `ExtensionDataContainer.kt:47`。
- forEachExtensionData 同时提供 Unit 遍历与泛型 List 返回重载。泛型映射无实际调用者，却要求各实现与测试替身维护。
- 实现使用 mapNotNull，不仅忽略空扩展槽，也会过滤 callback 返回的 null；名称叫 forEach，实际包含映射及过滤行为，合同未清楚表达。
- 建议：只保留实际使用的遍历；将来若有真实映射需求，再显式定义 map/filter 语义，不能为了“可能有用”维持重载歧义。

### BC-25 补充：trait 使用范围及更小的收敛方案

- 准确说法是“只在列车实体这一局部使用”，不是“只有一个类使用”。CoreTrain 直接实现 TrainComponentTrait；CoreCar/CoreBogie 通过 DynamicRenderableTrait → ExtendableRenderableTrait 间接实现，共三个宿主。
- 没有以 trait 为参数、返回值或字段类型的独立消费者，也没有其他模块实现；它们是几段局部实现复用，不是业务使用的多态能力。
- 共享内容为三处扩展初始化、两处扩展销毁、两处初始快照组装。扩展存储已经通过 Extendable by extensionDataContainer 复用；再加三层继承引入 this as T、隐式 super.destroy 和跨文件追踪生命周期的成本。
- 更小方案：初始化显式 forEach 并传 this；Car/Bogie 显式遍历销毁，保持原有顺序；初始快照就地构造。允许少量短重复，不必另造生命周期管理器。删除内部 trait 与清理公开 DynamicRenderable.lastState 可以分批进行。

### 本轮排除与剩余覆盖

- RailwayAreaOccupancyTracker 的物理连续状态与定义状态具有不同生命周期，不因为是小包装就合并。
- RevisionedCommandQueue 的锁与 volatile revision 服务不同访问需求，未认定为无意义重复同步。
- observation 的防御复制与 epoch/identity 校验属于真实公共边界，不因上游已校验就删除。
- RailSpeedCalculator 退化采样后的无限制返回仍需明确自定义曲线合同，本轮不作为确定缺陷。
- Core 的未使用异常声明已完成调用核对，见 BC-40。
- 本轮覆盖是上述包的定向工程审查，不表示每个生产文件、测试、跨线程与故障组合已审完。仍需补齐 topology 编辑、持久化失败边界、生命周期及所有公开 API 的一致性矩阵。


### BC-40 — Core：异常目录保留不存在的错误协议

- 类别：无效错误模型／遗留 API。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/util/persistence/PersistenceException.kt:3`、同目录 `LoadPersistentDataException.kt:5`；`Core/src/main/java/work/art1st/javarailways/core/api/physics/exception/CannotSplitException.kt:3`、同目录 `Collision.kt:5`。
- 全仓只有定义与旧异常之间的继承，没有实际 throw/catch 消费。真实持久化使用 SerializationException/require，分车使用 CannotCreateTrainException 和状态错误，碰撞使用事件及协调器决策。
- 维护代价：公共错误目录暗示一套实际上不会产生的可捕获协议，必须逐个搜索才知道哪些是真实合同。
- 建议：删掉这些无使用类型，不要为了保留旧层级把当前事件或错误路径重新套进去。实际使用的 BogieException、InvalidActiveRailException、CannotConnectRailException 保留。

### BC-41 — Core：弱引用包装器隐藏失效策略并伪装为非空访问

- 类别：不合理抽象／无效类型防御／注释与合同不一致。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/api/util/WeakReferenceWrapper.kt:6`、`:12`；`Core/src/main/java/work/art1st/javarailways/core/api/control/TrainController.kt:29`；`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/chunk/RenderChunkManager.kt:45`。
- 泛型 T 无非空上界，getter 直接 `ref.get() as T`，没有显式存活检查。泛型擦除使这个 cast 不能实现非空保证，可能把 null 传至调用处才失败；与 TrainController 注释的 fail-fast 承诺不一致。
- 两个消费者都是 val，但 wrapper 还提供无使用的 setter。弱引用的生命周期政策被通用属性代理隐藏，调用处看不到引用可能失效。
- 建议：两个 owner 明确持有 WeakReference，并按其合同选择 checkNotNull 或 nullable lookup；如果保留代理，至少限制 T : Any、显式检查并删除无消费者写入能力。无需再引入新的引用接口。
- 这是静态类型和合同判断，没有运行 GC 时序复现，也不声称当前正常控制流程已触发引用失效。

第四轮 Core 额外覆盖 ExtensionDataTypeRegistry、PluginRegistry、WorldSnapshotCodec、SavedRailEndpointDecoder、ObjectPools、异常类型及删除相关调用。显式类型注册、严格解码和补充错误上下文没有被认定为过度设计；空间索引与全部拓扑准备对象仍未深入。


## 第五轮：补齐拓扑准备、索引、几何与执行环境的静态覆盖

- 基线：`9ed278181a577ebc1450bd307fe466d47ed91f96`。
- 用户明确要求暂不启动测试。本轮未执行测试、构建、局部运行复现或压力验证，仅阅读源码、规范和调用关系；不修改业务代码、不创建任务。

### BC-42 — Core：世界 DI scope 已无生产解析方，却仍贯穿创建与关闭合同

- 类别：不必要抽象／遗留生命周期负担。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/di/JavaRailwaysKoin.kt:29`、`:46`；同目录 `WorldParentContext.kt:25`；`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt:118`；`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/RailWorldFactory.kt:92`。
- Koin 实际只定义根 marker、创建父／世界 scope、declare world；全仓生产代码没有从 scope 读取 world 或其他服务。getScope/get<CoreWorld> 的实际消费在验证 scope 存在的测试中。持久化已经使用显式 dependency，运行时组件也显式拿到 world。
- 但每个 live world 仍启动／连接全局 Koin、注册 child scope、持有 registration，并在构造失败、加载失败、关闭时清理。WorldParentContext 参数及 no-op 实现还贯穿 factory、snapshot dependency 和测试 fixture。
- 维护代价：为一个没有生产读取方的登记维持全局状态、创建失败来源和独立清理阶段。现行生命周期规范详细规定了这些清理步骤，说明它们被正确维护，不证明该功能仍有必要。
- 建议：先清除无消费者的 Koin world binding，随后收敛仅为它存在的 context 参数／registration／no-op。保留真正拥有物理与 tick 生命周期的资源管理；不要把清理写法本身认定为错误，也不建议删除与此无关的 owner 机制。
- 边界：这是当前仓库消费者范围内的维护性判断，不声称 Koin scope 当前泄漏或初始化失败；未假定未知第三方使用该内部集成。


### BC-43 — Core：交叉索引与空间索引的更新归属不对称

- 类别：职责边界不合理／隐含调用约定。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/rail/AtGradeRailCrossingIndex.kt:23`、`:54`、`:145`；`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt:798`、`:1258`。
- crossing index 添加／安装时同时维护 spatial index，remove 却只删自身；CoreWorld 两处删除必须手工再删 spatial。当前两处都已配对，未发现现有删除遗漏。
- 维护代价：同一类的添加与删除有不同副作用边界，未来增加替换／恢复入口时容易留下 spatial candidate，却没有 sampled geometry，随后触发内部不变量失败。
- 建议：由一个现有 owner 对称维护两份索引；可以把两者操作都放在 CoreWorld，也可以让 crossing remove 对称清理 spatial，选择一种即可。不新增通用索引管理框架。

### BC-44 — Core：交叉检测规则在加载与在线准备路径重复，邻格策略也散落在调用方

- 类别：缺失合理复用／策略归属分散。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/rail/AtGradeRailCrossingIndex.kt:34`、`:111`、`:119`、`:169`；同目录 `RailSpatialIndex.kt:84`、`:102`。
- bootstrap addOrReplace 展开 pair 排序、几何对应、bounds gate 和 detect，而现有 detectPair 已实现同样流程，prepared 路径却调用该函数。修改检测规则可能让加载与在线创建分叉。
- 27 邻格枚举另在 spatial 两个 candidatesFor 和 crossing 批内候选构建重复，交叉检测器因此需要知道空间分桶的细节。
- 建议：bootstrap 复用已有 detectPair；rail 查询版本复用 footprint 查询版本，邻格枚举用一个小型内部函数。保留不同生命周期和 live／批内容器，不为此引入泛型索引体系。

### BC-45 — Basic：无读写入口的 SavedAnchor DTO 暗示不存在的持久化协议

- 类别：遗留文件／无用途的格式抽象。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/curve/SavedAnchor.kt:9`、`:18`、`:25`。
- SavedAnchor、SavedVec3d、SavedAnchorFile(version=1) 全仓只有文件内引用，无序列化调用、转换入口、注册或测试消费。实际 RailWand 普通锚点存在 world-keyed transientAnchors，端点和分割锚点由轨道派生。
- 维护代价：类型和注释暗示存在 YAML 持久化边界，实际上没有 reader/writer，给后续格式维护提供错误线索。
- 建议：清理无消费者文件；不据此创建迁移、提升版本或假定曾经发布。若将来真的需要锚点持久化，应先确定实际边界与合同。

### BC-46 — Basic：道岔构建结果额外暴露无人消费的派生切线

- 类别：冗余结果模型，低优先级。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/curve/StandardTurnoutFactory.kt:47`、`:102`。
- StandardTurnoutBuildResult 的生产消费者只读 curve；startTangent 无外部读取，endTangent 只在测试读取。两份 mutable Vector3d 重复表达曲线端点切线，还可以独立修改。
- 建议：直接返回 curve，或去掉多余字段。现有测试同时检查真实 curve 的终点切线和末段方向，应保留这些有效断言，不能把整个测试归类为无效。

### 第五轮低优先级候选与排除项

- RailCurvePartitioner.kt:103-125 的 require(curves.size == plannedRoles.size) 检查本函数构造必然成立的计数关系，不验证额外输入。可删；若为未来重构保留不变量断言，使用说明清楚的 check。暂不单独编号。
- RailGeometryBreakpoints.kt 的 exact 断点合同在非 PrimeCurveImpl 时回退 stream，ReversedCurve 会落入含虚拟采样的路径；支持范围、成本与合同需进一步明确。未运行几何／性能验证，不声称交叉检测结果已错误，暂不单独编号。
- ParallelAnchorGeometry 两种结果分别表达不同阶段的值和失败原因，不建议为形状相似就套通用结果框架。
- VerticalProfileSmoother 的固定端点／手动锁／水平段／极值优先级有明确作用，内部求解失败不是可删的“不可能错误”。
- SweptOccupancyIndex 的碰撞候选优先级与普通占用过滤有不同用途；bounds 检查、缺失 sampled geometry 的 fail-fast 都有真实不变量依据。


### BC-09 补充：快照恢复准备的曲线解码职责分散并重复构造

- `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt:429` 先为新增／替换轨道 load 曲线以准备创建 batch；`Core/src/main/java/work/art1st/javarailways/core/physics/rail/PreparedRailWorldSnapshotPlan.kt:112`、`:113` 又解码完整 current/target snapshot。
- 因此刚准备过的目标曲线再次构造，未变化曲线也从两份 snapshot 重建。后一阶段主要验证端点和读取长度，未发现必须使用另一曲线实例的理由。
- 建议准备阶段一次建立解码映射，供创建 batch、连接验证和目标拓扑使用；可用一个内部准备协作者承接。CoreWorld 保持唯一锁、提交、发布和失败 stall owner。没有测量运行时间，不声称已造成明显卡顿。
- 小型冗余：同一 read lock 内 plan.requireCurrent 后又比较相同 topology root，可局部删除；跨准备／提交阶段的复验必须保留。

### 第五轮覆盖台账（静态深度）

- **Core 索引：** 逐读 RailSpatialIndex、SweptOccupancyIndex、AtGradeRailCrossingIndex、RailGeometryBreakpoints，并追踪 CoreWorld 安装／删除、occupancy snapshot 和曲线断点消费者。尚未穷尽平台空间查询输入，未验证数值完备性与性能。
- **Core 拓扑准备：** 阅读 PreparedRailCreationBatch、PreparedRailConnectionPlan、PreparedRailTopologyUpdate、PreparedRailWorldSnapshotPlan；追踪 TopologyInput、CoreTopologyInputAdapter 两种 capture、TopologyStateBuilder 入口和关键校验，以及 CoreWorld 和 RailWand 主要消费。builder 全算法证明与所有异常组合仍未完成。
- **Basic 几何：** 逐读 RailCurvePartitioner、VerticalProfileSmoother、Anchor、SavedAnchor、ParallelAnchorGeometry、StandardTurnoutFactory，核对生产消费者。工程职责已补查，未做数学完备性或数值运行验证。
- **Core 执行环境：** 阅读 di 三个文件、WorldExecutionOwner、ThreadGuardBridgeRegistry、AsyncTickThreadMarker、IsolatedSimulationThreadMarker、AwaitExecutorTermination 和对应 host bridge 调用。没有证明所有跨世界加锁、临时释放再获取、关闭时序均正确。
- **合理边界：** 不同 Prepared 类型代表不同操作与准备阶段；TopologyInput/adapter/builder 分离活对象采集和纯构建；提交时复验用于防止准备后世界变化；无法证明完整回滚时 fault/stall 是有效失败处理。本轮均未将它们当作过度抽象或吞错。
- **仍需横向补齐：** 全部公开 API 的可见性／可空／异常合同，持久化与运行生命周期的失败清理矩阵，测试源码质量及包／命名／重复工具的一致性。原“索引／剩余几何尚未深入”已由本轮更新，不能继续笼统列为完全未查。
- **验证限制：** 本轮只做静态审查，零测试执行、零构建、零局部运行复现；用户暂不启动测试的约束继续有效。


## 第六轮：API 合同与失败清理横向审查

基线仍为 `9ed278181a577ebc1450bd307fe466d47ed91f96`。仅静态阅读与调用搜索；没有运行测试、构建或故障注入，没有修改业务代码或创建任务。新增 BC-47～51，其中 BC-50 应优先处理，其余主要是接口／结构／测试维护问题。

### BC-47 — Core：RailView 保留无人读取的旧保存出口

- 类别：遗留 API／不存在的持久化合同。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/rail/RailView.kt:80`、`:88`。
- SavedRailViewV1/save 保存 rail ID、方向、相邻 rail ID 列表和 active 下标；全仓无调用方或 reader。真实 CoreRail.SavedRailV1 已用 SavedRailEndpointRef 明确端点身份，加载使用 SavedRailEndpointDecoder。
- 旧出口暗示另一套受支持格式，却不能表达当前端点连接的完整身份，容易引出无必要的 reader、版本和兼容路径。
- 建议删除无消费者 DTO/writer，保留真实存档入口；不创建兼容 reader、不凭清理旧声明升级 schema。与 BC-45 同类，但属于独立 Core writer。

### BC-48 — Basic：freeze 返回执行结果，但生产调用完全不消费

- 类别：接口职责含混／冗余结果，低优先级。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/TrainActuator.kt:32`；`Basic/src/main/java/work/art1st/javarailways/basic/control/HandleTrainActuator.kt:91`；`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/ato/AbstractATO.kt:137`。
- freeze 与 step 一样返回 ActuationStep，但唯一生产消费者丢弃返回值，自己写零速度、零移动和累计量。Handle 实现真正作用是选择制动手柄；返回值只有测试读取。
- 建议 freeze 返回 Unit，明确执行器只调整内部冻结状态，运动冻结由 controller 承担。保留实际副作用，不将其误认作无用方法。与 BC-36 制动包络的可空合同不同。

### BC-49 — Basic：通用世界对象注册表仍归于 ATO provider 包

- 类别：包归属不合理。
- 位置：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/provider/RailObjectStore.kt:16`；`Basic/src/main/java/work/art1st/javarailways/basic/BasicWorldData.kt:21`。
- Store 管理 Core RailObject、ref/rail 查询和注册生命周期，不依赖 ATO 或 provider 合同；实际 owner 是 BasicWorldData，SimpleATO 的 StationStore 复用同一个 store。provider 包只有该文件。
- 包路径错误暗示通用对象注册属于 ATO 服务，降低非 ATO 调用方的可发现性，并使后续类型容易继续放错层。
- 建议移至 basic.api.rail，保持跨模块需要的 public 可见性；不新增接口／实现分离或兼容代理。与 BC-16 注册原子性问题分开。

### BC-50 — Core：扩展销毁异常会截断失败加载的 Core 注销

- 类别：无效失败清理／同类错误处理不一致，优先级较高。
- 位置：`Core/src/main/java/work/art1st/javarailways/core/physics/train/TrainPersistence.kt:46`；同目录 `CoreTrain.kt:581`、`:208`；`CoreCar.kt:691`；`Core/src/main/java/work/art1st/javarailways/core/util/trait/render/ExtendableRenderableTrait.kt:13`。
- SavedTrainV1.load 在恢复摆位／发布失败且 coordinator 仍 RUNNING 时尝试 discardFailedLoad。后者依次 removeController、销毁 head/tail/cars、world.removeTrain。
- 任何 bogie/car renderer destroy 抛异常都会中断后续步骤，world.removeTrain 不执行；失败列车的世界身份／发布归属可能保留。外层仅把清理异常附加为 suppressed，不因此进入 STALLED。
- 同一 CoreTrain 的构造失败路径已将独立清理逐项 runCatching，继续尝试 world 注销。这里应统一“独立 Core 注销不被插件清理错误阻止”的政策。
- 建议逐项尝试并聚合错误，确保独立 Core 登记撤销；若 Core 状态无法安全撤销则显式 stall。正常 destroyOnOwner 的失败停机合同不同，不能改成所有销毁异常都忽略后继续运行。
- 边界：静态调用链确认，未注入 throwing renderer 运行复现；不要求回滚任意 extension 外部副作用，也不声称所有 world 加载都应原子回滚。

### BC-51 — 测试工程：世界 fixture 的清理合同没有统一落实

- 类别：缺少合理共享／测试资源生命周期不一致。
- 位置：`Basic/src/test/java/work/art1st/javarailways/basic/safety/SpeedSafetyMonitorTest.kt:28`、`:32`、`:63`、`:101`；`Core/src/testFixtures/kotlin/work/art1st/javarailways/core/physics/train/TrainTestUtils.kt:39`。
- 三个 safety 测试创建拥有 tick/coordinator 的世界，但没有 finally/AfterEach 关闭；共享 createTestWorld 注释明确成功后由调用方负责关闭。
- 其他测试各自编写 withWorld/closeWorlds，部分是串行 close，一步失败就不再尝试后续清理；CoreWorldRailTopologyTransactionTest 又有独立的错误聚合版本。相同 lifecycle 合同由各测试手工复写，已经出现遗漏。
- 建议提供一个小型作用域 fixture helper，区分自己创建和调用方传入的资源，统一逐项关闭和 suppressed；测试仍自行组织场景与断言，不搭建通用测试框架。
- 边界：未运行测试，未测量泄漏，也不声称这些测试必然启动后台线程；即使 executor 尚未启动，成功创建后缺少明确关闭仍是 fixture ownership 缺口。测试断言本身不因此无效。

### 既有问题补充与本轮排除

- BC-40 补充：CurveException(curve, invalidField, message) 的前两个参数完全不保存或生成诊断，唯一调用看似传入上下文，实际只留下固定错误文字。可删无效参数或捕获必要值诊断，不为保留参数引用整个 live curve。
- BC-42 补充：createTestWorld 即使使用 NoOpWorldParentContext 仍调用 ensureJavaRailwaysKoinStarted，进一步扩大无生产解析方的全局绑定对测试的影响。
- Train.publishedFrame、可空 tick metrics 有明示可选合同；未找到消费者误用，未将可空本身认定为错误。
- World topology listener 默认不支持、RailView 非 Core 实现拒绝和 Future/CompletableFuture 写法差异，在本轮证据下不另计缺陷。
- Basic 已明确区分“断连返回 null”与非法输入抛错的距离 API，以及有实际意义的无活动准入状态，不应机械统一成一种结果类型。
- 构造器不销毁调用方 cars、发布进入 STALLED 后不再反向 discard、CoreWorld 失败只关闭自身 binding，均有明确 ownership 理由，未列为失败清理缺陷。

### 第六轮覆盖与尚余空白

- Core：Train/Rail/RailView/RailObject/World/Curve/PrimeCurve/TickManager 的公开签名与默认实现、保存出口、异常上下文。
- Basic：operation gateway/ticket、actuator、safety interlock、准入／换向／准备结果、manual turnout、dependencies、规划限制／方向／距离、route/perception/observation 和 RailObjectStore。
- 失败清理：Basic infrastructure/runtime restore，Core train/car/bogie load、构造／failed-load／正常销毁、renderer destroy、CoreWorld.load 及平台资源栈。
- 测试源码：共享 world fixture 与若干具体 cleanup，未逐个读完全部测试；没有执行任何测试。
- 尚余：所有平台 gateway 的调用条件、第三方扩展异常组合、关闭超时和跨世界锁交错、全量测试源码质量与全部 API 文档/可见性的一致性。已覆盖清单只表示静态审查深度，不能作为运行行为或全模块无缺陷证明。


## 提交与剩余范围（2026-10-19）

后续对话问题5–13的提交：5 db2a8200；6 64d303d2；7 529cc1f0；8 968a22be；9 dad8fd81；10 6a6cbbc5；11 ecded929；13 936e8c1e。12尚未实施。8f076bcb同步合同文档。

用户授权其余已验收改动提交后：

- 6fece6fc：Wand配置迁至BukkitPlugin，ConfigItem改名WandConfigItem，配置预注册/真实类型/先校验再注册/提交通知分类及所有消费者；补充IOException通知失败的边界。
- acd19ef5：Core/Basic剩余工程清理及跨模块消费者，包括DI/死接口清理、控制器当前速度、事件注册与优先级、extension保存owner-key冲突、索引所有权、Basic admission包及SimpleATO运营归属、曲线结果收窄和测试fixture。
- 修正未用fixture借用分支、明确测试coordinator关闭、更新旧command与frame reason文档。相关源代码与测试源码均已纳入提交。

本轮通过差异空白检查与静态调用链检查。未运行编译、测试、构建或服务器复现；提交不代表运行验收通过。其他任务的.trellis/tasks文档未纳入。BC-23结果nullable访问面/成功帮助函数、BC-51全测试fixture统一仍仅部分完成，不将原51项标为全部完成。

### 问题12：BC-09 CoreWorld职责拆分

当前CoreWorld约1610行。推荐保持单一锁、事务、占用、发布owner，只移出纯准备计算：

1. 先提取buildPreparedTargetRailSnapshot（约45行）：输入已有快照和变更，输出目标DTO，没有额外world可变状态。
2. 目标topology计算（约85行）与锁内live capture分离。前者移到physics/rail内部helper，后者留在CoreWorld。
3. snapshot索引、diff、曲线解码集中为一次准备结果，供PreparedRailWorldSnapshotPlan消费。

整体中等规模，可逐步提交；这样的内部提取不需要新的产品/持久化决策。不推荐扩大为多个事务owner或策略接口；那属于另一个并发架构任务。

### 问题12：BC-10 Basic几何职责拆分

第一步规模较小：提取Basic内部CurveFrames纯计算，将ConstraintSolver.computeFrame和两份roll旋转集中。内部统一radians并不修改输入，metadata的rollDegrees仍在入口转换；保持现有近垂直阈值、采样和容差。影响约8个生产文件。VerticalCurveRebuilder不再为了基础数学同时依赖Solver和Factory。无需新业务决策，也无证据需要放入Core。

第二步中等规模：RailCurveFactory约1036行。保留公开facade/模板调度，先分出并行偏移、Hermite S弯与半径拟合的内部实现；超高profile按实际依赖抽纯helper。无需引入每模板一个策略类的体系。

建议下一批做BC-09纯准备器及BC-10基础frame/roll，Factory算法拆分再单独一批。需要确认的只是实施范围，不是改变曲线数学行为。当前仅评估，未开始问题12的重构。
