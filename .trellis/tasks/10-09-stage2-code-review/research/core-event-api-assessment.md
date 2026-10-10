# Research: Core 旧事件与未来附属插件用途评估

- Query: 评估 `PreTickEvent`、`PostTickEvent`、`RenderableEvent` 的实际发布、未来附属插件独特用途、现有 processor/service 替代能力、线程与阶段，以及保留概念或删除空壳的取舍。
- Scope: internal；定向覆盖 Core `api/event/**`、`api/event/processor/**`、PhysicsCoordinator/CoreWorld/TickManager 与 Basic、SimpleATO、SimpleSignals、Bukkit 宿主的直接消费链。座椅、迁移及其他模块整体验收不在本次范围。
- Date: 2026-10-10
- Baseline: `dev/signal-system` / `7ff3a8d3`；本轮源码未改动。
- Status: 静态研究与产品建议；任何删除、重命名、增加 publisher 或公共接口变更均未获本次评估批准，也未实施。

## Findings

### 推荐

建议删除这三个未接线的历史声明，同时保留已有领域事件及“明确阶段、明确 payload 的通知”这一产品概念。不建议为使旧类生效而新增泛 tick 广播，也不建议把 `RailwayEpochProcessor` 包装成任意附属插件的动态 listener registry。

这个建议不是由“没有当前 caller”单独推出：主循环 tick 已与 physics epoch 分离，平台呈现又由目标 region 异步执行。三种旧声明没有 world/epoch/revision、具体变更或可靠的完成阶段，不能承载其名称容易令人期待的控制/完成保证。已有服务覆盖了当前具体插件需求，仍未公开的独特需求应先得到明确产品要求，再按真实 owner 设计最小接口。

**没有 publisher 是当前事实，不表示未来事件用途不存在。** 前置控制、完成物理结果观察、离散业务转换、模型生命周期和呈现完成分别有合理用途；它们应按各自语义评估，不能把三个旧名称当成已经支持这些用途。

### 已实现：事件与总线

- `Core/src/main/java/work/art1st/javarailways/core/api/event/PreTickEvent.kt:3` 只有“安排一次 server tick 工作前”的 KDoc 和空 class。`PostTickEvent.kt:3` 只有“server tick commits 完成后”的 KDoc 和空 class。`RenderableEvent.kt:5` 是持有 live `Renderable` 的 interface，没有具体 subtype。
- 全仓非文档、非构建产物源码检索三个名字，只命中上述声明；生产、测试均无 publisher/handler/具体 render event。声明并不构成可订阅的现成功能。
- `MainRailEventBus.kt:13` 是 process-wide singleton，`publish` 在当前调用线程执行，并于 `:21` 检查 Core main context。`PhysicsRailEventBus.kt:14` 同样 process-wide，于 `:23` 检查 async tick context；其 `:10` 明确不同 train 的 callback 可并发。它们都不执行调度。
- `api/event/processor/TypedRailEventBus.kt:36` 反射注册单事件参数的 handler，返回独立 registration；`:58` 幂等关闭，`:77` 同步遍历类型与 listener，`:83` 捕获 handler `Throwable` 后继续。它不是可回滚事务，也不能作为 safety processor 的失败传播替代。
- 实际领域事件应单独保留和审查：`PhysicsCoordinator.kt:288` 发布 `CollisionEvent`，允许 stable-ID decision request，结果在后续 epoch 应用；`CoreTrain.kt:218` 在前向移动的 signal 探测阶段发布 `PassSignalEvent`，这是 physics worker 上的 live payload，不能当作完整成功 epoch 后的通知；`CoreWorld.kt:219` 在 publication 后消费 derail 通知；`CoreTrain.kt:479` 将 physics reverse 通知排到 main post-tick queue。
- 宿主的真实 consumer 是 `BukkitPlugin/.../behavior/TrainReverseBehaviorListener.kt:11` 和 `debug/TrainDebugEventListener.kt:11`，分别处理 reverse 行为和带原因的单次 derail 记录。`BukkitPlugin.kt:227`/`:229` 注册，`:335`/`:337` 关闭。Basic、SimpleATO、SimpleATOBukkit、SimpleSignals、BukkitPluginDev、TunnelBridgeBuilder 的生产代码未使用这些 Core bus。没有当前消费者本身不构成删除真实领域事件的充分理由：decision、变更原因和发生时刻不能可靠地由较晚的快照差分恢复。

### 已实现：三个不同阶段

宿主主循环来源是 `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt:91` 的 global scheduler，运行串行 simulation context。其顺序是：

