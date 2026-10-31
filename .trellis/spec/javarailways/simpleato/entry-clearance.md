# 入口以前的整列车净空检查

## 解决什么问题

状态为 `CURRENT`：生产接线与独立离线运行/测试检查已完成；不等于真实Bukkit/Folia验证。
插件可以要求列车进入某处以前，先确认整列车能够驶过指定出口。SimpleATO 不认识保护区域或闭塞类型，
只解释入口坐标、车尾须通过的坐标，以及当前经过允许忽略的入口自身限制。
Basic 仍独立验证和授予资源，并在真实车尾清除后释放；本检查不产生授权，也不直接释放资源。

触发是绑定路线、更新当前列车控制约束，以及把检查结果交给串行信号周期。
具体保护区域到这些中立要求的转换归[SimpleSignals 合同](../../simple-signals/backend/protection-region-authority-transition.md)。

## 一次检查怎样运行

1. 插件在既有路线绑定时声明入口和出口，缓存这一份有序要求；不会每周期匹配区域图。
2. Dispatcher 用当前一致观测里的车身跨度和控制器已有安全距离，抬高进入该入口后的授权请求下限。
   这仍是请求，Basic 可以拒绝；不足的路线终点不能被解释成净空成立。
3. 控制器扫描前，将当前前视入口所需的净空目标并入原有前视长度。只有一次前方扫描，
   后方车身几何直接读取 Core 已发布帧，不另扫车身或寻找车尾路线。
   扩长后新覆盖的入口也按有序坐标继续处理一次，直到本次范围不再要求延长；不重复扫描或求固定点。
4. 控制器更新信号、基础线路、运营停车、站台和跟车约束后，检查车头能否到达净空目标。
   不能到达时在入口安装停车约束；资源准备仍继续，不能把停车状态当作尚未完成检查。
5. 控制器把不可变完整结果交给现有 inspection 运行时，再在串行信号周期按来源分发变化。
   插件把净空结果与 Basic 授权和设备就绪合并后才允许保护信号开放。

条件改变必须影响下一次控制计算，不依赖 Dispatcher 继续推进；信号图只在检查结果改变时被唤醒。

## 坐标、车长与余量

所有坐标是当前冻结路线起点起算的米数，包含同一轨道重复经过的独立位置。
设出口为 `x`、当前保守车身长度为 `L`、控制器授权停车安全距离为 `m`：

```text
minimumHeadClear = x + L
requiredAuthorityEnd = minimumHeadClear + m
requestedFloor = max(existingPluginFloor, requiredAuthorityEnd)
```

原始车身跨度由同一 Core 已发布帧中有序 `bodyTraversal` 的绝对弧长差求和；L取共享保守上界。
不能用 `Train.length`（车厢数量），也不能只累加车厢模型长度：后者不包含车钩间距和实际曲线摆放。
不得按轨道 ID 合并重复片段。帧缺失、列车ID不符、脱轨、车身为空、车头与当前感知不符、周期不一致或算术非有限都不能证明净空。
控制器每次检查读取当前几何；几何变化不重新运行插件路线匹配，也不能沿用旧的较短净空证明。
公开读取入口为 `Train.publishedFrame: TrainFrameSnapshot?`，完整帧的发布合同归
[Core 帧规范](../core/render-publication.md)。

Core现有车钩二分摆放允许毫米级变化，真实直线列车连续平移的跨度也会抖动。
不能让精确浮点相等比较把它变成每周期floor重编和共享规则执行。
本列车既有路线绑定只保留一个共享长度上界，Dispatcher和controller共用：
令g=2×Curve.TOLERANCE（当前10mm），首次或raw超过旧上界时立即取`ceil(raw/g)*g+g`；
只有`cached-raw >= 3g`才回落到同一个新上界。每次有效返回必须不小于本帧raw；
在当前常量下保守余量是厘米级，不是另一套用户安全距离。
新取整增加10–20mm；回落迟滞期间可保留不足30mm的余量。缓存记录最近采样physics epoch，
较旧帧不能迟到回落更新帧的上界；控制计算遇到旧/错帧本次仍不能证明净空。
缺/错帧不能靠旧缓存证明净空；当前结果仍为不可用。缓存按已有路线绑定/折返/移除生命周期重置，
不保存长度历史、不另增列车registry或存档字段。短锁只调节这个数，不持锁编译floor或运行扫描/插件。

