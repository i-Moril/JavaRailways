# AI Task B：物理时序与一致性加固

> 2026-10-14决策更新：用户批准移除 `SweptOccupancyIndex` 的 `maxCandidates` 截断。碰撞查询检查同一有限occupancy snapshot中全部局部候选，不以人为阈值漏掉真实候选；相关复杂度/压力评估仍可作为独立性能观察，但不得恢复候选数量截断。

> 状态：核心代码已实现；occupancy benchmark 与 Paper/Folia 实机验证待执行
> 类型：confirmed concurrency/time-step risks
> 范围：PhysicsCoordinator、轨道运行输入、Controller command、SpeedSafetyMonitor；occupancy 仅 benchmark 后按证据处理

---

## 1. 重新评估结论

Task B 的问题仍然成立，但上一版把一个较长 `deltaT` 在单个 epoch 内写成：

```kotlin
substeps.forEach(controller::tick)
```

这是错误的抽象层级，已经全部撤回。当前 `controller.tick()` 不只是积分，它还包含 ATO 感知/规划、命令消费、动力学、移动和事件处理；`CoreTrain` 的 active/published 双缓冲也只为一次完整 physics tick 后 publish 设计。因此不能在不 publish 的情况下重复完整 Controller tick。

新的实现原则：

1. **不新增 coordinator-level substep API。**
2. 对墙钟卡顿产生的大时间间隔只执行一个现有语义的**完整 physics tick/epoch**，并把实测 `deltaT` clamp 到独立的 `maxPhysicsDeltaMillis`；被丢弃的墙钟时间不形成 catch-up 债务。
3. clamp 必须可观测：metrics 记录 raw/applied/dropped delta 和 clamp 次数；不能只改数值而不给诊断。
4. topology 编辑不在 physics 热路径加锁或建立 epoch snapshot；改为维护前置条件：受影响轨道停运、释放运营资源并等待当前 ticket 完成后，才允许在 simulation owner 中修改。
5. occupancy 当前只有候选收集成本风险，没有代码证明的漏碰撞缺陷；先 benchmark，不预先修改 API。

---

## 2. 已确认风险

### 2.1 Physics epoch 没有冻结运行输入

`PhysicsCoordinator.runEpoch()` 只快照 Controller 列表。worker traversal 仍读取 live：

- `RailView.activeRail`；
- Rail metadata；
- signal collection；
- Controller command state。

正常 Bukkit server tick 由 simulation lock 串行并等待 ticket，降低了竞态概率；fallback timer 仍可独立拥有 in-flight epoch。产品决策是不为在线改轨提供并发热切换：玩家必须先将受影响区域停运。编辑入口等待已捕获的当前 physics ticket 完成并再次验证维护条件；验证失败则拒绝编辑，不等待全局锁，也不让修改与在途列车并发。

### 2.2 Safety 有独立异步写路径

`SpeedSafetyMonitor` 目前注册到 `TickManagerImpl`，与 PhysicsCoordinator 是不同 worker 路径，并直接调用 `train.derail()`。derail buffer 有同步保护，因此不能宣称已经发生内存损坏；真实问题是：

- 列车 mutation/publish 所有权不唯一；
- grace tick 与 physics epoch 没有明确关系；
- 基线中的 `DerailEvent` 是 physics-worker event，不能从 simulation thread 直接投递到 physics bus。

### 2.3 大 deltaT 当前直接进入完整 Controller tick

当前名称 `MAX_STEP_MILLIS` 有误导性：它不限制物理 `deltaT`，而是“单个 epoch 的墙钟执行耗时达到多少算一次 overload”的阈值。Task B 实施时统一改名：

```text
maxStepMillis                         -> epochOverloadThresholdMillis
MAX_STEP_MILLIS                       -> DEFAULT_EPOCH_OVERLOAD_THRESHOLD_MILLIS
新增 maxPhysicsDeltaMillis            -> 传给 Controller 的物理 delta 上限
```

卡顿或 fallback 切换产生的大间隔目前会未经限制地传给 ATO/动力学/移动逻辑。

### 2.4 Occupancy 是待测性能风险

`SweptOccupancyIndex` 会收集、去重并排序全部局部 candidate train；2026-10-14已批准去除此前排序后的 `maxCandidates` 截断。候选生成、去重和排序本来就处理完整集合，截断不能有效界定这些成本，反而会造成真实候选漏检。每个候选还会与自身及查询train的spans进行比较以确定body overlap、separation和exact overlap；多轨长列车会提高该比较成本。可以benchmark病态共享桶下的bucket引用、span比较、临时分配、排序与精确检查成本；优化必须保持穷尽当前有限候选集合，不得重新加入数量截断。

---

## 3. 非目标

