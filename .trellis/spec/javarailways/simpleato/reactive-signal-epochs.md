# SimpleATO 响应式信号 epoch 契约

## 状态、范围与归属

状态：`CURRENT`的平台无关运行时及Bukkit source注册接缝。触发：修改`SignalEpochSource`、`SignalEpochRuntime`、监测区变化到restriction publication的数据流，或Core railway epoch composition。

SimpleATO拥有一次完整physics epoch内的source路由、返回值校验、restriction发布及到controller的production传递。具体信号插件拥有固定闭塞、保护区域、联锁、下游依赖传播和aspect规则；Basic只派生监测区物理变化；Core只提供同步epoch边界。本契约包含运行期完整source集合的原子替换以及Bukkit对外注册/禁用生命周期，但不包含显示、inspection lead-time缓存或通用依赖图。

## 正常主流程

```text
Core捕获一个尚未公开的RailwayWorldFrameSnapshot
  → SignalEpochRuntime投影唯一RailwayObservation
  → 一个Basic RailwayAreaOccupancyEngine派生changed/baseline area updates
  → selectionRevision变化时只比较source预注册的watched continuation entries
  → 只按area/continuation owner唤醒有update的SignalEpochSource
  → 每个source同步处理自己的完整update子集
  → host先校验全部source结果
  → 按sourceId、trainId确定顺序发布到world-local immutable snapshot store
  → 将每个source返回的inspection declaration invalidation标记到同ID的当前列车binding
  → 所有publication ACCEPTED后记录本次completed observation stamp
  → callback返回，Core才发布world frame并完成exact ticket
  → 下一次physics tick中controller读取该store
  → 用Dispatcher预先绑定的冻结route和controller当前投影progress更新唯一adapter
  → 同一个constraint manager计算本次运动
```

第一次observation是baseline，因此每个至少拥有一个area或watched continuation entry的source都会调用一次。后续若area不变且`(topologyRevision, selectionRevision)`不变，continuation路径为`O(1)`并保持IDLE。selection revision变化时只比较已编译的watched entries，未实际变化的entry不唤醒source。同一epoch的`ENTER`、`LEFT`保留在一次area update和一次source调用中，不进入跨epoch事件队列。

## 公共值与callback

- `SignalEpochSource(sourceId, areas, watchedContinuationEntries, handler)`：一个已安装source snapshot中的immutable binding；source至少拥有一个area或watched entry。watched entry只声明唤醒兴趣，SimpleATO不解释道岔、信号或分支含义。
- `SignalEpochInput(observation, areaUpdates, continuationUpdates)`：immutable value；两籺updates只包含该source拥有且本epoch baseline/changed的值。`SignalRailContinuationUpdate`保留canonical entry和nullable selected exit；完整同epoch snapshot仍由`observation.activeContinuations`提供。
- `SignalEpochResult(publications, invalidatedInspectionDeclarationIds)`：完整immutable snapshot publications与同source ID下需要
  重算的inspection声明ID；空result表示本epoch两者均无变化，不表示撤销旧限制。
- `TrainRestrictionPublisher.publish(snapshot)`：平台无关publication seam；只有`ACCEPTED`允许epoch继续。

撤销必须发布同`(sourceId,trainId)`流内更高revision且`restrictions=[]`的完整`TrainRestrictionSnapshot`。这复用现有revision contract，不增加nullable、隐式超时或“没有返回即清除”。所有public collection在构造时复制，callback只能读取。

## 校验、顺序与失败

runtime在任何publisher调用前先收集并校验本epoch全部handler结果：

1. snapshot的`sourceId`必须等于callback source；
2. `observationStamp`必须精确等于本次observation；
3. `trainId`必须仍在该observation中；
4. 一个source result对同一train最多包含一个完整snapshot。

通过后按`sourceId`、`trainId`排序发布restriction，再按source ID顺序把invalidation交给inspection runtime，使相同静态binding和输入
得到相同side-effect顺序。invalidation只作用于同ID inspection source已有binding中实际存在的声明；不相关路线和未激活声明不执行
插件代码。handler抛错、返回值非法、publisher或invalidation失败都会向Core抛出；Core不发布候选world frame并Stall。已通过校验不保证
多个side effect具备事务rollback：较早接受的完整replacement或dirty标记可能已经改变，但任何后续失败都会在下一个physics epoch
开始前永久Stall当前coordinator，因此部分结果不能驱动车辆继续运动。这里不增加rollback或两阶段协议。

## 表示、复杂度与线程

runtime以空source state启动。`replaceSources`先完整复制、排序、验证source/area唯一性并建立新的`areaId → sourceId` map和Basic rail-local occupancy index；全部成功后才用一次volatile写替换state，失败保留旧state。低频配置事务使用同一编译路径的`replaceSourcesAtomically`：完整candidate先编译，随后执行调用方commit callback，callback抛错时不替换state，成功后只做已经编译好的引用写入。每个`process`开头只捕获一次state，因此并发替换不会把两个集合混入同一epoch；新state第一次消费的frame建立自己的baseline。空state不投影完整observation、不运行occupancy engine或source callback，只从Core frame读取stamp和live train ID并提交completed boundary，供controller证明自己消费的是上一成功physics epoch。

同一candidate还预编译`watched entry → source IDs`反向索引并复制排序后的entry集合。source替换会丢弃旧continuation baseline，下一epoch为新source snapshot提供完整watched-entry baseline；已开始epoch仍只使用自己捕获的旧state。

