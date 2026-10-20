# 子任务设计

## 技术方案

提取既有逐车行为的逻辑与效果接口并由实际/模拟共同调用；每车/每模拟新建JS状态。保留方向、拒绝、延迟效果汇总、取消和失败，模拟验证效果启动语义并以逻辑时钟推进，测试按需注入观察器。删除只按固定配置时长返回成功的执行替身。领域扩展按目标World创建实例；平台Feature排除，其他必需Feature不得被旧opt-in筛选悄悄丢弃。不添加通用JS沙箱或Bukkit模拟器。

完整交界契约见[父设计](../10-18-isolated-simulation-runtime/design.md)，本任务不能自行改变用户已确认的模式/事件/失败语义。

## 代码ownership

Bukkit现有CarBehavior/TrainBehaviorRuntime、逐车renderer调用及operation/profile capture；Basic TrainOperation契约；SimpleATO simulation gateway、扩展registry和共同安装consumer。

派发前按实际最小变更文件限定ownership；其他子任务和会话可能同时存在，不能恢复或覆盖他人改动。源文件或持久化格式若需扩大范围，先记录真实依赖并更新设计。

## 依赖与回滚

依赖10-18-world-runtime-isolation；本子任务最终联合验证也依赖10-18-simulation-signal-features。
每批独立验证/提交；回滚仅撤本子任务提交，保留原合并基线与无关WIP。

## 2026-10-19研究后实施边界

最小差距是Bukkit模拟factory仍按modelActionDurationTicks估算成功、遗漏真实JS/逐车方向/onReverse和操作失败。共同CarBehavior、CarOperationBatch、TrainBehaviorRuntime已经有逻辑/效果出口，且同模块factory能把既有Basic TrainOperationGateway注入SimpleATO Dispatcher，因此不移包、不加Basic/Graal依赖或公共SPI，不改持久化值类型。

- **A 行为owner**：在Bukkit既有behavior/operation包增加detached `CapturedCarBehavior(script,actions,animations)`及SIM operation adapter，直接复用原三逻辑类；仅如真实复用所需抽出方向/metadata小helper和原ticket。A拥有新类型、adapter、无显示逐车extension、既有Animation只读scene索引入口、必要行为/common ticket文件及对应测试。animation能力只捕获实际runtime非空索引，复用既有转换/scene normalization，不构建显示/不恒true。
- **B 捕获/装配owner**：CarExtension只读capture helper、SimulationControlProfiles逐车源和安装方向、现HTTP factory/blueprint/seed、SimpleATO目标owned-resource close接线、删除duration-only gateway及旧测试，维护对应capture/fixture测试。B使用A定义，不另造同名值。TrainExtension.resolveActions仍有实际命令consumer，不误删。现模型快照/格式和source package语义保留。
- **生命周期**：每target每exact car独立CarBehavior/Context和延迟状态；在现有Bukkit Core plugin slot挂无显示RenderableExtension，让真实destroy结束其动作、联结/解编保留。同源两SIM无共享Context/ticket/queue；generic tick只推进时间，effects/JS在既有owner commit执行。target退出必须先确认physics/generic真终止，再逆序关闭gateway/tickets/Context和事件注册；创建失败同样清理，不加watchdog或轮询框架。
- **操作/事件**：OpenDoors、CloseDoors、ExecuteModelAction真实执行，SetCabLights/Announce与LIVE同样Rejected；按实际无手动session控制端公式捕获各车方向，不硬编码HEAD；WORLD_TRAIN blueprint记录每车安装方向，非复制在线驾驶状态。注册现MainRailEventBus的目标World本地onReverse消费者，关闭退订，LIVE平台listener继续不取SIM renderer。取消已接受效果可继续但ticket不能变成功。
- **平台边界**：动画/声音仅验证并执行声明效果启动语义，生产不保留历史，完成仍是全部已接受效果启动而非播放结束。静态animation能力使用真实定义。LIVE不可见renderer的held scene缓存/无模型无scene失败是既有Bukkit显示状态，不复制到静态设施模拟，也不为追求表面相同改LIVE规则；对比同一合法定义下的JS决定、方向、延迟/拒绝/完成。服务端视距/实体可用性和声音实际播放属于明确排除范围。

