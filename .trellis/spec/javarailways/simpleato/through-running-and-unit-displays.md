# 停车跨线、运营车次与单元显示

## 状态、范围与所有权

CURRENT：一份时刻表内的多线路停车跨线、整编组物理运行、按完整登记单元分配的运营车次和受控显示状态。触发：修改运行段、运营车次、显示动作、编译范围或对应 HTTP/编辑器投影时读取本规范。

`SimpleATO` 拥有模型、校验和 Dispatcher 状态；ScriptWorker 编译可视化生成的 JS；WebUI 的 `DutyEditor` 是唯一文档、历史和恢复 owner。Core 拥有实际车辆与完整单元组成，Basic 拥有行车资源与尾部释放。LCD 设备/版式、跨文档排图和不停站跨线不在此能力范围。

## 一次正常运行

主线 A–B–C–D 与支线 C–E 共享真实 StationId C。编辑器建立 M01=A–C、B01=C–E，放入同一 duty；生成一个 service、两个 `runSegment`，只在服务边界取车或 CHECK 一次。到 C 的停车、最小停站和车门设置属于 M01 的终点；B01 起点只提供后续发车时刻。Dispatcher 等待真实到站、停站及关门 gate，然后切换当前段与线路限速，继续执行同一车辆任务。

运营车次引用完整 RUN 和参与单元槽位。例如共同运行 M01 上 U1 属于 T-D，U2 属于 T-E；解编后两者分别继续自己的段。物理 M01 只执行一次。显示字段不参与路径、线路限速、库存或停车许可决策。

## 内部机制

- 段范围是实际 top-level step 索引的半开区间；嵌套动作继承外层 step 范围。不得解析 `origin` 字符串猜测运行段。
- `segmentAtOrBefore` 为段间程序保留上一线路限速，首段前预加载首段限速；只有地址实际进入段范围，才绑定该段的运营车次与单元。
- 限速使用同一 `timetable-line/<timetable>/<service>` owner 原位替换，经 ATO 串行命令生效。其他约束 owner 保持独立。重规划及固定编组输出从实际继续地址恢复限速。
- 槽位为实际 operating HEAD 起算的 1-based 完整登记单元位置；每段解析为稳定 unitId。反向/重联/解编可改变槽位，不改变单元身份。
- 显示上下文以 timetable、cycle、actual unitId 定位。进入新运营车次创建默认值，同一车次保留变更。新周期仅淘汰重新进入的单元旧上下文，仍独立 HOLD 的同伴保留诊断。
- `proveFuture` 从实际单元种子沿固定反向/重联/解编传递组成；遇到可能改变顺序的条件分支停止推断。只有显示更新的分支不妨碍组成证明。未知后续段列入 `unresolvedRuns`。
- 停站按 occurrence 排序，仅合并连续合法终到/始发的同一 C 停车：保留前段 arrival、后段 departure，并显示 STOP；不能全局按车站去重。

## 接口与字段

`TimetableService.runSegments` 缺省空；每段包含 `runId,lineId,startStepIndex,endStepIndexExclusive,diagramLineId?,lineDirection?,calls`。`calls` 为 `{id,stationId,destinationId,status,arrivalTick?,departureTick?}`；status 为 ORIGIN/STOP/PASS/TERMINAL，计划 tick 不代替实际执行。

`Timetable.operationalTrips` 缺省空；每项 `{id,defaults,segments:[{serviceId,runId,unitSlots}]}`。defaults 是受限 JSON object，1..128 个不重复正整数槽位，最多 128 个非空顶层字段，JSON 深度/节点/文本预算分别为 16/4096/65536 UTF-16 单元。嵌套 key/string 遵守共享 JSON 输入预算。

原生动作：`SetDisplay(unitSlots,values,removeKeys)` 先删指定键再覆盖值；`ResetDisplay(unitSlots)` 恢复当前车次默认值；`BeginOperationalTrip(tripId,runId,unitSlots)` 提前建立下一段的上下文。wire 类型分别是 SET_DISPLAY、RESET_DISPLAY、BEGIN_OPERATIONAL_TRIP，字段使用 `displayValues/removeDisplayKeys/operationalTripId/runId/unitSlots`。

JS 支持 `runSegment(options,callback)`、`operationalTrip(id,definition)`，以及 timed/untimed `setDisplay/resetDisplay/beginOperationalTrip`。程序 SET_DISPLAY/RESET_DISPLAY 不带单元选择，统一作用于当前整列车；程序参数不提供单元槽位类型。原生 TimetableAction 的 SetDisplay/ResetDisplay 空选择表示执行时的所有登记单元，显式非空选择仍供连续显示方案使用；BeginOperationalTrip 必须保持非空选择。BEGIN 是编译控制边界，不是可条件化的编组变换。

