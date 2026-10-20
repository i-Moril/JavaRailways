# Historical preliminary review

Superseded for the current aligned candidate by [upstream-check-result.md](upstream-check-result.md) and [upstream-final-evidence.json](upstream-final-evidence.json). The material below records the earlier pre-upstream review; its pending gates are no longer current.

# 第四批逐车行为 Check 结果（初步）

状态：**PRELIMINARY**。A/B 生产源码已全范围静态复核；C 的 build.gradle/Java bridge/`newFullFeatureSimulationIsolationTest` 仍在编写中，尚未纳入。最终 8 模块门禁、shadowJar、`verifySignalModuleBoundaries`、Java API consumer、Dokka、frontend、docs 的 SHA/XML 证据由 root 在 C 完成后提供。本报告不替代最终结论。

基线：main 9ed27818 + 第三批未提交 27 源 a5095090（继承保留）。A/B 冻结字节 = `/tmp/simulation-behavior-owner-source.json`（A）/ `-capture-source.json`（B）。

---

## 一、已复核文件（A/B 生产 + 测试）

### A（行为 owner：共享 runtime / adapter / 无显示 extension）
- `BukkitPlugin/.../behavior/CapturedCarBehavior.kt` — `CapturedCarBehavior(script,actions,animations)` 数据类（L10-14）与 `SavedCarModelTree.captureAnimationCapabilities()`（L17-35）。
- `BukkitPlugin/.../behavior/CarBehaviorTargets.kt` — 共享 `Train.behaviorRelativeTail`（L9-10）与 `carBehaviorActions`（L12-18）。
- `BukkitPlugin/.../render/Animation.kt` — `internal fun sceneNames(): Set<String>`（L74），只读归一化 scene 索引。
- `BukkitPlugin/.../operation/SimulationCarBehaviorExtension.kt` — 无显示 `RenderableExtension<Car>`，复用 `CarBehavior`，效果仅记录启动，`schedule` 秒换算 + owner commit 执行（L70-88）。
- `BukkitPlugin/.../operation/SimulationTrainOperationGateway.kt` — SIM gateway：复用 `CarBehavior`/`CarOperationBatch`，复用 `ModelOperationTicket` PENDING 单次终态，onReverse 按 `event.world === world` 过滤（L29-35），close 幂等（L90-107）。
- 复用（未改）：`TrainBehaviorRuntime.kt`、`CarBehavior.kt`、`CarOperationBatch.kt`、`ModelOperationTicket`。

### B（捕获/装配 owner）
- `BukkitPlugin/.../extension/train/CarExtension.kt` — `captureSimulationBehavior`（L156-182）沿用既有 `operationalSnapshot` SNAPSHOT/FOLLOW_PACKAGE 分支；`captureCarBehavior`（L647-654）产出 detached 值。
- `BukkitPlugin/.../extension/train/SimulationTrainExtension.kt` — `TrainProfile` 承载 + `splitBefore`/`prepareLink` 继承（L11-13）；`simulationTrainProfileOrNull`（L17-18）。
- `BukkitPlugin/.../http/SimulationControlProfiles.kt` — blueprint 增加 `CapturedCarBehavior` 与 `installedReversed`；删除 `modelActionDurationTicks` 汇总。
- `BukkitPlugin/.../http/IsolatedTimetableSimulationFactory.kt` — 真实逐源 capture、真实 gateway 注入、seed 挂 behavior/train extension；删除 seed-only `profilesByTrainId`。
- `SimpleATO/.../timetable/simulation/IsolatedTimetableSimulation.kt` — `operationResourceFactory(CoreWorld): AutoCloseable?` 参数 + 关闭/失败清理；删除 duration-only gateway 注入。

### 测试（首轮 focused 60，SimpleATO 14 + Bukkit 46）
`SimulationTrainOperationGatewayTest`(14，含 A 后续加强的 2 例)、`CapturedCarBehaviorTest`(1)、`IsolatedTimetableSimulationFactoryTest`(7)、`SimulationControlProfilesTest`(5)、`IsolatedTimetableSimulationTest`(SimpleATO,14)、`CarBehaviorTest`(8)、`CarOperationBatchTest`(5)、`TrainBehaviorRuntimeTest`(4)、`BukkitTrainOperationGatewayTest`(3)、`CarControlSourceTest`(4)、`TrainResourceConfigurationTest`(2)。

---

## 二、确认成立的关键实现事实（含 file:line）

