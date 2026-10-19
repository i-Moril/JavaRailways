# Research: World owner、共享锁与跨 World 管理事务

- Query: 去除全局 simulationLock 后，哪些业务必须保留跨 World 互斥；最小 owner 入口、调用点和回归应如何接线？
- Scope: internal；只读基线源码，唯一写入为本文。
- Date: 2026-10-19
- Baseline: 独立工作区 `.worktree/world-runtime-isolation`，派发基线 `8f156900`。本文行号为实施前基线，后续实现会移动行号。

## Findings

### 1. 当前锁提供两件不同的事情；单删锁会丢失实际保证

`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/util/BukkitThreadGuardBridge.kt:9–36`：

- `simulationDepth` 只是词法线程分类，使 `isMainThread()` 通过；不包含 World、entity 或 region ownership。
- 唯一 `ReentrantLock` 包围所有 `runInSimulationContext`，串行普通世界、模拟和平台交互。
- `runOutsideSimulationContext` 释放该线程全部重入次数，清除标记，结束后恢复锁次数和标记。
- 全生产源码检索 `runOutsideSimulationContext` **没有调用者**，只有定义和 `BukkitThreadGuardBridgeTest:36–49`。不得声称区域等待生产路径已经接入；后续 owner suspension 能力需用测试证明，新增真实等待才按需接线。

`Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt:316–402` 的 `processBetweenFrames/withStructuralMutation/withFrameBoundary` 关闭新 epoch 准入并 await exact ticket，但不是 host 串行锁：两个调用可以同时增加结构计数并同时执行 action。`processingBetweenFrames` 只拒绝同时 process，不会排队，也不覆盖所有 structural entry。

因此最小改法是 **每个具体 CoreWorld/协调器持一个可重入 owner 串行入口**，并由已有 process/structural 入口共享；不是仅删 `simulationLock` 或只传 World 当标记。物理 worker 不要取得这个 host owner 锁，否则 host 持锁 await worker 将死锁。继续用已有 stateLock + 临时准入计数关闭 epoch admission，stateLock 外等待和执行回调。

### 2. WorldManager 的调度等待是第二个真实跨世界阻塞点

`BukkitPlugin/.../logic/WorldManager.kt:97–102,465–477,480–566`：

- 全局 fixed-rate task 持全局锁调用整个 tick。
- tickStart 逐世界 wake + generic preTick；tickEnd 又逐世界执行 `coordinator.processBetweenFrames`，内部 await 当前 ticket。
- 即使换成每世界锁，A 的 await 仍占用唯一 global task，B 的 Dispatcher/帧间操作无法执行。
- `WorldManager:497–566` 的顺序为 drain operations → observation publish → admission tail release → pending derail / speed safety → train visual dispatch → Dispatcher → generic postTick。该顺序是已有业务合同，分调度不能重排。
- `:568–629` 是平台 commit、debug、标记和定期 snapshot，属于相应 World 后续阶段，不应在等待其他 World 的全局循环中继续运行。
- `:65–95,802–806`：world map/processor map多为 concurrent；`recentServerTickMetrics` 的 ArrayDeque 与 shared aggregate 目前依赖全局串行，改并行后需保留一个小型 metrics owner/锁或用每 World value metrics 汇总，不能各任务直接写同一 mutable map。

建议 global task 只发布这次时间/唤醒意图并驱动各 World 的 bounded/coalescing owner 调度。每 World 至多一个执行中和一个最新待处理，不为每 tick 堆积任务。A 的等待只占 A owner 的执行路径；B 与模拟各自推进。沿用现有 exact ticket 和 frame gates，不另建第二物理循环。卸载先撤 lookup/禁止本 World 新调度，再等它自己的已在执行阶段，保存最后成功 exact frame，清理相应资源。`WorldManager:366–443` 当前 shutdown/capture/cleanup矩阵应保留。

### 3. 跨世界业务事务确实存在，但不是普通 tick 的共享 owner

#### 3.1 列车资源重载必须冻结所有相关 World