- 不深拷贝全部 Rail geometry、signals 和 world collection。
- 不引入通用刚体物理。
- 不实现不同 Rail ID 的平交/菱形交叉冲突。
- 不自动生成三维 PhysicalConflictZone。
- 不重写 CollisionEvent/联挂业务语义。
- 不新增通用插件 phase framework。
- 不为 topology mutation 增加全局读写锁、epoch permit 或 traversal snapshot resolver。
- 不支持有车运行时在线热改轨；不满足维护条件时直接拒绝。
- 不建立 elapsed-time catch-up backlog，也不连续补跑多个 physics epoch。
- 不在一个 epoch 内循环完整 `controller.tick()`。
- 不复用墙钟执行耗时的 overload threshold 作为 delta 上限；`epochOverloadThresholdMillis` 与 `maxPhysicsDeltaMillis` 是两个独立概念。
- `maxPhysicsDeltaMillis` 的产品决策值为 200ms；bootstrap、fallback 周期和 overload threshold 仍各自保持独立语义。

---

## 4. 实施任务

## B1. 明确定义“停运后编辑”的维护契约

Task B 不支持运行中的轨道在线热修改。**先停运再编辑是玩家和上层运营流程的前置责任**，不为此实现自动 route reachability 分析或维护状态机。

停运流程应完成：

1. 停止会使用受影响区域的 Controller/ATO/时刻表任务；
2. 等待列车驶离受影响 Rail 和编辑会改变连接关系的邻接端点；
3. 释放相关 RouteSection allocation、道岔锁和其他运营资源；
4. 再发起编辑。

编辑入口只做低频、便宜且代码可证明的防误操作检查：当前局部占用、已暴露的 route lock/allocation，以及已捕获 in-flight physics ticket 的完成状态。它不尝试推断任意 Controller 的未来路径。等待 ticket 后重新检查局部条件，再由 simulation owner 串行执行编辑。

“列车速度为 0”本身不等于停运。无法被现有 API 可靠检查的运营条件作为明确调用契约记录，不为它新增全局扫描或状态机。

### RED

- 受影响 Rail 被列车占用时，connect/split/merge/remove/turnout edit 被拒绝且不产生部分修改；
- 现有 API 可见的活动 route allocation/道岔锁未释放时被拒绝；
- fallback ticket 运行期间发起编辑时，编辑先等待该 ticket，随后重新验证；
- 等待期间区域重新被占用时，编辑失败而不是继续执行；
- 完全停运时编辑成功，且 physics 正常路径没有新增锁、permit 或 snapshot 开销。

## B2. 收口高层编辑 transaction，但不与 physics 加锁

一次 topology 编辑仍可能修改多个字段，例如清除 active branch、替换 `nextRails`、设置新 branch 和更新 metadata。即使没有 physics 并发，操作中途异常也不能留下半拓扑。

实现要求：

- 优先复用现有 `RailTopologyEditor`、Wand transaction/rollback；仅当 RED 证明某个入口会留下半状态时才补充高层 API；
- transaction 开始前执行 B1 的局部 edit-time checks；可检测条件不满足时 fail fast；
- mutation 只由 simulation owner 执行，禁止后台 worker 直接写 topology；
- 编辑失败时恢复 transaction 前状态，恢复失败必须上报，不静默忽略；
- 审计 `mutableNextRails` 的直接写入；若兼容性允许则收窄为 internal，否则明确将直接写入标为不受支持的低层 API，不为此包装每次读取；
- 不给 physics epoch、Rail getter 或字段 setter 添加读写锁；
- 不等待“所有未来 epoch”，只等待编辑请求发起时已经捕获的当前 ticket。

B2 解决的是**维护操作自身的完整性与入口收口**，而不是让运行列车与改轨安全并发。

### Controller command 是另一类输入

Controller command 与停运改轨无关。命令使用一个原子 immutable value或现有 revisioned queue，在每个完整 physics tick 开始捕获/消费一次，保证 tick 开始后提交的命令从下一 tick 生效。不引入完整 event sourcing。

## B3. 串行化 SpeedSafetyMonitor 和脱轨请求

推荐的最小边界：

1. `WorldManager.tickEnd()` 捕获并等待明确 physics ticket；
2. safety 读取该 ticket 对应的 published train state；
3. grace 以成功发布的 physics epoch 计数，同一 epoch 重复 evaluate 不重复；
4. safety 产生 `DerailRequest(trainId, reason, observedEpoch)`，由 simulation owner 应用；不要让通用 async tick worker 直接改 Train；
5. derail 状态由 simulation owner 应用，并通过 `MainRailEventBus` 发布 `DerailEvent`；physics worker 只记录 pending derail state，不直接发布该事件。不要放宽 Physics bus 的线程契约。

若 fallback 期间没有 server tick，则 safety 可在下一次 simulation tick 对最新 published epoch 评估一次；除非产品明确要求离线 fallback 每个 epoch 都执行 safety，否则不新增 Coordinator plugin post-processor。

### RED

- safety 与阻塞 physics worker 并行时不修改 active state；
- 同一 epoch 不重复消耗 grace；
- 跳过多个 epoch 时明确采用“一次观察”还是“按 epoch 差补计数”，默认前者，避免假定中间持续超速；
- derail request 应用后 frame/status/event 的线程和 epoch 归属明确；
- 关闭 derail 功能时不产生 request。

## B4. 大 deltaT：单 tick clamp，不追赶墙钟时间

### 核心语义

为墙钟驱动的 main/fallback wake 定义独立的 `maxPhysicsDeltaMillis`：