1. `WorldManager.kt:384` 开始主循环，逐 world wake physics，再 generic `TickManager.preTick`。
2. `PhysicsCoordinator.kt:395` 应用上一轮 collision decision、快照 controllers、并行 tick；`:422` 等待所有 workers；`:428`/`:438` 发布 controller/train 状态及 immutable train frame。
3. `PhysicsCoordinator.kt:446` 执行 per-train frame processors，`:447` 构建共享 occupancy，`:449` 捕获候选 world frame，`:451` 串行执行 railway epoch processors。`CoreWorld.kt:1231` 再次核对 topology/occupancy/train frames/active continuations，最终 `PhysicsCoordinator.kt:503` 发布 world frame，`:509` 完成 exact ticket。
4. `WorldManager.kt:405` 捕获并 await exact ticket；`:412` 发布公共 observation；`:418` 消费资源释放观察；`:425` 应用安全请求；`:440` 提交车辆画面调度；`:447` 运行 Dispatcher；`:452` generic `postTick`。
5. `WorldManager.kt:462` drain generic platform commits，必要时排入目标 owner；`:569` 车辆画面通过 `runAt(location)` 才真正 `applyFrame`。

“物理 frame 已发布”“主循环协调完成”“实体显示应用完成”因此是不同事实。`Core/util/TickManagerImpl.kt:129` 的 `postTick` 仅 await generic workers、drain 两个 task queue；`:173` 才提供 platform commit drain。`scheduleMainThreadAfterCommitTask` 的名称不证明平台显示已完成。`CoreWorld.kt:1423` 的 train mailbox drain 是宿主消费动作，也不是第三方广播 API；附属插件不得竞争 drain 同一个 mailbox。

此外 `PhysicsCoordinator.kt:338` 的 fallback timer 可独立启动 epoch，`:358` 的 wake 可合并到已有 ticket。因此一次 server/global callback、generic tick、physics epoch 不能当作一一对应。

### `PreTickEvent`：前置控制有用途，但旧空壳不提供该能力

未来插件可能需要在计算前提交驾驶输入、控制策略或世界政策。这是合理需求，现有后置 observation 与 railway epoch processor 均不能保证“本轮运动开始前接收一个新决定”。但需区分：

- 普通周期工作只需平台自己的 scheduler，旧空事件没有独特数据或阶段保证。
- 插件拥有一个列车 controller 时，`Core/api/control/TrainController.kt:75` 的 worker tick 和 `:99` 的 publication callback 已表达相应 owner 阶段；它是单个 controller 的能力，不是旁路追加任意监听器的能力。
- Basic/SimpleATO 现有 command 合同是在 physics boundary 激活 immutable revisioned request；实际 `SimpleATO/controller/SimpleATO.kt:469`/`:517` 统一 drain mission 与普通 command。信号限制由 epoch N 的 source 处理、在 N+1 的 controller 规划前消费，不能改成依赖主循环 pre-event 才更新。
- 真正需要“每个 world 的每次 physics epoch 开始前执行”的新扩展点，目前没有通用、可动态注册的公共 service。`PreTickEvent` 若只在 `WorldManager.tickStart` 发布，会漏掉 fallback epoch，并可能在 physics 已开始后发生；若改在 coordinator 发布，又违反它目前的 `MainThreadRailEvent` 类型。

推荐移除旧声明，不承诺 generic pre-tick hook。若用户明确要求未来的前置决策能力，先明确由 train controller、现有 signal/inspection source，还是新的特定政策 owner 接收输入；保证在该 owner 的 physics boundary 激活。需要新接口时再约定 stable identity、目标 epoch、合法 result、失败行为与卸载生命周期；本评估没有提出新增通用框架。

### `PostTickEvent`：完成物理观察已存在；“所有平台 commit 完成”不存在

面向独立附属插件的替代入口已实际公开：

- `BukkitPlugin/.../api/observation/RailwayObservationService.java:16` 提供 per-world `latest`/`subscribe(pluginOwner, worldId, listener)`；`api/internal/JavaRailwaysServiceRegistration.kt:31` 注册到 Bukkit services。owner disable/host close 有明确释放合同。
- `api/internal/DefaultRailwayObservationService.kt:63` 校验 frame 与 exact ticket epoch，`:78` 对同 epoch 去重，`:89` 同步通知，`:90` 隔离普通 listener 异常。这是供观察的通知，不是使安全结果先于 world frame 可见的处理器。
- `RailwayObservationListener.java:11` 明确 callback 位于 serialized simulation context、exact ticket 完成后，非 physics worker，不能直接执行 Bukkit entity/location/block/region side effect。
- `Basic/.../api/observation/RailwayObservation.kt:95` 是同一 world/epoch 的 immutable value；`:144` 的投影主动丢弃 render car 数据。状态面板、地图、在所收到观察范围内的日志/统计可用此接口。`SimpleSignals/.../authoring/CurrentRoutePreview.kt:122` 已真实订阅该服务跟踪 active continuations。

