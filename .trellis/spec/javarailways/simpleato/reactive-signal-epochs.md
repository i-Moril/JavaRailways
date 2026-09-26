# SimpleATO 响应式信号 epoch 契约

## 状态、范围与归属

状态：`CURRENT`的平台无关第一批运行时接缝。触发：修改`SignalEpochSource`、`SignalEpochRuntime`、监测区变化到restriction publication的数据流，或Core railway epoch composition。

SimpleATO拥有一次完整physics epoch内的source路由、返回值校验、restriction发布及到controller的production传递。具体信号插件拥有固定闭塞、保护区域、联锁、下游依赖传播和aspect规则；Basic只派生监测区物理变化；Core只提供同步epoch边界。本契约包含运行期完整source集合的原子替换语义，但不包含Bukkit对外source注册/显示、插件禁用策略、inspection-distance缓存或通用依赖图。

## 正常主流程

```text
Core捕获一个尚未公开的RailwayWorldFrameSnapshot
  → SignalEpochRuntime投影唯一RailwayObservation
  → 一个Basic RailwayAreaOccupancyEngine派生changed/baseline area updates
  → 只按area owner唤醒有update的SignalEpochSource
  → 每个source同步处理自己的完整update子集
  → host先校验全部source结果
  → 按sourceId、trainId确定顺序发布到world-local immutable snapshot store
  → 所有publication ACCEPTED后callback返回
  → Core才发布world frame并完成exact ticket
  → Dispatcher读取active train的最新snapshots并同步到controller已有adapter
  → 用当前route/progress一次apply，下一次controller tick使用新约束
```

第一次observation是baseline，因此每个至少拥有一个area的source都会调用一次。后续若area的occupants和crossings都不变，则该source保持IDLE；不通过每tick无条件callback查询状态。同一epoch的`ENTER`、`LEFT`保留在一次area update和一次source调用中，不进入跨epoch事件队列。

## 公共值与callback

- `SignalEpochSource(sourceId, areas, handler)`：一个已安装source snapshot中的immutable binding；`sourceId`使用与restriction相同的namespaced语法，source至少拥有一个area，area ID在整个snapshot唯一。
- `SignalEpochInput(observation, areaUpdates)`：immutable value；updates只包含该source拥有且本epoch baseline/changed的area。
- `SignalEpochResult(publications)`：完整immutable snapshot publications集合；空result表示本epoch没有restriction变化，不表示撤销旧限制。
- `TrainRestrictionPublisher.publish(snapshot)`：平台无关publication seam；只有`ACCEPTED`允许epoch继续。

撤销必须发布同`(sourceId,trainId)`流内更高revision且`restrictions=[]`的完整`TrainRestrictionSnapshot`。这复用现有revision contract，不增加nullable、隐式超时或“没有返回即清除”。所有public collection在构造时复制，callback只能读取。

## 校验、顺序与失败

runtime在任何publisher调用前先收集并校验本epoch全部handler结果：

1. snapshot的`sourceId`必须等于callback source；
2. `observationStamp`必须精确等于本次observation；
3. `trainId`必须仍在该observation中；
4. 一个source result对同一train最多包含一个完整snapshot。

通过后按`sourceId`、`trainId`排序发布，使相同静态binding和输入得到相同side-effect顺序。handler抛错、返回值非法、publisher抛错或返回非`ACCEPTED`都会向Core抛出；Core不发布候选world frame并Stall。已通过校验不保证多个publisher调用具备事务rollback：较早调用接受的完整replacement可能已经改变、甚至显式清空该来源旧限制，但任何后续失败都会在下一个physics epoch开始前永久Stall当前coordinator，因此这次部分publication不能驱动车辆继续运动。这里不增加一套rollback或两阶段publisher协议。

## 表示、复杂度与线程

runtime以空source state启动。`replaceSources`先完整复制、排序、验证source/area唯一性并建立新的`areaId → sourceId` map和Basic rail-local occupancy index；全部成功后才用一次volatile写替换state，失败保留旧state。每个`process`开头只捕获一次state，因此并发替换不会把两个集合混入同一epoch；新state第一次消费的frame建立自己的baseline。空state直接返回，不投影observation或扫描列车。

`TrainRestrictionSnapshotStore`是每world一个的value-only交接点，不查找、注册或保留live train/controller。一次成功publication只对应列车的concurrent-map entry内替换`sourceId`，并原子公布新的immutable per-train list；不复制其他列车的值。旧reader仍持有完整旧快照，新reader按source ID稳定顺序获得最新快照。写入成本为`O(S_train log S_train)`，Dispatcher查找为`O(1)`并线性处理该列车的source数。

每个非空epoch将Core frame投影一次并为`T`列车建立一次live-ID集合，只对Basic返回的`U`个更新做owner分组；host线性查看该epoch捕获的`S`个source以保持稳定调用顺序，但只有其中`D`个dirty source执行callback。若返回`R`个snapshot，host额外成本为`O(T + U + S + R log R)`，不扫描所有列车×所有area，也不重跑路线perception。area几何查询复杂度归Basic监测区契约。source替换的编译成本只发生在配置变化时，不进入每tick热路径。

`process`只由Core serialized physics coordinator调用，不并发重入；handler必须同步返回且不得调用Bukkit、阻塞I/O或把安全工作异步延后。Bukkit production每tick在等待exact ticket后运行Dispatcher，隔离仿真则在两次`stepSimulation`之间运行Dispatcher；因此epoch N写入的snapshot在下一次controller tick前已进入原有constraint manager。source替换可来自另一线程，但生产adapter仍须先等待当前physics epoch结束再允许插件卸载或废弃callback owner；volatile snapshot只保证epoch内一致性，不替代插件lifecycle barrier。具体插件内部可以根据B变化同步重算A；host当前不建立通用fixed-point依赖图。

## Good / Base / Bad

- Good：B保护区同epoch产生ENTER+LEFT；拥有B的source被调用一次，在内部同步更新B及受其影响的A，然后返回完整restriction snapshot。
- Base：baseline后所有area不变；Basic返回空batch，任何source都不调用，旧restriction仍按revision语义保留。
- Bad：handler没有返回某列车便希望清除旧限制；必须显式发布更高revision空snapshot。
- Bad：把update放入Bukkit/event queue下一tick执行；这会跨过Core安全epoch边界。
- Bad：source返回旧epochstamp或其他sourceId并继续发布；host必须在第一个publisher side effect前拒绝整批。

## 必需测试与剩余接线

- exact Core ticket等待processor，processor失败不推进latest world frame；
- baseline调用，unchanged area保持handler IDLE；
- 同epochENTER+LEFT一次完整到达owner，非owner不调用；
- wrong source/stamp、missing/duplicate train在任何publication前拒绝；
- publisher非ACCEPTED与handler exception向Core传播；
- source/input/result collection defensive copy。

尚未完成：Bukkit对外source注册/禁用生命周期、具体插件依赖传播以及immutable revisioned presentation bridge。完成这些前不得声称响应式信号系统已经生产可用。
