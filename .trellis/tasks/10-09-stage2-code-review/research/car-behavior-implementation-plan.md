# Research: 每车 JS 与模型动作的最小完整迁移

- Query: 将现有列车级 JS 状态迁至每节车厢，保留模型包脚本接口，明确延迟效果、操作完成、Folia ownership 与 lifecycle。
- Scope: internal; 源码研究与实施建议，不是已完成实现。
- Date: 2026-10-11
- Snapshot: 研究时 Core ownership worker 正在修改 Car.train；以下 Bukkit 证据来自尚未迁移的生产代码。行号可能随并行工作漂移。

> 2026-10-12更新：Core归属已由`9a81133f`交付；真实开发服副本已只读取得，详见[存档研究](./dev-server-save-audit.md)。6个world的current/.bak均无列车，因此这个样本没有既存每车脚本快照需要转换；不能由此推断非空车厢迁移已验证。下文“尚未取得样本”为研究时的历史状态，当前仍需处理已有outer/运营抽离迁移及真实旧曲线数据问题，不由本每车行为批次加入runtime旧格式fallback。

> 同日用户已确认自定义动作菜单取并集，只操作声明该动作的车厢，整列门操作仍必须覆盖全部目标；原文intersection/union待决项已被取代。碰撞主编组平局按稳定train ID字典序选择，完整联结另批接入。当前开始Bukkit完整纵向迁移，不再等待这两项决定。

## Findings

### 建议提交边界

1. **Core 独立提交**：每车当前 train 身份与 publication owner 合为同一个原子引用，公开只读 Car.train；构造、split、link、remove 生命周期测试。这个提交不改变 JS 行为，可独立生产交付。
2. **Bukkit 垂直完整提交**：每车 runtime、精确源包解析、每车同步/延迟效果及完成结果、逐车 region dispatch、现有命令/UI/reverse 消费者、world unload 收尾一起切换。不要先把 JS 放到车厢却保留 TrainExtension 的旧 action/delay backend。

第二笔涉及多个文件但属于一条不可拆断的生产链，不是新 framework。若改动量仍需细分，可先提交不接生产路径的纯实现/测试，但不能宣称 per-car migration 已完成，也不建议长期并存两条执行路径。

### 现有路径与必须保留的语义

- `BukkitPlugin/.../behavior/TrainBehaviorRuntime.kt:36`：每实例创建一个 Graal Context；已有独立状态、HostAccess.EXPLICIT、无 IO/线程/类查找。迁移 runtime owner 即可复用，不需要重写 JS engine。
- `.../extension/train/TrainExtension.kt:45`：目前 train 持 runtime/script、action tracker 和 package snapshot。
- `TrainExtension.kt:71`：applyAction 解析一次动作，立即动画广播 self.cars，延迟动画也最终广播当时的 self.cars；声音使用车头。这正是编组变更后误操作的来源。
- `TrainExtension.kt:105,176`：door/model action 用 tracker 追踪同步 JS 发起的 action；成功仅代表最晚声明效果启动，不代表动画播放结束。保留这个明确完成合同。
- `TrainExtension.kt:351`：DelayedActionEffect 为 generic TickingObject + Synchronizable + PlatformCommitAffinity；可直接迁成每车拥有，不新增 scheduler。
- `.../extension/train/CarExtension.kt:36,133`：已有每车模型、保存状态和 synchronized destroy，是行为归属与销毁接入点。模型隐藏/rebuild 与车厢 destroy 不等价，不能隐藏时关闭 JS。
- `.../operation/BukkitTrainOperationGateway.kt:49,111`：当前只把整列车调到 head region，单回调直接 ticket.complete；必须改为固定目标逐车 dispatch + 全目标汇总。

### 每车状态与同步调用

建议由 CarExtension 持有一个惰性创建的 runtime，以及该车的 pending effects/operation 状态。为避免把 CarExtension 变成巨型类，可以把原 TrainExtension 行为代码直接搬到一个内部 car-bound helper；不建全局行为注册或通用任务编排器。

TrainExtension 不再拥有 JS Context/效果。它可以暂保留现有广播入口名字，作为捕获 targets、请求逐车执行、汇总结果的 facade；禁止同步跨 region 访问 N 个 Context 并返回假装立即完成的 Boolean。

每次行为调用只在该车 owner 上执行、受同一车锁保护。Context 初始化、listActions、call、runDefinedAction、close 都必须同样序列化；不要只保护 action 而漏掉 UI metadata 查询和 close。