替代的限制同样明确：该观察发生于 Dispatcher 与 generic postTick **之前**；它不表达运营调度决策完成，不表达每个车厢的姿态或实体应用完成。宿主只在自己的循环中发布观察，因此不承诺 fallback 期间每个 physics epoch 都送达，也不提供逐 epoch backlog。需要无遗漏的边界越过、精确每 epoch 计量或离散原因时，不能仅由连续两份 latest observation 差分代替真实领域 hook。

推荐保留“成功物理发布后通知”的概念，通过已有服务承担；删除含糊的 `PostTickEvent` 空壳，不增加一个重复的第二订阅通道。若未来确需“Dispatcher 已完成的特定运营变更”，应在相应运营 owner 定义具体领域事件；若需“模型显示已应用”，应由 platform/render owner 提供明确结果。把旧事件在 generic `postTick` 结尾发布并沿用 commits-complete 文案，会制造当前实现无法兑现的保证。

### `RenderableEvent`：呈现与生命周期合理，旧基类没有具体契约

- `Core/api/render/Renderable.kt:7` 是 `Destroyable + WorldElement` 和 JOML position/direction/normal/velocity 的 live 几何接口。`RenderableEvent.kt:7` 直接携带它，既没有稳定 render object ID、revision，也没有 created/removed/frame-applied/click/failure 等事件类型。不能据此判断 callback 获得了 Bukkit entity/region ownership。
- 普通附属模型目前由 `BukkitPlugin/.../api/model/ModelPresentationService.java:15` 的 owner/world/object 绑定 handle 创建，`ModelHandle.java:9` 提交完整 immutable revisioned presentation，`:13` 安排关闭，`ModelPresentationService.java:24` 提供 owner cleanup completion。`ModelHandle.java:3`/`:7` 明确任意线程调用、从不 await rendering，渲染失败由宿主记录。
- `SimpleSignals/.../presentation/SignalModelBinding.kt:269` 已真实提交 `ModelPresentation`，并抑制未变化的显示。当前具体呈现并不依赖 Core render event。
- 精确 train-car/bogie 物理姿态可在 `Train.publishedFrame`（`Core/api/physics/train/Train.kt:48`）读取，但它是某次读取时的 published value，不提供动态订阅和平台应用完成保证。公共 `RailwayObservation` 不含这些 render-car 字段，内部 `PublishedTrainFrameProcessor` 也不能直接推介给第三方作为 renderer hook。

未来 renderable/model 创建、移除、交互、应用成功/失败通知各有独特用途；现有 service 只对部分创建/提交/清理动作提供能力，并没有为每次 submit 提供 applied acknowledgement 或所有列车模型 lifecycle 广播。推荐删除无具体事件的旧 grouping interface。若用户需要上述某一能力，再由宿主模型/列车生命周期 owner 定义具体稳定身份与结果；几何观察传 value frame，平台副作用由目标 entity/region owner 执行。不要在 physics worker 发布 live `Renderable`，也不要以全世界 renderable 每 tick 广播替代有语义的通知。

### 两种 processor 的可替代范围

- `Core/api/physics/RailwayEpochProcessor.kt:4` 是完整候选 world frame 的 **publication 前** value-only 同步处理；不是 main post-tick observer。`PhysicsCoordinator.kt:208` 仅允许 start 前注册，`:218` 禁止运行中移除；API `World` 没有暴露该 registration，当前 composition 经实现对象安装。抛错会 Stall，不会像 event listener 一样日志后继续。因此不能宣称独立插件随时 enable/disable 即可注册它。
- `Core/physics/tick/PublishedTrainFrameProcessor.kt:6` 位于 implementation namespace，`process(CoreTrain, epoch)` 含 live implementation，按 train 并发，`completeEpoch` 串行；它不是 public value-only add-on API。真实 consumer 为 `Basic/.../safety/SpeedSafetyMonitor.kt:25`，由 `WorldManager.kt:227` 安装、`:247`/`:352` 释放，先发 stable derail request，宿主 await 成功后应用。
- `SimpleATO/.../signal/SignalEpochRuntime.kt:29` 是已安装的 railway processor，`RailWorldFactory.kt:195` 在 start 前注册；`SignalEpochRuntime.kt:178` 只调用兴趣有更新的 source，`:191` 校验结果，`:198` 发布限制，错误向 Core 传播。
- 独立信号插件的当前入口是 `BukkitPlugin/.../api/signal/SignalEpochSourceService.java:33`：按 exact plugin/world 绑定完整 source 集合，支持 runtime replacement，callback 串行运行在 railway coordinator。`SimpleSignalsWorldRuntime.kt:1289` 已实际安装 source。因此当前信号/保护插件无需泛 `PreTickEvent`/`PostTickEvent`，也不应绕过该 owner 直接加 processor。