1. **共同逻辑无复制**：SIM 直接构造 `CarBehavior(script, actions, animate, sound, schedule, log)`（`SimulationCarBehaviorExtension.kt:34-45`），门操作经 `CarOperationBatch.execute`（`SimulationTrainOperationGateway.kt:68-87`）。方向公式抽到 `behaviorRelativeTail` 且 LIVE `TrainExtension.kt:57` 与 SIM `SimulationTrainOperationGateway.kt:78` 共用同一 helper；动作/声明合并抽到 `carBehaviorActions`，LIVE `CarExtension.kt:216` 与 SIM `SimulationCarBehaviorExtension.kt:55` 共用。无手写门侧/支持者/完成算法。

2. **无会话方向不回退 HEAD**：SIM 传 `controlTail = train.isReversed`（`SimulationTrainOperationGateway.kt:72-78`），与 LIVE `TrainControlManager` 无 player session 的 TAIL-iff-reversed 一致，最终 `relativeTail = controlTail xor (car.isReversed xor train.isReversed)`。

3. **动画能力用真实定义**：`captureAnimationCapabilities` 解析 `entry.inline ?: resolver.resolve(...)`（跨包 import），`part.animations.toRuntimeAnimationMap()`（复用现有空动画省略/同名 last-entry），`animation.sceneNames()`（归一化索引）。`SimulationCarBehaviorExtension.animate` 对 scene 先 `Animation.normalizeSceneName` 再 `in scenes`（L38-40），与 LIVE `Animation.hasScene` 归一化一致；空 scene 集 = 仅普通无 scene 播放，非恒 true。不构建显示、不携带 model/Context/live 引用。

4. **逐源捕获边界**：`CarExtension.captureSimulationBehavior` 在 SNAPSHOT 下读 pinned `savedPackageSnapshotHash`/`savedModelSnapshotHash`，FOLLOW_PACKAGE 读 `liveVersion.carPackages[carModelName]`；`captureCarBehavior` 深拷贝 `script`/`actions`（`source.behavior?.copy()`、`action.copy(animations=…,sounds=…)`）并取 `model.captureAnimationCapabilities()`。捕获不初始化 JS、不读 renderer、不保留源 World/manager。

5. **结构配置继承闭合旧缺口**：`SimulationTrainExtension.splitBefore/prepareLink` 返回新 `SimulationTrainExtension(trainProfile)`（train 级继承）；每车 `SimulationCarBehaviorExtension.carProfile` 随 exact `CoreCar` extension 容器迁移；factory 从当前 `train.simulationTrainProfileOrNull` + 各 `car.simulationBehaviorOrNull?.carProfile` 重建 ATO，不再依赖 seed-only `profilesByTrainId`（该 map 已删除）。

6. **取消/完成/关闭合同保留**：ticket 复用 `ModelOperationTicket` 原子 PENDING 单次终态（`execute` L47-53）；`close()` 先 `registration.close()`、再 `completeExceptionally` pending、再逐车 `close()`（L99-105），失败逐项隔离并 rethrow 首个 + suppressed；`destroy() = close()` 幂等。

---

## 三、与第三批 a509 信号候选的跨层影响（保留 + 复核）

- 装配顺序无冲突：`IsolatedTimetableSimulationWorld.create` 中 `operationResource = operationResourceFactory(world)`（先建 SIM gateway/onReverse listener）→ `OperationalWorldRuntime.create` → restore/materialize/attachSafety → `DefaultSimulationDomainTarget` → `preparedFeatures.install(target)`（信号）→ `seed(world)`（建车 + 挂 behavior extension）。信号 feature 安装早于 seed，gateway 早于二者；onReverse listener 在 seed 前注册、事件触发时 `car.simulationBehaviorOrNull` 已可用。
- a509 的 `BukkitPlugin.kt` `prepareSimulationDomainFeatures`（`worldManager.railWorlds.entries.single { it.value === world }.key.uid`）现被 B 的 `TimetableHttpBridge` 接入真实调用；`prepare` 返回的 `feature.id` 与 required owner 同源小写身份，与第三批 `DefaultSimulationFeatureService`/`SimpleSignalsSimulationFeature` 匹配。`DefaultSimulationDomainTarget` 只暴露 UUID/topology/方向/限速/三类 source/provider/contributor，不暴露 CoreWorld，行为批未放宽。
- 已核无行为批对 SimpleSignals main 的改动（SimpleSignals 全部为继承 a509 改动）；无反向依赖、无生产 classloader 新增。仅 C 的 BukkitPlugin `testImplementation(project(":SimpleSignals"))` 待 C 落地后复核静态图（研究已确认 main 无 cycle）。

---

## 四、低优先级观察（非阻断，供 root 决定是否记录）

1. `SimulationTrainOperationGateway.close()` 关闭顺序为 registration → tickets → Contexts（L99-104），与设计文档“逆序关闭 gateway/tickets/Context/事件注册”字面相反；因三者无依赖链且 future 幂等（ticket 先 FAILED，随后 `CarBehavior.close` 的 `done(failure)` 传播为 no-op），结果等价。**结论：安全，但建议在 spec 措辞上澄清“关闭不要求严格 LIFO，因各资源独立且幂等”。**

