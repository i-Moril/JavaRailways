# 定义编辑影响合同

## 1. 状态、范围与所有权

CURRENT；owner 为 SimpleSignals。解决在线编辑许可误包含同世界全部信号定义的问题。
只改变编辑影响范围的计算，不新增锁定系统，不改变 Basic 的静态运营依赖、列车动态占用或编辑许可语义。
完整候选编译仍是异步低频操作；本合同不要求增量编译 catalog。

## 2. 模块工作方式

`applyDefinitionPatch` 在 worker 上校验 patch/revision，完整编译候选 publication，计算旧、新状态的编辑影响，
再向现有 `InfrastructureResourceService.acquireEditPermit` 提交 rail IDs。
Basic 判断这些轨道是否涉及已应用时刻表、列车占用或其他编辑。拒绝则不写盘、不替换 source。
通过后沿用 staged save 和原子 source/publication 提交流程。

## 3. 内部机制

按稳定 ID 比较各类旧、新定义，取得实际改变的 region、table、block、signal、indicator。
分别在旧 publication 和候选 publication 收集影响，最后取并集，覆盖删除、移动和依赖改绑。
如果 topology root 已更新，还比较已有的区域内部、标准化 block traversal/长度、进路所需道岔连接和信号依赖路径。
只把实际改变的派生值加入起点；排除 revision-local 标识与 callback 对象身份。
无关在建轨道导致 root 换代，不等于所有信号定义都被编辑。

- block 改变：包含完整 block traversal，并以其 section 作为依赖传播起点。
- region/table 改变：包含相关区域边界、内部和进路；进路 section 与入口选择作为传播起点。
- region 与直接后继 block 互相影响准入 floor；变更 block 时不能遗漏前驱 region，变更 region/table 时不能遗漏直接后继 block。
- signal 改变：包含其受控 section 和所有结构上可受影响的消费者。
- indicator 改变：包含旧、新放置轨道和绑定入口的进路，不把仅显示模型的设备当作限速提供者。

依赖检查复用 `OneWayInvalidationGraph` 的邻接表，使用结构可达遍历，每个节点访问一次。
与运行时 `propagate` 不同，这里不执行规则，不因灯色未变而停止，也不按当前道岔开放方向排除路径。
被影响的信号还包含它已编译的下游查找路径，不能只保护信号所在轨道。

## 4. 接口与单位

内部入口：`SimpleSignalsDefinitionEditImpact.railIds(previous, candidate): Set<UUID>`。
`SimpleSignalsPreparedDefinitions.affectedRailIds` 表示本次编辑影响，不再表示整份世界定义的轨道全集。
`OneWayInvalidationGraph.reachableConsumers(providers)` 包含起点及所有结构可达消费者。
`SectionSignalRuleRuntime.affectedBindings(sections, signalIds, entries)` 将这些起点映射为受影响的信号 bindings。
轨道身份使用 Core rail UUID；不把 rail/node 改成新的 SimpleSignals 资源。

## 5. 不变量、线程与版本

影响收集只读取已编译 publication，不修改共享信号状态、不调用 Bukkit、不申请列车资源。
旧、新结果都必须检查；删除后的对象不能因候选中不存在而失去保护。
删除保护信号时，旧 publication 中的 signal binding 仍是影响收集起点，必须包含其受控进路轨道。
正常 Dispatcher 路径先 `FrozenRouteEditProtection.protect` 再绑定 inspection；结束时先
`InspectionRuntime.removeTrain` 再解除路线编辑保护。因此相关检查仍有效时，删除必须返回 `EDIT_BLOCKED`，
不能依赖删除后识别旧 declaration 的兼容分支。物理 worker 对已捕获 binding 仍核对当前 source 身份，
`removeTrain` 后的下一次检查更新过滤掉已移除的检查。此结论限于上述正常生命周期与统一编辑许可，
不代替其他调用方的生命周期合同，也不是已在执行回调的取消机制。
完整候选仍绑定 exact topology root；许可后 root 变化须在保存前拒绝。
计算在异步 authoring 路径，不进入物理 tick 或逐列车信号计算。
不改变持久化字段与 schema，也不增加兼容分支。

## 6. 校验与失败行为

- 无变化、非法 revision、非法引用或依赖环：候选校验拒绝，尚未取得许可。
- 有影响但被静态运营依赖、动态 claim 或其他编辑阻挡：透传现有 host decision/blocker。
- 许可后 topology/base publication 改变：typed stale，保留旧状态。
- staged save 或 source validation 失败：保留旧磁盘 primary、旧 source 和旧 publication。
- 成功：沿用原子 commit point；关闭许可，不新增 rollback 状态机。

## 7. 性能、复杂度与退化

设定义比较规模为 D、block 数为 B、route 数为 Q、边界与同轨道 block 入口的候选比较数为 H。
受影响图节点/边为 A/Ea，收集的几何、依赖路径和显示器绑定引用数为 G。
普通影响收集为 O(D+B+Q+H+A+Ea+G)。block 按入口轨道、route 按入口分组一次，避免全轨道对比和每个显示器重复扫描全部进路。
可达节点只访问一次；几何收集与去重按访问引用线性。
topology 换代另增加已编译派生值比较规模 C 的线性成本，不会为每个对象重新寻路。
这不是每 tick 成本。完整候选编译和存档编码的原有规模成本仍存在且仍在 worker 上执行。
区域内部暂采用整个区域的保守范围，不尝试证明某条未改进路与另一条进路完全无关。

## 8. Good / Base / Bad

Good：运营线路 A 与在建线路 B 无结构依赖，修改 B 的信号不因 A 的时刻表或列车 claim 被拒绝。
Base：移动或删除 B 的信号，同时检查旧、新影响；不能只检查目标位置。
Bad：B 是 A 的下游依赖，或 B 是 A 保护区域出口后的直接闭塞；不能因轨道不同、当前灯色不变而允许编辑。

## 9. 必需测试

- 实际 runtime manager 配合 Basic registry：无关静态运营依赖和动态 claim 不阻挡编辑。
- 被编辑轨道或间接受影响的上游轨道受保护：拒绝且磁盘、publication、source 不变。
- 旧、新路径、删除和位置移动均覆盖；block、region、table、signal、indicator 均有断言。
- 固定进路信号和当前信号分别删除：使用实际 Basic registry 的静态依赖、动态 claim 阻挡；
  拒绝不改变磁盘、publication、source，解除阻挡后同一删除成功。
- 无关在建轨道替换 topology root 后，局部编辑仍不被另一条运营线路阻挡。
- 当前输出不变、当前支路未开放仍收集结构依赖。
- 保留 revision、非法候选、许可后 root 变化、保存失败和阻塞提交的既有原子性测试。

## 10. 迁移、未决事项与 Wrong / Correct

无需存档迁移、公共服务升级或 runtime compatibility adapter。
玩家命令、Wand、inspect/dry-run UI 是后续批次，本批只修复共同事务入口。

Wrong：把整份 old/new publication 的所有轨道作为许可范围；或者只保护编辑对象所在轨道。
Correct：从实际改变的对象出发，在旧、新编译状态中沿结构依赖收集影响，再复用现有许可。