已接受效果绑定 exact car/extension 实例，联结/解编时不要求 owner train 身份仍等于旧 train，因为这会错误取消本来必须延续的动作；仅销毁/extension 替换/runtime 重载会令其失效。查询 current train 使用新 Car.train，不在 region 读 world map 或 live train.cars。

### operatingEnd 与物理门侧：保留 s100 脚本，避免 double-flip

证据：`models/s100/behavior.js:14-23` 自行通过 operatingEnd 把 semantic side 换成 physical action 名；`.../control/TrainControlManager.kt:79-82` 的 controlEnd 是 stable train end，未有人控制时由 train.isReversed 推断。`CoreCar.kt:109-111,133` 的 car.isReversed 是车体相对当前 train operating direction 的反向，安装反向等于 `car.isReversed xor train.isReversed`。

最小方案：

1. simulation owner 捕获 exact cars、当前 controlEnd 及每车安装方向。
2. 计算 `relativeTail = (controlEnd == TAIL) xor (car.isReversed xor train.isReversed)`。
3. 将这个 Boolean 随该次调用交给 CarExtension，调用前设置 call-local end，finally 恢复。包含 ensureRuntime/script top-level 初始化；脚本不能在初始化时看到未设置的默认方向。
4. 保留现有 `door(semanticSide, scene)` 输入，脚本的 operatingEnd 返回本次捕获的每车相对端。**不预翻 door 输入**；脚本只翻一次，生成的 action 名及 effects 已固定物理侧。没有 behavior.js 时 Kotlin fallback 用同一 captured end 换算。
5. 延迟效果只存具体 animation/scene/sound，不重新执行 JS 或重新读取方向。

这在语义上满足命令接受时冻结 physical-side，不需改 s100 或玩家脚本布局，也不需一个泛化调用上下文框架。types/train-behavior.d.ts 文档要更新为每车作用域、每次调用捕获的 relative end。

混合安装方向必须测试；只用 train.isReversed 会漏掉反向安装。onReverse、named action、onStation 和任意 behavior 命令也应捕获同一每车相对端，不只 door 路径。

### 源包与 snapshot 复用

- `.../config/model/ModelManager.kt:93-113` 当前 getTrainAction/getTrainBehavior 在指定包没有内容时会继续搜索别的包。按 car model 包解析时必须改用精确 package 查询（或增加极小精确方法），不能把其他车的脚本当作该车默认脚本。
- `ModelManager.kt:139-158` 已有 SavedTrainPackageSnapshot（actions、behavior、control、逐车 controls）与 SavedCarModelTree（car、parts）创建器。
- `.../config/model/SavedCarModelTree.kt:10-14` 没有 actions/behavior。单把 runtime 搬进 CarExtension 不能维持 SNAPSHOT 模式。
- `.../extension/train/TrainRenderPersistence.kt:7-18` SavedCarRenderState 只有 modelSnapshotHash，packageSnapshotHash 在 SavedTrainRenderState。

推荐给 SavedCarRenderState 添加可选 packageSnapshotHash，按该车定义的 packageName pin 已有 SavedTrainPackageSnapshotStore；content-addressed store 可让同包多车共享同一 hash。继续保留 SavedCarModelTree，不把脚本复制进模型 part tree，也不增加新的 store/version。SNAPSHOT 时脚本/actions 只读 pin 的源包 snapshot；FOLLOW_PACKAGE 时精确读当前源包。混合编组不能统一用主编组包。

持久化前置：已阅读 shared/versioning.md；该未发布开发格式不因本批升版。不在运行时代码加猜测旧车应继承哪个 train snapshot 的 fallback。开发服已有数据如需保持原 pin，单独在离线迁移中把原 train package hash 分配给实际对应源包的 cars，并验证源包匹配。缺失 pinned snapshot 不能偷偷回退 live 包。

**本批真实存档门槛未满足**：主会话指出任务要求先取得真实开发服样本，再修改持久化形状。本研究未访问 192.168.1.230，未取得或验证该样本；以上 per-car packageSnapshotHash 是方案，不是准许直接落地的格式改动。该字段当前模型中不存在，若新增 nullable 默认值仅用于 current model 的明确“尚未 pin”状态，不能用 null 自动从当前主编组取脚本来伪装兼容。当前 SavedCarModelTree 只能重用 car.packageName 作为源包身份，**不能恢复该包被 pin 时的 behavior/actions 内容**；其 parts 与 CarDefinition 不含脚本源码，也没有 package snapshot hash。因此完整 SNAPSHOT 迁移确实需要每车持久化一个源包 snapshot 引用（复用已有 store），或另一个经用户认可、同样明确的资产来源持久化方案；不能只靠已有 modelSnapshotHash 无损推导。第一笔 Core ownership 不受此样本门槛影响，可先交付；Bukkit 持久化落地应等待样本核对及当前数据迁移路径明确。