2. `onReverse` 的 `broadcast` 未包 try/catch（L32），异常会沿事件总线 listener 隔离机制传播（既有 `MainRailEventBus` 普通 listener 异常隔离保留），不会波及主流程；`whenComplete` 仅记日志。与 LIVE `TrainReverseBehaviorListener` 一致，无需改动。

3. `SimulationCarBehaviorExtension.effects` 仅累积、无上界（`effectSnapshot()` 每次 `toList()`）；随接受 effects 数增长，符合“效果记录随接受 effects 数线性”的设计，非逐 tick 扫描。

---

## 五、测试 → AC 映射（初步，C 落地后补 final）

**本子任务 PRD 验收：**
- “同一真实脚本两模式独立车厢状态/门侧/换向/动作选择/拒绝一致” → `SimulationTrainOperationGatewayTest`: `real per-car JS state is independent between cars and same-source target worlds`、`custom actions select actual supporters and retain real rejection semantics`、`formal doors capture installed directions and real reverse invokes only target world behavior`；`CarBehaviorTest`(8) 与 `TrainBehaviorRuntimeTest`(4) 复用保护同一真实逻辑。
- “多延迟/失败/取消/完成时机同业务规则” → `SimulationTrainOperationGatewayTest`: `all accepted delays wait for owner commits and cancelled tickets stay cancelled`、`unknown animation scenes and script initialization effects fail instead of succeeding`（A 加强②）；`CarBehaviorTest`: `missing effects and delayed failure report terminal failure once`、`door freezes relative end once and completes only every delayed effect`。
- “不触发显示/声音，诊断区分 World，两同源 SIM 不共享” → 无显示 extension 仅记录 `SimulationBehaviorEffect`；`log` 带 `[simulation ${runtimeId} car ${id}]`（`SimulationCarBehaviorExtension.kt:44`）；`SimulationTrainOperationGatewayTest`: `target close and real car destruction terminate pending work without affecting another target`、`real per-car JS state is independent...`。
- “运营扩展实例属目标 World，缺必需领域能力明确失败” → `IsolatedTimetableSimulationFactoryTest`: `capture rejects an installed domain feature without detached preparation`、`one captured snapshot creates new extension and domain state for every target`；`IsolatedTimetableSimulationTest`: `target feature receives real admission updates only from its local runtime`（静态接通）。真实联合留 C。

**父 PRD 映射（AC4/6/7 相关，静态 vs 真实本地集成 vs Paper/Folia 边界）：**
- AC6（行为）→ 上述 `SimulationTrainOperationGatewayTest`/`CarBehaviorTest` 是真实 shared JS 本地集成（Graal 真实执行），但 Bukkit 效果/视距是平台排除，已按 spec 记静态定义能力对照、非服务端可见性验收。
- AC4（独立推进）→ 本批本地集成只覆盖同 target 有序/关闭隔离；LIVE-LIVE/LIVE-SIM/SIM-SIM 的 owner/observation/logic barrier 属于第三批信号证据 + C 的 `newFullFeatureSimulationIsolationTest` 联合回归，最终由 root 汇总。
- AC7（完整门禁）→ 待 root 最终执行，未在本批提前宣称。

---

## 六、开放项（阻塞最终结论）

1. **C 未落地**：`BukkitPlugin/build.gradle` test-only SimpleSignals 依赖、Java bridge（跨 JVM public 构造边界）、`newFullFeatureSimulationIsolationTest` 联合 fixture —— 需复核其不反射/不复制 factory 业务/不扩大生产 visibility，并核静态 Gradle 图（研究已提示 Signals test 配置桥需首轮实际 resolve 验证）。
2. **A 后续加强的 2 个测试最终字节需重跑**：`SimulationTrainOperationGatewayTest` 的 `unknown animation scenes and script initialization effects fail instead of succeeding` 与 `accepted delayed effects retain exact cars and physical side through split and reversal`（首轮 focused 后 A 改动，当前字节已核，最终 XML+SHA 待 root）。
3. **最终门禁**：8 模块 `test`、`shadowJar`、`verifySignalModuleBoundaries`、`compileSignalApiConsumerJava`、Dokka、frontend、docs —— 全部由 root 单令牌执行并供 SHA/XML。
4. **本批 delta 与继承 a509 的整体候选指纹**：最终按 1124 源快照同时核本批 delta 与整体候选，不把 a509 历史测试沿用成本批联合证明。

（未提交、未 push、未 merge、未 deploy；本报告只读，除本文件外未改动任何 A/B/C/main 拥有文件。）