如果节点程序在 RUN 前设置显示信息，ScriptWorker 在 gap 内最后一次 acquire/couple/split/reverse 之后、首个显示动作之前插入 BEGIN；同时移动后续段范围及编译 trace。BEGIN 不改变当前 RUN/线路限速。没有前置显示动作时由 RUN 入口初始化。这样站内程序设置的新目的地不会在发车时再次被默认值覆盖。

`TimetableServiceRunSnapshot` 投影 `currentRunId/currentLineId/unitDisplays`；每单元包括 `unitId,operationalTripId,cycleIndex,active,values,plannedStops,completedStops,remainingStops,unresolvedRuns`。`Dispatcher.unitDisplaysForTrain(world,trainId,timetableId,cycleIndex)` 按真实组成查询，包括没有后续 duty 的 HOLD 输出；调用必须在该 world 的 serialized owner 边界。

## 生命周期、线程与版本

定义和快照防御性复制；JSON 状态由 Dispatcher 实例独占，隔离模拟不能共享 live 可变 map。显示动作先验证全部选择及结果，再原子写入。终止/未分配新 RUN 的上下文变为 inactive，后续 setter 拒绝；诊断仍可读取。清理旧周期证明时保留仍有实际上下文的周期。

新增字段有空默认值，沿用当前持久化边界版本；不新增开发数据兼容 reader。已有 checkpoint 的 recovery-required 规则保持，不能把诊断快照当作自动恢复执行授权。

## 校验与错误

- 未知线路、缺少正限速、段重叠/越界、方向站序错误：定义校验失败，未安装 controller。
- 停站与 canonical berth 的 stationId 不符，PASS/STOP 与实际编译移动不符，伪造或非确定移动的固定 call 列表：拒绝。
- 首版必须 ORIGIN/TERMINAL 停车边界；ORIGIN 是编译期计划事实和显示边界，不是运行段入口对换向后车头坐标的重复门禁。初次取车／显式 `Check` 与后续真实 Mission、方向和资源准入分别验证各自的实际物理前置。
- 同一 `(service,run,slot)` 被两个车次认领、缺登记单元或槽位不存在：拒绝。不同车库等既有库存规则由原 owner 校验。
- 车次顺序逆于物理交路、单元身份跳变、遗漏同单元中间运行段：拒绝。不能用相同槽位数字假装同一实体。
- BEGIN 位于其他 RUN 内、不是下一 RUN 边界，或其后还有组成/方向变换：拒绝。分支内 BEGIN 由 branch guard 拒绝。
- SET/RESET 没有活动单元上下文：失败，不自动创建占位单元。其他无车次 metadata 的普通 service 不因此强制要求登记单元。

## 规模与退化

范围查找按有序段线性扫描；单元证明是有限 service/固定事件遍历。运营车次连续性检查遍历车次段对和可能中间 RUN，并检查有限交路可达性，属于编译/应用校验，不能移到 physics tick。每段/编组变化时刷新身份，快照按已证明的车次段生成。无法证明未来组成时输出 unresolved 信息，不猜测路线或复制物理 service。

## 具体情形

普通无分段 service 继续使用明确 `service.lineId`；分段 service 的每段必须有自己的基础设施 lineId。跨 C 时 `diagramLineId` 仅决定在哪张图显示，不能参与限速解析。

U1/U2 同行时可分别设置 `{"destination":"D"}` / `{"destination":"E"}`。U1 下一运营车次重置 U1，U2 同一车次的种别/编号变更保持。反向后 U1 可能从槽位 1 变为 2，必须按实际顺序选中它。

错误做法：每单元复制一条执行 service，或在每条 RUN 开始无条件重置显示。正确做法：共享一次整编组执行，分别维护单元车次上下文，仅在运营车次/周期切换初始化。

## 必需测试

- `TimetableRunMetadataTest`：快照独立性、槽位变化、下一车次/周期选择性重置、C 到发合并、重复站保留、call 真实性与顺序拒绝。
- `TimetableFormationDispatcherTest`：真实解编、分支显示更新、HOLD 同伴查询、缺槽位/错误物理继承在 controller 安装前拒绝、无 metadata 服务隔离。
- `ScriptWorkerTest/StationProgramScriptTest/ScriptWorkerCompatibilityTest`：实际范围、BEGIN 插入和 trace 同步、动作 wire 对齐及受限参数。
- `IsolatedTimetableSimulationFactoryTest`：A–C–E 与 E–C–A、同一列车、C 一次到站、不同后段限速改变真实耗时、重联后解编逐单元显示、重复模拟不修改源 world。
- WebUI `interlineAuthoring/DutyTripDraft.events`：一 service、多段图投影、联合时刻单事务、改名/删除引用、默认值无效草稿与迟到响应 gate。

## 迁移与后续

本任务不改变 schema 版本或提供任意 JS 反向编辑。LCD 通过只读数据接口另行实现；在接入前明确如何从车厢定位实际单元、如何处理 inactive/unresolved 状态。不停站跨线需要独立的连续路线及范围限速设计，不能把 C 的到发 tick 设成相等就绕过站停 gate。