已有 TrainExtension.resolveControl/resolveCarControl 仍用于列车动力学/司机控制；不要因为迁移模型行为就顺带重定义整列车控制 profile 的产品政策。car-owned actions/behavior 与 train control 可以共用 snapshot value，而不是互相绑生命周期。

### 延迟效果与完成汇总

每次 car operation 持固定 effect 集合及 terminal once 状态。JS 同步求值期间 tracker 收集所有声明效果；求值结束后 seal，只有 seal 且全部接受效果 terminal 才可 success。一个声明动作含多个同时间/不同时间 effect 时不能另排一个 max-delay callback 竞争顺序：旧实现只按时间估计、不证明所有 effects 真正执行成功。

DelayedActionEffect 继续使用 existing tick delta 计时/affinity commit；commit 捕获异常并通知该车 operation FAILED，同时 finally unregister，避免 WorldManager 只日志后对象每 tick 重试。直接 action/sound 也计入该次 tracker；当前 binding.sound 直接播放但不 record，纯 sound behavior 会被误判零 action，需要一并修正。

列车 ticket 在 simulation 捕获 N 个目标后建立 N 个结果槽/计数，再逐车派发；同步完成 callback 早于 executor 返回 Accepted 也不能提前宣告其他目标成功。全部目标 success 才 SUCCEEDED；任何目标失败可立即 FAILED，其他已经发生的副作用不回滚。迟到 callback 不能改变 terminal ticket。

Basic TrainOperationTicket 已有 PENDING/SUCCEEDED/FAILED/CANCELLED 和幂等 CAS 模式可复用，不必新建公共 operation 系统。内部 callback 从 `() -> Unit` 改为能传递成功/失败即可；公开 ticket API 无须扩大。

取消合同当前只要求 CANCELLED 后不再成功（Basic/.../api/operation/TrainOperation.kt:77-90），未承诺回滚动画或自动关门。最小实现：取消 queued-but-not-accepted 工作，ticket terminal；已经接受的车厢效果按既有合同继续，完成不能改写 CANCELLED。若想停止已接受 effects 是额外产品政策，不在本次默认添加。

### Folia dispatch 与 unload 不永久 pending

RegionTrainOperationDispatcher 改为 car target，simulation 捕获值包含 target extension、方向和 location；真正执行时重查该车当前 location ownership，必要时用已有 dispatcher 重新提交，不等待 region，不遍历原 train.cars。脚本/效果执行必须覆盖 guard，不以 runInSimulationContext 冒充 location ownership。

`.../logic/WorldManager.kt:638-665` 已有 platform commit affinity 重新解析/reschedule；每车延迟效果复用此机制，只把 commitLocation 由 train head 换成 car position。`PlatformDispatcher.kt:18,89` runAt 没有 retired callback；不能假设卸载时排队任务还会运行。

硬缺口：`WorldManager.kt:394-406` world teardown 关闭 coordinator/render/tick，但不保证 destroy 所有 car extension。`RenderChunkManager.kt:75-96` close 只清观众/索引；`Core/.../util/TickManagerImpl.kt:233` close 只清 mailbox 和 shutdown executor，不 destroy ticking objects。

最小收尾：

- gateway 内部维护本 world 的 pending tickets，小集合，仅实际操作期间存在；close 将全部置 FAILED/CANCELLED 并禁止新请求。这覆盖初始 region task 尚未触达 CarExtension 的 pending。
- CarExtension 显式幂等 closeBehavior，终结 pending per-car operations，unregister effects，串行关闭 Context；真正 destroy 调用它，隐藏/rebuild 不调用。
- world teardown 在保存之后、tick owner 关闭前分别尝试关闭 gateway 和每车行为；后续 owner 清理仍应继续，遵守既有 suppressed failure 规则。逻辑 terminal 不能依赖 region task 成功执行；模型 Bukkit 实体清理仍按自身 owner 路径。
- 迟到 task 先检查确切 extension 已退役/operation 已取消，不能重新 ensureContext。

### 现有消费者迁移清单

