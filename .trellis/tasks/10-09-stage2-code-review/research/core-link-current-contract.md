# Research: CR-030 当前联结合同与最小实现边界

- Query: 在混合安装朝向、解编事件及 Experimental 标记已实现后，如何最小实现四端联结、extension 准备和碰撞结果消费？
- Scope: internal；仅源码/合同研究，未实现、未运行测试。
- Date: 2026-10-11
- Baseline: `bd29118a`；本文源码现状描述对应此基线。结构准入前置修复由`implement.md`另行记录，不能将本文的“没有gate”继续当作后续代码现状。

## Findings

### 当前事实与文件

- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt:681`：`splitBefore`返回 Future，在 main after-commit 队列 await physics，再发决策、prepare、commit、成功事件；其局部几何/frame 准备可复用。
- 同文件`:905`：旧 `linkWith(CoreTrain): Unit`仍先改 extension、直接拼 cars、调用 `updateByPrevCar`重新摆车，再删另一列；没有准入、Future 结果、完整新 frame 或失败原子性。必须整体替换入口，不能只加事件。
- 同文件`:201`和 `CoreCar.kt:130,166,185`：运营 head/tail 来自 train.isReversed + car.isReversed；车厢物理 bogie A/B、connector A/B 不随运营换向更名。`CoreTrain.kt:1246`保存安装朝向 `car.isReversed xor train.isReversed`，混合安装模型已存在。
- `Core/.../api/physics/train/Train.kt:70`：公开 split 已存在，link 仍仅实现类可用。新增接口应复用 Future，不要求插件 cast CoreTrain。
- `Core/.../api/physics/train/TrainExtension.kt:23,30`：split 返回专属准备实例；link 仍是旧可变 Unit hook。`api/util/plugin/ExtensionDataContainer.kt:4,34`提供当前 slot 列表与内部按 slot 安装，足够做 union，无需新 registry。
- `BukkitPlugin/.../extension/train/TrainExtension.kt:47,56,242,350,387,399`：唯一生产 TrainExtension；旧 link=no-op，但它并非无状态：保存 package/snapshot，持有 JS runtime，延迟 action 是捕获旧 extension/host 的 tick 对象。
- `Core/.../physics/tick/PhysicsCoordinator.kt:575`：LINK 排旧结构任务后无条件 postTick 清两个 pending ID，不检查结果；结构任务抛错封在 FutureTask 中，此路径会静默解冻。
- `Core/.../util/TickManagerImpl.kt:129,159`：afterCommit→postTick 顺序是真实的，但 generic queue 不提供 physics 排他。
- `Core/.../physics/CoreWorld.kt:253,279`：await 只捕获并等待一个 ticket；remove exact instance 并清 mailbox/name/owner，不能用它 destroy 移入车厢。

### 稳定编组端，不等于边缘车的物理 A/B

建议公开 `TrainEndSide.A/B`（命名由实现批次统一），含义为稳定 cars 顺序的首端/末端。不要复用实现类 `TrainEnd.Type.HEAD/TAIL`，它是运营端注册对象，不是稳定端。

- 编组 A 对应：未 reverse 时 head；reverse 时 tail。编组 B 相反。
- 边缘车物理端：其安装朝向 `car.isReversed xor train.isReversed`为 false 时，稳定 A 一侧为物理 A；为 true 时为物理 B。稳定 B 一侧相反。
- 保持所选主编组稳定顺序与安装朝向；主 B 接他 A → main+other；主 A 接他 B → other+main；主 B 接他 B → main+reverse(other)；主 A 接他 A → reverse(other)+main。
- 上述 other 指先归一到主编组稳定坐标后的顺序。仅反序加入的编组要切换它的安装朝向；运营 facing 切换条件必须比较每车原 `car.isReversed`和结果所需值，不能把“同名端”直接等价为无条件反转所有 bogie。
- 缺省结果运营方向继承所选主编组的物理运营朝向；按上述主编组稳定坐标保留时即可保留主 train.isReversed。事件显式方向应明确相对于结果稳定顺序，不隐式相对于请求者。
- 外端/车体路径由两个原已发布路径按接触次序拼接，局部 reversing 是纯列表/arc/facing转换；不能重跑摆车 solver 或依赖道岔反向 active choice。

### 准入与准备

建议 `Train.linkWith(other: Train, ownEnd: TrainEndSide, otherEnd: TrainEndSide): Future<Train>`；返回真正存活的主编组，不能承诺总是 this。碰撞通路另由已捕获接触事实选择端，不公开“猜最近端”重载。

同一停止的 epoch 上检查：同 world、exact live instance、非自己、无重复 car ID、未脱轨、双方 scalar speed=0 且向量 motion=0、指定相向车钩真实接触。零速直接精确 0，不另加玩家不可见“近零可联结”阈值。真实接触使用现有 Curve.TOLERANCE 的数值容差，以及车体中心/固定模型朝向/length/各物理 connector 的三维端点；同时证明轨道路径可连续连接，排除菱形空间近邻。容差不是吸附距离。不要要求 TrainEnd 重合，它不含车钩伸出。

prepare 必须在修改 live 状态前完成：结果 cars/每车方向、外端、body/placement/swept、完整新 frame、所有 extension。commit-only 安装这些值、撤除被合并列 controller/两个旧运营 TrainEnd、转移 car stream ownership、精确移除旧 world/mailbox，发布 survivor 完整 frame；不 destroy 移入 cars，不调用任意插件回调或几何 solver。主 controller 保留（当前准入必须 speed=0），不自动搬移另一列 controller。

**真实并发缺口**：`PhysicsCoordinator.kt:354-395`的 timerWake/requestStep 在 await 返回后仍能开新 epoch。`beginShutdown`是永久停机，不能挪用。需要一个极小 internal 结构准入 scope：stateLock 内临时阻止新 wake 并捕获 ticket，锁外成功 await，然后执行整个结构准备/提交，finally 恢复 gate；timer 此时 no-op，显式并发 wake 的行为应明确拒绝/复用已捕获 ticket，不得假装开启新 epoch。不能持 stateLock 等 ticket，不能持它派发事件或初始化 extension。此 scope 是保证同 epoch 的技术必要条件，不是泛化事务框架。拆解入口也应复用，避免同一结构 API 两套并发保证。

### Extension：最小 union 及宿主风险

建议将旧 link hook 换成准备返回专属 replacement 的 hook（Experimental），传双方 host 与另一侧同 slot nullable extension。按 union slot：优先主侧 hook，主侧缺 slot 时调用另一侧 hook；它必须明确处理 nullable 对端并返回新实例，不能遗漏 other-only slot 或把现有实例换 host 冒充准备。拒绝返回任一源实例，initialize 仅写新实例自身，无平台动作/修改源 host。Core 不解释插件自有状态、不做泛化序列化复制。

同一 slot 双方信息如何合并由该 extension 实现决定；不能以保存 main extension 就声称另一侧状态已处理。Bukkit adapter 至少显式保存所选来源 package/snapshot（已有 main 优先语义是否保留见未决项），新 runtime 懒建；不要通过调用 `save()`复制，它可能创建/落盘 snapshot。初始化对 result host 的观察应是稳定最终草案，或者明确准备期原 host；不可让 hook 推断错误 cars/direction。

**不能遗漏宿主退休**：旧 JS runtime 有 host 绑定；延迟 action 没有 exact-current-host/extension guard。replacement 成功后必须由 adapter 在成功通知里关闭被退休 runtime，并让旧延迟 action 不再操作旧/替换 host（例如 commit 前 exact slot identity guard）。失败时两侧原 runtime/延迟任务保持有效。成功通知异常不回滚 Core；因此该清理最好只调用可诊断幂等的 adapter 自有方法，并为迟到任务提供独立 identity guard。不要为此发明 Core 通用 destroy/rollback 协议，也不能声称 world.removeTrain 自动处理它。

### 事件与碰撞消费

- `TrainLinkEvent`：双方 live Train、指定稳定端、immutable 原 car IDs/方向，`requestMainTrain(Train)`仅可选二者、`requestDirection(Boolean)`结果稳定坐标；首次请求生效，同值重复允许，冲突单向 reject；拒绝理由首个保留。复用 split 的主线程 mutator/freeze/dispatch failure 模式。
- `TrainLinkSuccessEvent`：survivor/consumed live identity及 immutable 操作前双方 IDs/顺序/方向/端；只通知，无可取消字段。消费者可在准备时暂存元数据，成功时才正式更新。
- 两类/API/extension hook 标记现有 `ApiStatus.Experimental`，不新建 opt-in 注解。普通 event bus 不加通用 cancel 框架。
- 显式 API 失败返回 exceptional Future，原两列保持，不因插件 veto 额外脱轨。
- collision LINK 在下一 epoch先冻结两列并排队结构操作；postTick 消费其**已完成**Future（不能阻塞等待尚未执行的队列）。成功才清 pending；失败先实施获批的安全处置，再清 pending。现状无条件清 pending必须删除。冻结期间 controller.freezeMotionForPhysicsEpoch 和无 controller clearMotion 会把运动置零；不允许把正在接近但未真实接触的两车吸附来通过准入。
- 建议失败回退原碰撞 DERAIL、日志包含 Future 根因；不能仅解除冻结。具体 main-thread mark/publish必须复用现有 derail边界且不在 physics worker反向等待结构任务，避免环等。

### 有价值的最小测试矩阵

1. 四端×双方原运营方向，主侧选择两种，含混合安装/非对称 connector；物理 car/bogie/model 坐标无位移、稳定 car IDs正确，后续真实运动/换向/split/save-load贯通。
2. 未停车、间隙超过容差、异 world/自己/陈旧实例、重复 car、菱形近邻/不同支路拒绝，Future原因明确且全量原状态不变。
3. 决策 veto前后/监听顺序、相同请求、主侧/方向冲突、预事件异常继续但不提交、冻结后编辑拒绝；成功异常继续且 Future仍成功，成功恰好一次且完整 frame可见。
4. extension union：双方/主only/他only/空slot；后续hook或initialize失败、返回原实例拒绝；失败保留两侧 controller/extension/frame/owner/index。Bukkit包引用保留、退休runtime及延迟动作不能继续作用。
5. 在途 epoch + timer wake阻塞/排他，commit期间无新 epoch；collision成功、拒绝/prepare异常的安全状态、默认DERAIL不变；不能只测试远车重摆后的列表。
6. 原 `CoreTrainLinkTest.kt:14`把两10m单车放50/35而允许联结，需改为真实接触fixture并保留旧stream/端点退休断言，不能保留旧吸附准入。

### 真正尚需用户确认的政策（不重问已批准项）

1. collision `requestLink`之后被插件拒绝/准入失败时：建议原默认 DERAIL，而非解冻继续运行或长期冻结。旧研究已将此列待批，CURRENT合同没有批准它。
2. 默认主编组：建议显式 API选接收者；collision仅一侧有controller时选该侧，否则按稳定 ID。插件仍可共同选择且冲突拒绝，缺省只是无人选择时的确定性回退，不替代运营插件。CURRENT train-physics 明记默认待设计。
3. Bukkit双方不同 train-level behavior/package时，结果采用所选主编组包、他only slot保留其包，是否接受旧主行为实例退休/新实例惰性创建（不迁移任意JS realm全局状态）。这不是 Core可以自动无损合并的状态；需明确合同，不能默默丢另一侧脚本状态。

停止/接触/四端/事件两阶段/单向veto/方向冲突拒绝/预异常拒绝提交/后异常不回滚均已批准，不是待确认项。slot union + 专属prepare 是实现失败原子性的技术手段，不单独索取新框架授权。

## Related specs / External references

- `.trellis/spec/javarailways/core/train-physics.md`：CURRENT混合安装/split和TARGET联结区分，尤其方向/事件/停止真实接触要求。
- `.trellis/spec/javarailways/core/runtime-publication.md`：exact ticket、timer fallback、shutdown 与 generic队列边界。
- `.trellis/spec/javarailways/core/extension-data.md`、`event-bus.md`、`api-boundaries.md`：slot、host初始化、异常隔离和公开接口。
- 没有外部参考/外部版本；未修改持久化格式，无升版或runtime兼容提案。

## Caveats / Not Found

- 本文是实现前最小设计研究，不是已实现能力；没有编译/测试/服务器验证。
- 历史 `core-link-transaction-plan.md`的拒绝同名端、恢复几何缺口与默认沿用旧主控制器选择等内容不能作为当前冻结合同。
- 代码没有现成可恢复的结构 wake gate，没有 extension通用外部副作用rollback，也没有自动合并JS realm状态的能力。