```text
rawDeltaMillis = 相邻 physics epoch 起点的墙钟间隔
appliedDeltaMillis = min(rawDeltaMillis, maxPhysicsDeltaMillis)
droppedDeltaMillis = rawDeltaMillis - appliedDeltaMillis
```

- 每次 wake 最多启动一个完整 physics tick/epoch；
- Controller、ATO、移动、事件、publish 和 occupancy rebuild 都只执行一次；
- `rawDeltaMillis` 超限时只减慢模拟时间，不创建 catch-up batch，不补跑历史 tick；
- clamp 本身不使 Coordinator STALLED；STALLED 继续表示执行失败或已有 wall-time overload 策略触发；
- metrics 同时暴露 raw/applied/dropped delta、是否 clamped 和累计 clamp 次数；
- 日志应限频，避免持续卡顿时每 tick 刷屏；
- 现有 `MAX_STEP_MILLIS`/`maxStepMillis` 在同一提交中改名为 `DEFAULT_EPOCH_OVERLOAD_THRESHOLD_MILLIS`/`epochOverloadThresholdMillis`，不能与 `maxPhysicsDeltaMillis` 混用。

显式调用者提供的 `stepSimulation(deltaMillis)` 不是墙钟卡顿输入。它应要求调用者传入合法的单 tick delta；超出上限时拒绝并给出清晰错误，而不是悄悄 clamp，保证离线仿真可重复。

### RED

1. 墙钟间隔 500ms、最大 delta 200ms 时只调用一次 Controller，参数为 200ms。
2. ConstantSpeed、Manual/Realistic dynamics、Kinematic ATO 和 SimpleATO 都只推进一个完整 tick；ATO tick、命令消费和 provider transition 不重复。
3. epoch 只增加一次，只 publish 一次，只重建一次 occupancy。
4. metrics 保留 `raw=500ms`、`applied=200ms`、`dropped=300ms` 和 clamp 标记。
5. 下一次正常 wake 不补偿前一次 dropped delta。
6. 非有限或非正 delta 明确失败；显式 simulation step 超过单 tick 上限时拒绝而不 clamp。

## B5. Occupancy 只做 benchmark 决策

构造单桶 100/1,000/10,000 个不同 train span，分别测试 overlap/no-overlap、多 own spans 重复候选，记录：

- query wall time 分布（不要把宽松的 5 秒断言当 benchmark）；
- candidate train/span 数；
- 临时分配和排序成本；
- 真实服务器典型桶密度。

只有 benchmark 证明问题后才允许最小优化：bucket-local exact filter、按 train ID 去重、减少全局候选临时对象。所有优化仍须穷尽局部候选，不得重新增加 `maxCandidates` 截断或漏检，也不增加overflow状态机。

---

## 5. 推荐提交顺序

Task B 必须拆成独立小提交：

```text
B1 test: reject topology edits until affected rails are out of service
B2 fix: centralize validated maintenance transactions without physics locks
B3 test: published-epoch safety and derail request ownership
B4 refactor: serialize safety decisions without weakening event buses
B5 test: wall-clock delta clamp without catch-up
B6 fix: one complete physics epoch with observable bounded delta
B7 bench: occupancy pathological buckets
B8 perf (conditional): occupancy allocation reduction
B9 docs: document epoch, delta clamp and event ownership
```

不要与 Task A 合并为一次大重写。

---

## 6. 验证

```bash
./gradlew :Core:test
./gradlew :Basic:test
./gradlew :BukkitPlugin:test
./gradlew :Core:test :Basic:test :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar
./gradlew build
```

Paper/Folia 实机至少验证：

- fallback 拥有 epoch 时编辑道岔；
- server stall 后单 epoch delta clamp、无补跑；
- safety derail 的事件线程和 frame；
- 受影响线路未停运时所有编辑入口均拒绝操作。

---

## 7. 完成定义

- [x] RED 覆盖 connect/split/remove 的局部占用防误操作，包括车身跨 Rail 和第三腿；停运运营流程仍由玩家/上层负责。
- [x] topology transaction 等待已捕获 physics ticket，以完整车身包络检查受影响 Rail；Basic 编辑入口同时拒绝可见的活动进路 allocation。
- [x] physics 热路径没有新增 topology 读写锁、permit 或 snapshot resolver。
- [x] safety 只对 published epoch 产生 request，由 simulation owner 修改 Train。
- [x] `DerailEvent` 归 Main bus；physics-origin transition 在 frame publish 后只通知一次，Physics bus 线程契约未放宽。
- [x] `MAX_STEP_MILLIS`/`maxStepMillis` 已改为清晰的 epoch overload threshold 命名。
- [x] 墙钟大 elapsed clamp 到独立最大 delta，每次 wake 仍只执行一个完整 physics epoch。
- [x] raw/applied/dropped delta 与累计 clamp 次数可观测，dropped delta 不形成 catch-up 债务。
- [ ] occupancy 只有 benchmark 证明后才优化；当前实现未修改。
- [x] 三模块 539 tests 零失败（7 skipped），shadowJar 通过；根 build 仍被既有 Java 25/TinyRemapper 0.12 问题阻塞。