`BukkitPlugin/.../logic/TrainResourceReloader.kt:27–67` 先 worker prepare，后 global 发布；`:49–56` 已把所有 live CoreWorld 按 Bukkit UUID 排序，递归取得 frame boundary，最深层 `publishPrepared` 才做检查与发布。

`:80–138` 会联合检查 linked consist 的多个资源 type，拒绝相关类型后再准备 survivor，发布 catalog 并安装所有列车 controller/profile/resource binding。只各 World 分别更新会产生 catalog 已新、某 World 仍旧配置的窗口。应保留这个跨 World 检查/提交边界，并按相同稳定顺序取得 **owner + frame admission**。磁盘 prepare/snapshot write保持离线，不持 World owner。SNAPSHOT策略同样在发布后重验 held train（`:70–78`）。

#### 3.2 Contributor membership 是全世界路线合同

`BukkitPlugin/.../api/internal/DefaultInfrastructureResourceService.kt:300–334`：register 和 registration.close 均要求 **全部 World** `hasRouteState()==false`。现行规范明确 active route 之后仍可申请 suffix，不允许策略集合变化；不能把它缩成“只检查当前 claim 是否空”。

检查全部 World 到增删 membership 期间，新 route request必须被挡住。单独保留 `synchronized(lock)` 不够：route runtime 的 request/claim 并不全在这个 monitor 中。

#### 3.3 Provider close 与统一 planned unregister 必须保持检查-提交原子性

`DefaultInfrastructureResourceService:363–396`：单个 provider close 扫描所有 World 的 pending compiled request、claim/release queue 和依赖，全部空才删 type registration。

`:685–747`：统一 unregister 检查 requester、provider、infrastructure dependency、contributor route state，之后还调用 signal `requireOwnerUnused`；所有检查成功才 discard inactive route state、移除三类 signal source、provider/contributor、topology callbacks 和 disposable platform ownership。两侧不能分别提交。

`:754–795`：forced disable已不可取消。需移除死 callback，保留 route claim 和已应用 dependency，并只对确实受该 owner/provider/contributor影响的 World fail-close；不得把“整体停止服务”误报为 provider故障。

这些低频事务必须在有关 World owner/admission 全部保持期间完成，不能“逐世界检查→释放→最后删目录”。当前检查覆盖所有世界且 contributor可影响任意世界，因此首版采用所有已绑定 live World 是实际需求；无须建立依赖索引优化。

#### 3.4 玩家 handoff 只需要按玩家协调

`BukkitPlugin/.../control/TrainControlManager.kt:96–122` 已显式通过旧 World release future → 新 World submitBetweenFrames 连接 handoff。`:273–286` 只在所属 World 的 between-frame scope释放；保留此链，不能在新 World owner 中直接释放旧 World。

移除全局 owner 后，`:113–120` 的 pendingAcquisitions.remove + previous-session检查，与`:125–200` 注册会跨 World 并发。两个请求可能各自先检查到空 session 再都登记同一玩家；ConcurrentHashMap 不能保证双索引事务。

最小所需是 playerId 维度串行化 **检查+apply / release / retire双索引**，让同一玩家在任意 World最多有一个session。取得顺序是 World owner → player transaction；不持玩家锁 await Future，不同时跨World持owner。已存在 request identity与 exact train/controller复查保留，不新增session generation框架。`:309–320` retireWorld精确身份删除须同样保护，且不能退役新 World 的替代session。

### 4. 建议锁顺序和事务入口

必要层级分开：

1. 无World lifecycle/management mutex：只序列化 world集合注册/移除、reload发布、provider/contributor/owner管理事务。普通 tick、simulation step、route request不得持它。
2. 目标 World owner：普通领域操作只进这一级；多World管理事务按稳定 UUID顺序取得（同UUID重载的不同实例必须有确定实例归属，不用裸 UUID 字符串映射永久复用旧锁）。
3. 对应 coordinator frame admission：关闭新epoch，等待已捕获ticket，保留到整段检查/提交结束；worker仍不需要owner锁。
4. 已有 service registry短锁 / player短事务（不嵌套彼此的阻塞等待）。

