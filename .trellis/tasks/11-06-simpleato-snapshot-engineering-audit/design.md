# 首批设计：物理帧统一与整车原生限速

## 已批准追加：授权交接与边界复用

原真实回放暴露的 F6–F8 现已由用户批准，实施边界、观测证据与验证见 [追加说明](research/authority-handoff-repair.md)。控制器按真实物理位置继续消费保留授权；信号端保留已有端点；不新增授权策略、容差、物理副本或持久化接口。既有首批设计继续有效。

## 已批准追加：车门状态静态证明

F9 在现有 `TimetableValidator.validateService` 的 `State/process/walk` 有限证明中增加最小车门状态域：`UNKNOWN / SECURED / OPEN`。跨同一 service 的 run segment 继续传播，选站候选、工作预算、深度限制与 memo 全部复用现有管线，不另建第二套遍历。终到只计 arrival，origin 只计 departure preparation；无门程序不能清除已知 `OPEN`，但在非 `OPEN` 输入下与运行期一样形成 `SECURED`。完整转移与退化契约由 [Timetable model 与 validation](../../spec/javarailways/simpleato/timetable-model-and-validation.md#确定性车门证据静态传播current) 唯一定义。

当状态明确为OPEN而下一动作已有运行期关门前置条件时，validator在该动作地址报错。首个目标是当前真实反例的`BeginStationStop(doorSide=NONE)`；单纯Reverse不改变门状态也不触发错误，允许换向后继续同一次有门停站。应复用已有program traversal/地址和错误聚合，不新建运行时状态机或在WebUI复制校验。运行期`stationDoorsSecured`门禁仍是最终安全防线。

若现有validator无法在不重做完整跨service车辆分配的情况下证明某种结构/动态交接，保持UNKNOWN并交给运行期，不扩大为全局数据流框架。本批不自动修时刻表、不执行terminal departure preparation、不把NONE解释成关闭。

## 已批准追加：整服停机模型 terminal retirement

Paper 只保证附属插件与宿主 `onDisable` 的调用顺序，不等待附属插件跨 region 的异步模型删除；精确依赖版本也没有可等待的 pre-stop 事件。实现因此不拦截 `stop` 命令、不在 disable callback 阻塞，也不新增卸载协调器。

现有 `closeOwner` future 继续是唯一完成信号。正常插件停用和热卸载保持“实际实体删除完成”语义；仅在 `Bukkit.isStopping()` 已为真、整服 teardown 不可逆时，service/handle 进入 terminal retirement：撤销内部 owner/active/outstanding/task 与显示意图，正常完成原 cleanup future，不调用 region/entity scheduler。安全性只建立在当前生产后端全集上：native ordinary display 与 editor text 均非持久化，EntityLib visual 仅存在于 packet/container 内存；未来持久化后端不得自动继承此路径。

terminal 判断必须进入 handle 的 host-disabled/rejection 公共终点，而不能只写在 service `close()`：已接受回调可能在 host flag 清除后、service close 前运行。真正早于整服停机完成的清理错误仍保留；停机开始后仅因无法调度物理删除产生的失败转为 terminal retirement。完整证据见 `research/model-cleanup-shutdown-order.md`。

状态：已获用户批准，首批实施中。依据 `prd.md` F1–F5 和已有审查报告。首批范围是 K01–K03、T01、C01，其余发现明确后置；执行结果另记 research/implementation-validation.md。

## 1. 架构与责任

Core 原子发布的 TrainFrameSnapshot 是列车物理事实唯一来源；Basic perception、SimpleATO 控制状态、调度器及故障报告仅持有所需采样时点的同一不可变帧引用。车头/车尾/速度/车体不可再分别采样和拼接。任务进度、站停、门、外部 ticket、累计控制行程仍属于 SimpleATO，不试图从物理帧反推业务状态。

一次 worker 决策可以读取上一个已完整发布的物理帧，这是其正常输入；本 epoch 完成通知必须在本 epoch frame 已发布后读取结果。这两个采样阶段不能混淆。实现不新增“快照一致性证明”对象、重复镜像缓存或在每处再比较头坐标。

## 2. 物理发布顺序

调整现有阶段顺序为：workers join → Core buffer flip → 完整 train frame publication → controller publication hook → 既有 frame/railway processors 与世界完成发布。各阶段保留既有 ticket/close/stall/halt 门禁和 topology→state 锁序；第三方 hook 仍在锁外。不新增线程、全局锁或第二条发布管线。

精确切分需同时保持当前 failure contract：部分已发布状态不假装可回滚；失败 ticket 不冒充成功世界帧；close 后不继续 Core flip/frame/mailbox。不能只把某行搬进持锁区而使 close 等待任意扩展。

`TrainController.commit()` 当前也是先 hook 再 train.commit，必须同步改正，否则手工/测试路径继续读旧帧。重新界定或移除仅服务旧顺序的内部 helper，两个入口遵守同一个“先物理帧，后控制状态通知”合同，不额外 commit 两次。

## 3. 类型迁移与历史诊断

沿 research/train-snapshot-role.md 清单迁移 Core、Basic、SimpleATO、SimpleSignals 测试及 Bukkit 的隐式消费者，并删除 TrainSnapshot 定义。必要公开源码 API 直接更新，不保留 old-type alias、双 writer 或新物理摘要 DTO。无冻结旧开发 API 支持要求。

PublishedAtoState 如需物理关联只保留 frame 引用；不能使用 frame.copy(speed=controllerSpeed)。未提交速度是控制诊断，不得冒充已发布物理速度。Dispatcher 在 owner tick 捕获一次帧并传给本轮相关操作，人工状态不读取已退休 ATO 的位置。

初态、故障、ATP context 需要保留当时帧引用；后续读取不得替换成最新 frame。完整帧保留的数据更多，因此迁移必须核对当前初态记录 owner，在原有删除/世界清理入口释放记录，不建立新 lifecycle manager。若现有生命周期不足，先做最小 owner 内清理，不能无限留存完整历史帧。

诊断 JSON 继续显式选择现有小字段；不输出整个 frame/render 对象。保留现有 schemaVersion/字段形状，旧诊断计数从其所属上下文传入 writer，不能把 ATO tick 悄悄改成 frame.revision；frame 物理 revision/epoch 与控制计数分别理解。若实现发现保留字段语义必须改变磁盘契约，停止该扩展并返回规划，不猜测升版。世界/时刻表/车辆保存格式不变。

## 4. 车长与滚动授权

沿已有 bodyTraversal 计算实际保守跨度，保留重复 rail occurrence 与方向，保留已有几何上界抑制解算抖动的行为。本批不重做长度缓存，也不顺带修 I01 的全索引重建。

Dispatcher 不再拿 ATO 副本车头筛选 Core frame。inspection 生产输入只有一致的物理帧；测试直接构造有效 frame，而非依靠 nullable length 维持任意默认值。静态编译阶段允许未知车长的模型不在删除范围。

`end ?: route.totalLengthMeters` 删除。合法活跃 Core 列车应有完整 frame/body；真正缺失/非法时，沿现有错误路径说明列车与原因并停止新增申请，不假造长度、不换成另一套申请策略、不用旧缓存证明净空。已有 Basic grant 仍按既有安全生命周期持有和车尾释放。

资源请求仍由现有滚动器产生，再按具体入口的出口+车长+安全距离抬高到需要的边界；真正存在完整区间需求时可合法申请较远边界，测试不能笼统要求边界永远小于全路线。

## 5. 整车限速

从本次捕获的 frame.bodyTraversal 得到当前车身覆盖的轨道，读取已有原生 CURVE/SUBGRADE effective limit，形成距离零的当前限制；覆盖无转向架落入的短轨道。原生轨道限速是已有静态元数据，不引入新 epoch/变更事件系统。

自动和手动共用小型读取/归约逻辑，分别接入既有 reference/protection targets，不再各自维护“当前车头限速=整车限速”的假设。不新建 planner、signal source 或保护模型。既有前方入口信号继续负责提前减速；body 限制只在车尾真实清除后释放。世界级安全监测仍独立存在，不用删除 monitor 消除告警。

## 6. 取舍、风险与边界

- 修改 Core hook 顺序是主要风险，必须用真实 epoch 和 close/stall 并发回归，不只直接调用 ato.tick。
- 两项共享控制输入与诊断上下文，采用一个任务内串行 A→B 批次，避免两个 child 同时修改 SimpleATO/控制运行时；各批独立测试，最后集成检查。不是一次全模块重构。
- `Train.publishedFrame` 公共可空性不等于生产应使用假数据；无完整帧实现无法满足自动驾驶输入要求，应在既有入口明确拒绝。
- 不修改 Basic 资源仲裁、不改变站停/授权停车余量、不笼统删除数学浮点 epsilon。
- 现有本地真实数据可用；本批不触及测试服务器。源码可按批次回退，本批无持久化迁移。提交/部署另待授权。