`TrainRestrictionSnapshotStore`是每world一个的value-only交接点，不查找、注册或保留live train/controller。一次成功publication只对应列车的concurrent-map entry内替换`sourceId`，并原子公布新的immutable per-train list；不复制其他列车的值。旧reader仍持有完整旧快照，新reader按source ID稳定顺序获得最新快照。所有publication成功后才清理离场列车并推进completed stamp。写入成本为`O(S_train log S_train)`；controller查找为`O(1)`并线性处理该列车的source数。

每个epoch将Core frame投影一次并为`T`列车建立一次live-ID集合；非空source state只对Basic返回的`U`个更新做owner分组。host线性查看该epoch捕获的`S`个source以保持稳定调用顺序，但只有其中`D`个dirty source执行callback。若返回`R`个snapshot和`I`个声明invalidation，host发布阶段为`O(R log R + I)`；inspection runtime只遍历该source当前的`B`个列车binding并做hash membership，标记成本为`O(B × I)`，不调用插件规则。每个active controller再以`O(S_train)`重编译自己的source限制。整个过程不扫描所有列车×所有area、不遍历完整路线，也不重跑perception；area几何查询复杂度归Basic监测区契约。source替换的编译成本只发生在配置变化时，不进入每tick热路径。

selection revision未变时continuation检查为`O(1)`；变化时比较`W`个已注册entry并只为实际变化的entry构造update，成本`O(W)`。这不扫描Core的全部turnout ports，也不把物理分支语义放入SimpleATO。

`process`只由Core serialized physics coordinator调用，不并发重入；handler必须同步返回且不得调用Bukkit、阻塞I/O或把安全工作异步延后。Dispatcher只在route建立/延长时绑定冻结route与Mission progress的映射，不参与逐epoch限制执行。epoch N写入的snapshot和completed stamp由controller在epoch N+1规划运动前直接消费，因此Bukkit主线程停顿不会使旧限制继续驱动车辆。source替换可来自另一线程，但生产adapter仍须先等待当前physics epoch结束再允许插件卸载或废弃callback owner；volatile snapshot只保证epoch内一致性，不替代插件lifecycle barrier。具体插件内部可以根据B变化同步重算A；host当前不建立通用fixed-point依赖图。

Bukkit通过`SignalEpochSourceService`按`(plugin owner, world UUID)`安装完整source集合。普通`replaceSources`是no-op commit callback的简化入口；需要把插件definition publication/sidecar与area geometry一起提交时使用`replaceSourcesAtomically`。host在同一个simulation lock内先检查plugin/world、removed source的retained restrictions、跨owner identity，并把所有owner的候选全集交给同一个runtime完整编译；这些步骤任一失败都不调用commit callback。callback失败则旧runtime state和owner sets均保持不变；callback成功后只用预构造对象替换runtime引用与owner-set引用，physics epoch不得在callback与安装之间穿插。callback不得重入同一个host signal service或等待需要simulation lock的任务；重入会在其他source类别或owner记录发生变化前被拒绝。source/area冲突不会部分生效。replacement删除部分source ID前，会低频检查该source保留的所有列车流是否已经显式发布更高revision且`restrictions=[]`，不能把“以后不再callback”误当成撤销旧限制。完整owner删除不接受空replacement，而是和基础资源一起通过`InfrastructureResourceService.unregisterOwner`执行：所有检查通过后才移除两侧注册；无关列车和空restriction流不构成占用。每个成功epoch复用已经构造的live train ID集合清理已离开世界的列车流；失败epoch在Stall前不执行这项清理。强制插件禁用无法继续调用其代码，因此host从下一epoch快照删除失效callback；只有该source仍保留非空restriction的world才由既有Stall owner fail-close，单纯安装但IDLE的source不会使世界停运。世界卸载和JavaRailways自身关闭属于正常销毁，不触发故障恢复协议。world reload是新的runtime binding，旧UUID不会自动复活旧source集合。

## Good / Base / Bad

- Good：B保护区同epoch产生ENTER+LEFT；拥有B的source被调用一次，在内部同步更新B及受其影响的A，然后返回完整restriction snapshot。
- Base：baseline后所有area不变；Basic返回空batch，任何source都不调用，旧restriction仍按revision语义保留。
- Good：某watched turnout entry的selected exit变化；只唤醒注册该entry的source，同一source的area和continuation变化仍合并为一次callback。
- Base：其他道岔使全局selection revision前进，但所有watched entry值未变；不唤醒source。
- Bad：handler没有返回某列车便希望清除旧限制；必须显式发布更高revision空snapshot。
- Bad：把update放入Bukkit/event queue下一tick执行；这会跨过Core安全epoch边界。
- Bad：source返回旧epochstamp或其他sourceId并继续发布；host必须在第一个publisher side effect前拒绝整批。

## 必需测试与剩余接线

- exact Core ticket等待processor，processor失败不推进latest world frame；
- baseline调用，unchanged area保持handler IDLE；
- 同epochENTER+LEFT一次完整到达owner，非owner不调用；
- wrong source/stamp、missing/duplicate train在任何publication前拒绝；
- publisher非ACCEPTED与handler exception向Core传播；
- inspection invalidation在完整结果校验后按source顺序发布，且只唤醒同source当前binding中的声明；
- source/input/result collection defensive copy。

尚未完成：具体插件的信号依赖传播、制式策略和呈现。Host inspection lead-time声明编译、缓存与激活调度已在Stage 1完成。
Immutable revisioned presentation、per-object latest-only Bukkit/Folia dispatch与具体模型应用均由具体信号插件负责；
`SimpleATOBukkit`只适配SimpleATO自身的平台功能，Stage 1也不为尚无消费者的需求增加临时presentation API。
出现真实跨插件平台需求后才能单独评审host public service。上述Stage 2具体策略和呈现工作完成前，不得声称完整信号系统已经生产可用。