管理 mutex的用处是固定目录快照和多事务的顺序，**不能直接复用 service metadata monitor**：普通请求会在owner内读取metadata，若管理线程持同monitor再等待owner，则反序死锁。先固定目录/管理意图，短metadata锁捕获列表并释放，再按顺序进owner，最后用短metadata锁提交。World集合发布/撤销需与这个管理边界一致，不能快照后插入未被冻结的新World。

多World管理入口应在未持单World owner的外层运行；禁止从任意 owner callback同步递归获取其他World反序owner。当前命令外层包整个simulationContext（见下节）会把 `/jr resources reload` 这类无World操作混入玩家World owner，因此迁移应按目标业务分派，不能给命令入口盲目绑定player.world。

runtime源捕获与service callback不得持 stateLock；wait不得持service metadata锁。无需Environment、全局currentWorld、逐事件验证或任意clone接口。

### 5. 全部 bridge 真实调用点迁移清单（不含 return 标签）

以下目录前缀均为 `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/`。

#### 确切单 World

- `logic/WorldManager.kt:101,457,462,780–781`：tick改各World调度；load/unload是lifecycle外层，已有 World后仅其owner。不要继续用一个helper包整个遍历。
- `http/TimetableHttpBridge.kt:223–250`：await目前把全部HTTP读写放global task +全局锁。World路径resolve后进入相应owner；纯目录/status无需World owner。`:481–489` simulation capture须在sourceWorld的frame boundary，且在worker只用捕获输入。
- `http/TimetableHttpBridge.kt:493–503`：整场模拟当前持全局锁；改为target模拟World本身的owner/step执行。工厂创建前没有targetWorld，不使用sourceWorld锁覆盖create或simulate。
- `control/TrainControlDialog.kt:29,143`：明确 `train.world`；收集行为target/发动作后Future回调仍走entity scheduler，不能把owner标记当entity权限。
- `tool/AbstractWand.kt:172`：event player对应World（先解析loaded World；非铁路World的纯物品处理保留entity owner，不捏造World）。
- `tool/RailWandTopologyEditPlan.kt:24–26`：已是CoreWorld extension，直接用this的owner +已有frame boundary。
- `tool/TurnoutWand.kt:150,228`：异步查找结果与动作反馈都已在player entity任务内，按实际解析的World加owner；保持query sequence/target revision等已有检查。
- `tool/RailObjectWand.kt:218,804`：entity回调内用player当时的World；需要与host领域读取互斥，不包整个玩家集合。
- `tool/RailWand.kt:660`：现包的只是枚举viewer并排entity任务，锁并未延伸到`:4040,4073` 的实际callback。应移到每个具体callback/World读取边界，不把全局viewer循环绑定某个World。
- `command/JavaRailwaysCommand.kt:757`：异步脚本编译返回后已捕获sourceWorldId，`:784–826` resolve原World后读destinations/validate/add；应围住这个确切World的领域段。失败/玩家消息不需要World owner。
- `api/internal/DefaultInfrastructureResourceService.kt:215` attach runtime：调用参数已有 `world`。
- 同文件 `:463` BoundRouteAdmissionService：boundWorld当前已有physicalWorld，进入它的owner，继续已有withStructuralMutation及publishOwnerSelectionChanges。
- 同文件 `:501` validateRouteContribution、`:607` consumeObservation、`:622/628` dependency增删、`:635/640` claim增删、`:675` admission read：都有worldId/bound runtime，改使用该runtime owner。
- 同文件 `:593` removeWorld：目录撤销与owner关闭需属于lifecycle边界，保留World实例引用直至其资源清理完。
- `api/internal/DefaultSignalEpochSourceService.kt:49` registerWorld、`:124/179` inspection/planning replacement、`:308` removeWorld：WorldBinding现在只有runtime对象，需要在composition attach时保存明确CoreWorld/owner；替换及移除只影响该World。