以上是现有能力分类，不是增加新 API 的设计批准。尤其不能把既有 implementation 泄漏当作新接口先例。

### 线程与阶段的建议边界

- 普通完成物理观察：使用现有 observation service 的串行 simulation callback；只处理 immutable value，快速返回，平台效果交给目标 scheduler。
- 会影响下一次运动的信号/安全决策：使用已认证的 source/controller owner，在其物理边界同步消费；不能依赖 server tick，不能吞错继续运动。
- 模型实体创建、更新和移除：由 host render/platform owner 在目标 region/entity scheduler 应用；只有该 owner 才能报告明确操作的完成结果。
- 日志、metrics、纯附属业务观察：隔离 observer 失败，不给它改变已发布物理事实的权限；较慢处理不得为每个 tick 累积无界 queue。
- `Bukkit/util/BukkitThreadGuardBridge.kt:13` 的 main identity 含 serialized simulation context。通过 `MainRailEventBus` guard 不等于拥有任意实体或 location 的 Folia 访问权限。

### 必要待用户决定项

1. 是否接受上述三个类从候选 API 删除，同时将普通后置观察指向现有 `RailwayObservationService`。用户已确认无仓库外插件，故没有已知外部迁移对象，但这并不替用户决定未来产品功能。
2. 是否已有必须近期支持的具体需求：每次 physics epoch 前的第三方决策、无遗漏的物理变更流、Dispatcher 完成后的运营领域通知、列车模型生命周期，或每次显示应用完成结果。若没有明确需求，可先清理空壳；若有，应先确定其中实际所需项，再单独评审 owner/thread/payload/failure/lifecycle，不能一次性新增全套事件体系。

## Files Found