评估已有`MovementAuthorityConstraint`时，使用该constraint显式`stoppingMarginMeters`，为空才回退profile全局距离；不能把已证明车站终点的0.3米约束重新按5米计算。该变化不降低本页保守车尾净空授权请求floor，不改变跟车或入口自身限制。

`existingPluginFloor` 可以要求完整后继区间；它与车尾净空是两项条件，取较严格者。
`minimumHeadClear` 或授权余量超过运营路线终点时入口保持停车，不能把坐标裁到终点再声称足够。
未知车身长度时，请求floor保守取冻结路线末端，但净空始终不成立；这可能多申请资源，不能据此放行。

## 已冻结的中立值

```kotlin
InspectionRestrictionIdentity(sourceId: String, restrictionId: String)
InspectionEntryClearance(
    declarationId: String,
    entryRouteDistanceMeters: Double,
    tailClearRouteDistanceMeters: Double,
    ignoredEntryRestrictions: Collection<InspectionRestrictionIdentity> = emptyList()
)
InspectionDeclarationBinding.entryClearances

InspectionEntryClearanceState(
    declarationId: String,
    entryRouteDistanceMeters: Double,
    tailClearRouteDistanceMeters: Double,
    minimumHeadClearRouteDistanceMeters: Double,
    requiredAuthorityEndRouteDistanceMeters: Double,
    movementReady: Boolean
)
InspectionClearanceSnapshot(
    sourceId: String, trainId: String,
    observationStamp: RailwayStateStamp, route: FrozenRouteBinding,
    clearances: Collection<InspectionEntryClearanceState>
)
```

声明及状态的 `tailClearRouteDistanceMeters` 都表示车尾须通过的出口 `x`，不是车头目标；
`minimumHeadClearRouteDistanceMeters` 才是 `x+L`。身份使用稳定 namespaced ID；同一声明 ID 可以在不同入口坐标重复，
同 ID 同坐标重复拒绝。集合必须防御性复制。几何要求和结果不写存档，不增加 schema 版本。

## 如何复用已有控制约束

授权边界减现有安全距离；运营/站台停车点减对应实际停车余量；跟车间距减跟车安全距离，
不预测前车将来会继续行驶。下游零速目标、当前位置零速和 departure hold 都不能忽略。
正的速度上限仅要求慢行，不因低于某个国家制式档位就断言必须停车。

每个入口的局部证明检查的是从该入口到车头净空目标的范围，不是要求当前车头此刻可以越过所有更早的入口。
位于此入口以前的路线停车边界不参与其出口净空证明，但仍保留在最终驾驶约束中。
全局零速/hold 和授权覆盖不受这个局部范围过滤。边界恰在入口上的第三方限制仍参与，不能当成已经越过。
例如 A 的净空跨度进入 B：B 可以先证明自己的出口净空，不被更早的 A 红灯阻止；
A 仍等待 B 的正常信号开放。这样按路线方向传播，而不形成 A 等 B、B 又等 A 的准备循环。

只有当前入口声明列出的精确 `(sourceId, restrictionId)`，并且实际坐标就是本次入口的自身信号，
才可以从这次净空证明中排除。其他来源即使同位置同为零速，也仍参与判断。
上一次本入口净空等待不应阻止重新证明，但不能删除其他入口、其他经过或其他运营等待。
这是检查可达性的排除，不是从最终列车保护约束中删掉红灯。

## 结果交接与失败处理

每个来源、列车和当前路线发送完整替换值；同一周期的中间结果合并，只交接最新结果，不重放历史事件。
共享信号必须核对列车、冻结路线和入口经过，不能把其他列车或前一圈的 ready 用到本次。
首次结果缺失、未来周期、已替换路线或不足的资源授权都保持停车。
非法公开帧的车头若根本无法投影到当前Mission，既有路线invariant会使整个ATO世界Stall并freeze，
不是继续借用旧长度或仅等待入口；正常Core getter的同列车原子帧不应产生这种违约输入。
来源/列车移除要清除旧结果；旧路线正在完成的 worker 不能覆盖新路线的完整状态。
运行约束突然收紧时，控制器本次入口停车约束直接生效，不等待画面或下一次 Dispatcher 调度。