#### 无 World 的目录/生命周期或多 World

- `command/JavaRailwaysCommand.kt:61,104`：execute/suggest目前都是全命令锁；需要按命令实际World捕获领域段，资源重载/全服统计等是无World管理/只读路径。不可从sender永远推断目标World。
- `BukkitPlugin.kt:322–324`：启动枚举World，各自尚未发布/开始时创建并装配；共享插件注册顺序归lifecycle owner。
- `BukkitPlugin.kt:359–361`：停止逐世界卸载；保留停止入口→撤World lookup→各自shutdown/capture，不能全程持普通tick共享锁。
- `logic/TrainResourceReloader.kt:49`：上述稳定顺序多World管理事务。
- `api/internal/DefaultInfrastructureResourceService.kt:303,326,367,686,755,800`：contributor增删/provider close/统一owner卸载/forced disable/service close，分别按第3节保持相关World检查和提交。
- `api/internal/DefaultSignalEpochSourceService.kt:319`：service close与host停止协调，停止接纳目录变更，再逐World释放；不能影响未来同UUID实例。
- `DefaultInfrastructureResourceService.kt:265–290` provider首次注册当前**没有全局simulation wrapper**，只有短metadata锁。身份/handler验证可离线；目录增项应与有关lifecycle事务串行，不能默认把所有注册都当有活动World的大事务。
- `DefaultInfrastructureResourceService.kt:103–124` world目录register也是现有metadata短锁；需要与多World管理快照同一目录lifecycle保证。
- `runOutsideSimulationContext`：没有production调用，测试从“全局锁释放”改验证“精确owner重入次数释放、分类清除、恢复同一owner”，不得恢复错误World或错误depth。

### 6. Signal atomic replacement 是必须保留的并发特例

`DefaultSignalEpochSourceService.kt:71–118` 的 `replaceSourcesAtomically` **故意不取得全局simulation锁**；`:81–117` 当前持service monitor做候选/compile/atomic callback。`SimpleATO/src/main/kotlin/.../signal/SignalEpochRuntime.kt:121–144` 本来允许旧epoch捕获旧state继续运行，候选编译后bounded commit+volatile引用替换，topology变化经现有handoff同步。

`DefaultSignalEpochSourceServiceTest.kt:149–191` 明确测试 callback暂停时当下epoch完成旧source，下一epoch用新source。不能把整段compile放到World frame admission造成回归；保留离线编译、旧epoch不阻塞及同World replacement串行。对于当前service全局长monitor，应考虑每World binding串行与短目录锁拆分，避免A慢编译阻塞B登记/普通读取。新代码若调整此处需保持原bounded commit禁止reentry、callback失败原state不变的合同，而不是加更多防御分支。

### 7. 可复用测试与具体新增矩阵

- `Core/.../physics/tick/PhysicsCoordinatorTest.kt:768–934` 现有结构准入/timer/失败scope矩阵；新增两个host线程同World barrier证明串行，两个World barrier证明互不等待，worker await不持stateLock；process与structural共享锁且重入不二次drain。
- `BukkitPlugin/.../util/BukkitThreadGuardBridgeTest.kt:12–49` 现有全局互斥与runOutside测试应改为同World互斥、不同World并行、嵌套次数恢复、异常恢复；分类为true不能替代owner断言。
- `BukkitPlugin/.../api/internal/DefaultInfrastructureResourceServiceTest.kt:98` exact-ticket request阻塞；`:291` edit permit不等全局锁；`:738,830,878,922` planned/forced provider/contributor/跨owner资源；`:1035` provider pending readiness；`:1372,1452` dependency卸载。新增：management扫描A后暂停，在B同时尝试新request，不得穿入检查→提交窗口；登记新World同样不得躲过快照。失败检查确保两侧注册完全保留。
- `BukkitPlugin/.../api/internal/DefaultSignalEpochSourceServiceTest.kt:80,114,150` atomic validation/rollback/reentry与不阻塞epoch；`:195,250,290,334` 空replacement/forced disable/World lifetime；`:490` 两侧preflight无部分提交。新增A暂停replacement不阻塞B；owner统一unregister与inspection绑定建立并发不得绕过。
- `BukkitPlugin/.../logic/WorldManagerUnloadTest.kt:43,73,124` 保留失败关闭cleanup全覆盖及同UUID替代owner不被删；新增world tick调度coalescing、A等待时B owner后续阶段完成、A关闭只取消A pending，stop等待已有owner任务但不排入无限队列。
- `BukkitPlugin/.../control/TrainControlManagerDirectionTest.kt` 当前只有控制端换向测试，**没有跨World会话竞争测试**。需真实两个CoreWorld与同playerId、barrier同时接管，证明至多一个session/双索引一致；旧release失败/旧World卸载/最新请求替代情况不得误退役新session。
- `TrainResourceReloader.kt` 没有同名专门测试文件。新增稳定顺序冻结多个World的真实配置检查/安装、准备失败保持旧catalog/controller、无关World正常推进的边界。