- `Core/src/main/java/work/art1st/javarailways/core/api/event/**` — event/root 声明，区分真实领域事件和三个无发布空壳。
- `Core/src/main/java/work/art1st/javarailways/core/api/event/processor/**` — 同步 typed bus、反射 handler、closeable registration、线程 guard。
- `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt` — worker/coordinator epoch、两种 processor、fallback、world frame 与 ticket 完成顺序。
- `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PublishedTrainFrameProcessor.kt` — 实现层 per-train callback，含 CoreTrain。
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/RailwayEpochProcessor.kt` — 公开 value-only publication 前的静态 composition callback 合同。
- `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt` — frame capture/revalidation/publication、main derail notification、宿主 mailbox 消费。
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt` — pass-signal/reverse/derail 真实 publisher。
- `Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt` — 独立 generic workers、task queue 与 platform commit drain。
- `Core/src/main/java/work/art1st/javarailways/core/api/util/tick/TickManager.kt` — generic ticking 候选 API，与列车 physics owner 分离。
- `Core/src/main/java/work/art1st/javarailways/core/api/control/TrainController.kt` — 每列车 worker tick/publication hook；不是 multicast listener。
- `Core/src/main/java/work/art1st/javarailways/core/api/render/Renderable.kt` — live 几何接口，不是 detached platform lifecycle payload。
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/train/Train.kt` — immutable published frame 读取入口。
- `Core/src/main/dokka/Module.md` — 当前 `1.0-SNAPSHOT` 候选 API，无 binary compatibility 承诺。
- `Basic/src/main/java/work/art1st/javarailways/basic/safety/SpeedSafetyMonitor.kt` — per-train processor 实际使用与 stable derail request。
- `Basic/src/main/java/work/art1st/javarailways/basic/api/observation/RailwayObservation.kt` — 移除 render-car/live 对象的同 epoch 公共观察投影。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/signal/SignalEpochRuntime.kt` — physics 上的响应式 signal source routing 与 restriction publication。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/controller/SimpleATO.kt` — revisioned mission 与控制命令在 controller owner 统一 drain。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt` — 独立 logical physics step 路径，进一步说明 server tick 不等于 physics epoch。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt` — 真实插件 source 注册与 lifecycle。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/CurrentRoutePreview.kt` — observation 服务实际 consumer。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/presentation/SignalModelBinding.kt` — 模型服务 actual presentation submit。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt` — simulation→exact ticket→observation→Dispatcher→region commit 的宿主顺序。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/RailWorldFactory.kt` — Core/SimpleATO composition 与 static processor 安装。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultRailwayObservationService.kt` — owner/world-scoped 公共观察与 epoch 去重。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/observation/**`、`api/signal/SignalEpochSourceService.java`、`api/model/ModelPresentationService.java`、`api/model/ModelHandle.java` — 独立附属插件已有服务边界。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/JavaRailwaysServiceRegistration.kt` — 上述能力真实注册到 ServicesManager。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/util/BukkitThreadGuardBridge.kt` — main identity 与 simulation context 的实际映射。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/TrainReverseBehaviorListener.kt`、`debug/TrainDebugEventListener.kt`、`BukkitPlugin.kt` — 领域 listener 的用途、注册与释放。

## Code Patterns / Static Test Evidence

- `Core/src/test/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinatorTest.kt:232` 暂停真实 processor，断言 exact ticket 未完成且 world frame 未前移；`:263` 断言 processor 异常 Stall、候选不发布；`:288` 断言 start 后不可变更 membership。
- `Core/src/test/java/work/art1st/javarailways/core/util/TickManagerImplTest.kt:19` 明确断言 generic postTick 后 platform commit 次数仍为零，drain 后才在 consumer thread 执行。
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultRailwayObservationServiceTest.kt:20` 断言 per-epoch 去重及 close；`:42` 断言普通 listener failure isolation 和 exact owner cleanup；`:93` 拒绝错误 ticket epoch。
- `Core/src/test/java/work/art1st/javarailways/core/api/event/RailEventBusTest.kt` 已有 bus segregation、close、wrong-thread、failure isolation 的测试源码；这不能当作三个旧类已发布的证据。

本轮仅阅读现有测试，不运行或新增测试，不援引旧批次测试成功作为本轮验证。

## External References / Versions

- 无外部检索或外部运行证据。本问题的事实来源是当前 checkout 源码、KDoc 与本地契约；不依赖外部框架行为推断。
- API 发布定位来自 `Core/src/main/dokka/Module.md:5` 的 `1.0-SNAPSHOT` 说明；本报告不涉及持久化格式或版本升级。

## Related Specs / Task Context

完整读取 `.trellis/workflow.md`、本任务 `prd.md → design.md → implement.md`，并读取：

- `.trellis/spec/javarailways/core/event-bus.md` — 同步 dispatcher、payload/thread/lifecycle、异常隔离与无 publisher 限制。
- `.trellis/spec/javarailways/core/runtime-publication.md` — exact ticket、candidate publication、两种 processor 与 generic tick 分离。
- `.trellis/spec/javarailways/core/api-boundaries.md` — value/live、候选 API、composition seam 与可信平台边界。
- `.trellis/spec/javarailways/core/render-publication.md` — immutable frame、latest-wins、Core commit 与平台显示分离。
- `.trellis/spec/javarailways/basic/threading-and-publication.md`、`basic/api-boundaries.md` — command/observation、安全 owner 与公共投影。
- `.trellis/spec/javarailways/simpleato/reactive-signal-epochs.md` — signal source 同步路由与下一 epoch restriction 消费。
- `.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md` — simulation context、target scheduler 与插件卸载 owner。

## Caveats / Not Found

- 未发现三个旧类型的 publisher、handler、concrete render event 或测试用例；该结论只覆盖本次检索到的仓库源码。仓库外不存在插件的前提来自用户确认，不是源码检索能力的结论。
- 这是静态产品/API 评估，不是完整模块复审、并发诊断复现、Folia/客户端验证或新增功能验收。未构建、未运行测试、未操作 Git，代码及 spec 均未修改；只新增本研究文件。
- role-isolated research 指令禁止加载 `implement.jsonl`/`check.jsonl`，因此按主会话协调采用任务三份 Markdown 与手动完整读取相关契约；未将这些 manifest 的内容或其中可能存在的其他结论当作已加载证据。
- 当前 `reactive-signal-epochs.md` 尾部仍保留具体 Stage 2 策略/呈现尚未完成的表述；本报告以当前 SimpleSignals 的真实 source 注册和 model submit 为已实现接线证据，不据该旧措辞断言这些能力尚不存在，也不据源码接线声称已通过现场验证。
- event-bus spec 将中间 priority 写为 `NORMAL`，实际 enum 为 `MEDIUM`（`EventListener.kt:15`）；本次未修改规范。此差异与旧空壳是否有产品必要性是两个问题。
- 未公开且确有可能合理的需求已分别列出；不能把“当前已有 service”扩大为所有未来用途都有等价替代，也不能因可能有未来用途就新增通用框架。