1. `.../control/TrainControlDialog.kt:33-40,113-128`：actions/behaviorActions 元数据和点击调用都迁移，不能 UI 在玩家 region 直接运行其他车 Context。
2. `.../command/TrainCommand.kt:550,605,638`：animation/action/任意 behavior 命令统一走逐车 facade；不要留下 old direct broadcast。异步结果反馈回玩家 entity owner。
3. `.../behavior/TrainReverseBehaviorListener.kt:12`：onReverse 每车一次，捕获当次方向；原来 train runtime 的字符串结果不能代表全车一致成功。
4. `.../command/JavaRailwaysCommand.kt:582`：capability inference 不再借主包唯一 runtime；应从真实 cars 元数据求结果，避免为了查询 metadata 在错误 region 初始化 Context。
5. `.../http/SimulationControlProfiles.kt:118`：actionDurations 来自各车各源包同名动作的最大最晚 effect start，不能仍只读主包。
6. `.../logic/RailWorldFactory.kt:185-188`：构造新的逐车 dispatcher/gateway，并把 close 接进已有 world lifecycle。
7. `.../command/JavaRailwaysCommand.kt:189-190`：pin snapshots 时除了 model tree 也 pin 每车源包；不能只 pin train。
8. `types/train-behavior.d.ts`、Dokka/spec 与真实每车语义同步；无需重命名玩家 behavior.js/actions.yml。

### 最低 API 变化与技术未定

- Core：只读 nullable Car.train；其引用可跨 region 识别 identity，不授权读取 mutable train 状态。orientation 本批可复用现有 CoreCar seam，在 simulation 捕获，不必急于新增公共安装方向 API。
- Basic：原则上无 public API 改动，保留现有 ticket/request/operation 形状。
- Bukkit：内部 executor/dispatcher 由 train 改 car/captured target，completion 带结果；TrainExtension facade 的同步 Boolean 返回不能在异步逐车模式诚实保留，consumer 一起迁移。
- 历史待决项已由2026-10-12用户决定取代：自定义动作采用并集，仅声明支持者为目标；支持者失败仍失败，零支持者失败。整列门语义不因此缩小目标，缺失必须明示失败。
- 元数据列表可在 car-owner 初始化时缓存不可变结果，UI 获取/刷新也通过逐车 dispatch；避免为每次 UI 查询建立一套新 Context。缓存失效跟随实际脚本替换/源包 generation，不跟随联结/解编。
- JS Context 数随初始化车厢数增长；不应在本批无测量声称固定初始化成本。可复用 Engine/Source，但共享 Engine 必须有现有 plugin lifecycle owner；不为“优化”引入另一条线程池。

### 最低回归

- 同源码两车变量隔离、连续调用保留，未调用车不建立 Context；split 后 runtime 身份/变量/待执行效果不变。
- 有 delay 的多车 door 仅全部 effects 执行后完成；同 due time 不依赖 commit 顺序；effect 抛错终止一次、不反复重试。
- 一个目标 missing action/script failure，整体 FAILED，不永远 pending、不误报全车 success；纯 sound action 正确计入。
- 混合 installed direction、controlEnd HEAD/TAIL、命令后 reverse/split 的 action physical side 固定，无 double-flip。
- cars 从不同 model package 加载各自 behavior/actions，SNAPSHOT 在包 reload 后仍使用原 script，FOLLOW_PACKAGE 遵守明确 reload 边界。
- queued region callback 前 cancel/unload，已接受 delay 期间 destroy，旧 task 对同 ID 新 car 无影响，Context 无并发访问。
- 不先伪造完整四端 link；真实 link 的两侧 runtime 连续性待完整联结批次一同验收。

## Related Specs

- `.trellis/spec/bukkit-plugin/backend/car-behavior.md`：用户已批准的 TARGET。
- `.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md`：simulation/location/entity ownership、affinity reschedule、失败隔离卸载。
- `.trellis/spec/bukkit-plugin/backend/interaction-render-persistence.md`：frame owner、snapshot、schema policy。
- `.trellis/spec/javarailways/shared/versioning.md`：未发布格式不按批次升版，旧开发数据离线迁移。

## External References

无外部检索；本报告只基于当前源码，不给 Graal 初始化/内存数字或 Folia 游戏内正确性背书。

## Caveats / Not Found

- 没有运行 Gradle、性能 benchmark、Folia 或开发服测试。
- 不实现完整四端碰撞联结，不决定模型 JS 之外的运营元数据迁移。
- 用户已确认仅碰撞触发联结、碰撞前静止一方优先、拒绝/准备失败默认脱轨；这些不改变 per-car 动作保存合同。
- 未引入 runtime legacy compatibility；如果计划修改 persisted snapshot 模型，仍须执行独立版本门槛与开发服手动迁移检查。