## Files Found

- Core `physics/tick/PhysicsCoordinator.kt`：既有exact ticket、帧间准入和有限FIFO队列。
- Bukkit `util/BukkitThreadGuardBridge.kt`：全局互斥与线程分类混在一起的根源。
- Bukkit `logic/WorldManager.kt`：全局遍历等待、运行装配与卸载保存。
- Bukkit `logic/TrainResourceReloader.kt`：稳定顺序跨World配置发布。
- Bukkit `control/TrainControlManager.kt`：玩家跨World handoff与双session索引。
- Bukkit `api/internal/DefaultInfrastructureResourceService.kt`：provider/contributor lifecycle、认证准入与统一卸载。
- Bukkit `api/internal/DefaultSignalEpochSourceService.kt`：三类source目录、planned/forced lifecycle。
- Bukkit `http/TimetableHttpBridge.kt`：source capture与整场模拟共享锁。
- Bukkit command/Wand/Dialog调用点：见第5节全部清单。
- SimpleATO `signal/SignalEpochRuntime.kt`：与当前epoch并行的source prepare/swap特殊合同。

## Related Specs

- `.trellis/workflow.md`：先计划、源码/验证/未验证分开，研究持久化。
- `.trellis/spec/javarailways/core/index.md` 与 `runtime-publication.md:85–156`：exact ticket、有限drain、共享准入，不能拿await-only当结构互斥。
- `.trellis/spec/javarailways/basic/threading-and-publication.md`：完成epoch→observation→tail release→safety→Dispatcher，World safety latch局部。
- `.trellis/spec/javarailways/basic/route-resource-admission.md:245–359`：认证contributor集合稳定、provider准备及planned/forced卸载；本次已直接补读原文。
- `.trellis/spec/bukkit-plugin/backend/index.md` 与 `lifecycle-and-threading.md`：global分类不等于Folia ownership；统一owner检查/提交；atomic source replacement不阻塞epoch。
- 父设计 `../10-18-isolated-simulation-runtime/design.md` 与本子PRD/design/implement：不新增Environment；按World隔离；共同装配仍由其它研究覆盖。

## External References

无。全部结论来自当前内部源码、既有测试和已确认设计；没有依赖外部API版本推测。

## Caveats / Not Found

- 本研究没有改生产代码、运行测试、提交Git或执行平台live验证。上列新矩阵是建议，不能视为已通过。
- 本角色未加载implement.jsonl/check.jsonl（Trellis research角色隔离要求）；已直接读PRD、design、implement、父设计、workflow和相关spec。
- 当前scope不审计领域Feature capture/install全链，该部分由共同装配研究负责。
- owner suspension没有production runOutside调用；若声称某区域等待必须释放，请提供具体新增调用证据。
- perWorld host调度如果用固定小线程池，A/B同时卡满池仍可能间接阻塞模拟；具体资源模型需要实现时明确。承诺是执行归属隔离，不是JVM CPU/GC资源隔离。