生产接线使用以下宿主方法准备请求floor；显式米制长度用于持有一致几何的宿主调用，
生产Dispatcher传完整帧，由已绑定要求决定是否求车身跨度：

```kotlin
synchronizeRoute(
    observationStamp: RailwayStateStamp, trainId: String, route: RouteSnapshot,
    trainLengthMeters: Double? = null,
    authorityStoppingMarginMeters: Double = 0.0,
    publishedFrame: TrainFrameSnapshot? = null
): Double // 返回既有最大声明提前时间（秒）
```

`publishClearance(snapshot)`交接移动侧结果。信号来源通过
`SignalEpochSource.watchedInspectionSourceIds`声明兴趣，由`SignalEpochInput.entryClearanceUpdates`接收完整变化。
世界组合启动时连接唯一inspection runtime；具体epoch路由、首次baseline及空来源消费归
[响应式epoch合同](reactive-signal-epochs.md)。这些不是插件可写grant或直接操纵列车的入口。

进入前的证明只针对当前几何、路线、授权和停车事实；未来紧急停车、故障或下游改变仍可让列车停在区域内。
这种情况下继续保持真实资源锁，不能因旧的净空证明提前释放。

## 性能与必需回归

车身跨度求和为本列车已发布的 `B` 个车身片段的 O(B)。控制器只有有净空要求时才额外读取帧和定位进度；
Dispatcher传入一次完整帧，宿主仅在该绑定有要求时计算车身跨度。
入口在低频绑定时按坐标排序；设全部要求 D、本次纳入前视的入口 K，初始边界二分及单调扩展为 O(log D+K)。
新覆盖入口各处理一次，不反复从头求固定点。各入口按下游到上游检查已有约束与本批下游入口hold；
设既有约束 C、每入口允许忽略的自身身份最多 J，保守检查成本为 O(K(C+K)(J+1))，不是全世界列车×区域扫描。
前视扩长仍只增加原有一次扫描所经过的范围，不新增全世界或全列车查询。
共享保守上界实际变化才重编本列车已有声明的请求floor（排序及prefix maximum），若该列车有 F 项floor，为 O(F log F)，
不重编入口索引、不调用插件matcher、不重建其他列车的view。上界未变时不重编；每次调节为O(1)。
结果构造和变化比较按本次K项线性；每个来源/列车保留一个当前值和至多一个待交接值，不排队保存中间历史。
无变化时不执行共享信号图，不保留无限事件队列，也不为曲线几何变化重新编译区域路线。

回归须覆盖：出口 25m、车身 10m 时车头至少到 35m；后继只到 30m 不能代替净空；
已授权到 40m 但下游红灯在 28m 时入口仍停车；站台目标和前车间距不足同样停车。
还要覆盖无后继、运营终点太近、当前前视以外的红灯、自身红灯不自锁、同点外部停车不能忽略、
Dispatcher 停止后的下一控制周期恢复、BA/重复经过、车身长度变化、缺帧和状态替换、变化局部唤醒与未变保持 idle。
真实Core直线连续移动须证明长度解算抖动不会逐周期重编floor/唤醒共享规则，真实增大立即收紧、明显缩短回落，缺/错帧不能复用缓存ready。
相邻保护区域须验证 B 先完成局部证明、A 经正常信号周期随后恢复，且 A 不会忽略 B 的停车信号。
测试必须包含真实控制器、Basic 授权和信号结果交接，不以单独调用判断函数代替生产接线。
当前测试入口是`EntryClearanceTest`、`EntryClearanceRuntimeTest`、Core的`CoreTrainPublicationBoundaryTest`，
以及SimpleSignals的`ProtectionSignalControllerHandoffTest`；selector/device/在线替换测试显式提供净空结果，
不保留“没有结果也ready”的兼容fixture。Java公开值消费通过现有`SignalApiConsumerPlugin`编译fixture验证。

避免“持有闭塞资源所以车尾一定能驶出”的推断；正确做法是完整资源与当前可行驶空间同时成立。
本批不改变 Basic 仲裁、真实车尾释放、手动驾驶、双向闭塞规则、JS 或模型格式。