根会话拥有任务/规范/文档/最终证据和联合验收分工。现有信号候选a509的源码字节已经逐文件快照，所有实现者保留并适配，不恢复HEAD或丢弃旧WIP。若真实结构变化暴露控制profile注册缺口，由对应研究给出确定caller后纳入最小必要接线，不能猜测扩成框架。

### 结构变化的确定依赖缺口

完整inventory确认：SIM原seed-only profilesByTrainId在split产生新train、重建ATO或吸收无controller车时缺失/长度错误，LIVE已有per-car resolver和train extension的split/link snapshot继承。B须沿用当前extension机制保存捕获TrainProfile和每exact car的CarProfile，原车随结构转移保留、train配置由真实split/link hook传递；ATO factory从当前组成读取并向HandleTrainActuator提供既有profileResolver。A的无显示逐车extension同时承载该车只读CarProfile（不新增第二plugin slot）。这只补实际执行依赖，不建立新配置/重载系统、不复制在线controller。

### 联合验收ownership

第三位实现者只拥有BukkitPlugin test-only SimpleSignals依赖、单个Java测试桥和新联合测试文件。测试调用正式SimpleSignals loader/真实PreparedSimulationFeature与Bukkit HTTP factory，在真实Core/Basic/SimpleATO链检查逐车JS、信号/碰撞/时刻表及两target独立推进；不增加生产反向依赖或public接口，不反射、不复制factory业务。测试用Java桥只跨Kotlin internal在JVM已存在的public构造边界，Kotlin生产visibility不放宽。

## 2026-10-20 review follow-up: exact retirement and bounded simulation state

User identified three concrete P2 lifecycle defects on the aligned ae862ac8 candidate. This follow-up stays in the same reviewed task/worktree, with a byte-preserved48-source baseline at ../simulation-review-fixes-baseline-20261020. Prior2491-test success is historical until modified sources are retested. No commit/merge/deploy.

- Signals manager ownership: on install failure retire only the epoch/planning/inspection objects registered by this exact installation before closing its world-local engine; partial failure and sameUUID reinstallation must not leave stale or remove new callbacks. Reuse existing identity-specific host registration/retirement contracts, preserve short directory locks and target engine lifetime. Production scope is manager plus minimal established host retirement boundary only if required by actual API.
- Bukkit behavior ownership: replace always-on effects history with an optional injected observer used by tests; default production execution validates animations and completes actual semantic effects without accumulating records. No bounded ring buffer/history cache added.
- Bukkit gateway ownership: remove the append-only shadow car ownership or retire exact cars on actual destruction using the existing lifecycle. Core/World actual car destruction and true-worker-exit gateway close remain authoritative; split/link retain live exact car runtimes, cancellation/completion and cleanup failures remain observable. No weak references/GC timing assertions or extra framework.
- Regression boundaries: partial install failures including later source registries, exact identity cleanup before engine close, sameUUID reinstallation race; production no-history effect execution and injected observer behavior; true destroyed-car disposal plus surviving-car close, existing per-car JS/door/timetable/coupling paths.

Implementation split: review_signal_rollback owns manager/tests; review_behavior_retention owns car/gateway and their actual observer test consumers/necessary internal factory injection. Root owns specs/docs/task evidence and one Gradle token. Independent Trellis check follows both implementations; current affected module tests/build/API gates refresh by exact source fingerprint.

## 2026-10-20 parent acceptance closure

Current P2 fixes are checked at52-source c781ed4f; user approved continuing without immediate merge. Parent review found two test-evidence gaps against existing requirements, not a new feature contract: actual fromCore barrier lacks LIVE-SIM/SIM-SIM mode variants; source preservation lacks one non-empty LIVE frame/claim/Dispatcher/signal before-after proof across simulation capture/run/close.

Minimal ownership: acceptance_observation_matrix changes only DefaultRailwayObservationServiceTest mode matrix; acceptance_live_preservation changes only SimpleSignalsWorldRuntimeManagerTest using existing real official-rule/live-domain fixtures. No product API or runtime/persistence changes planned. Root owns parent/child acceptance records and single build token; independent parent_acceptance_check reviews exact tests and current gates. All prior source/recovery artifacts remain preserved. No commit, merge, push, deploy or archive in this batch.
