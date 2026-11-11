# 设计草案：单文档跨线交路

状态：源码调研完成；首版停车跨线已确认，不停站跨线推迟；运营车次与显示信息合同已确认。用户已授权实施；具体API合同以research/implementation-contract.md为准，本文保留背景推理。

## 1. 所有权与基本表示

沿用world级Line/Station/Destination store。主线和支线是两条普通Line，各自拥有UP/DOWN站序；共用同一个StationId C。暂不增加影响寻路或许可的parentLineId/主支线层级。

沿用AuthoringDocument.runs、duties、diagramLines。RUN继续保存本段diagramLineId、lineId和lineDirection。每个duty只编译为一个TimetableService，顺序执行多个运行段，取车与生命周期仍由Dispatcher持有。不要把跨线转换成多个ANY service来重新抢同一列车。

编译结果需要显式运行段描述，建议每段携带稳定runId、基础设施lineId、单独的图归属/方向，以及编译后精确step范围。范围由编译builder捕获实际索引，不能从origin字符串反解或按目标车站名猜测。运行段不嵌套、不重叠；范围内嵌套动作以现有TimetableStepAddress/branchPath定位。展示字段不得成为物理经路/限速授权。

service.lineId保留其现有“整段、未分段service的明确线路设置”能力。多线路分段service必须逐段声明；不允许用service.lineId悄悄覆盖或兜底缺失的区段线路。不是旧格式compat reader；如持久化结构需要调整，按发布基线政策单独审计。

## 2. 编译与同站衔接

建议在现有origin作用域旁提供运行段作用域（最终名称实施前固定）：用runId和明确线路标记包围该RUN生成的步骤，记录范围与图归属；仍复用destination/pass/站内program DSL。不得只删前端两行禁止条件，亦不得通过丢失service.lineId绕过限制。

先从完整duty导出只读衔接关系：前RUN终点、后RUN起点、到发时刻、实际停车点、朝向、夹在中间的FUNCTION/PROGRAM。直接衔接要求同一真实停车位置、offset一致/可解释、实际方向可达；线路UP/DOWN只是各自站序，不等于Core车头方向。换标、换台、折返通过明确站内程序验证。动态程序按已有所有可能结果的证明检查后继，实际执行使用已选结果。

停车衔接C：到达值由前RUN终点保存，发车值由后RUN起点保存；联合视图绑定同一文档两处值，一次更改一个历史事务。停站/门侧配置有唯一归属（到达C的call），下一段origin不能再配置第二次C到站。保留既有字段，允许连接终点编辑必要的最小停站信息；不再由resize/reset无条件清空后丢失。冲突配置明确诊断，不能静默取任意值。

生成一次真实C到站、一次开关门、等待nextDeparture与minDwell同时满足后离开。后RUN起点是继续运行的边界，不再生成一次check/acquire/目的地停车。计划时刻不替代真实到站；晚点继续等待动作完成。

## 3. 线路上下文与限速

由ActiveRun当前执行地址解析并发布currentRunId/currentLineId，而不是通过物理坐标猜线路。进入下一运行段前完成校验、限速更新，再发布/放行该段mission；使用同一个timetable限速key原位替换，不做remove再add，避免无约束间隙。所有命令走SimpleATO既有串行队列与revision边界。

停车连接：最晚在C出发前新段限速必须实际生效；线路切换失败/未知线路/缺cap保持停车并返回可定位原因。跨线过程保留原有Basic grant/尾部释放和其他限速owner。站内程序段保持当前安全限速；切换下一段前不得提升到未确认的新值。出库/EMU输出首次运动的线路由实际首运行段确定，不能等首次运动后才设置。

补齐所有重建入口：首次service启动、重规划替换ATO、人工接管后恢复、重联/解编输出及新交路接管；它们必须恢复当前段限速，不得重读最初service.lineId。取消/终止仍只移除本交路owner约束，不动信号/其他限速。

后续如另行支持不停站：增加显式通过式边界，取消当前ORIGIN/TERMINAL强制停车假设；连续编译跨C的mission/路线，C位置绑定精确route occurrence。低限速进入前制动，高限速不能提前解除仍适用的旧限制，边界跨越与车体/尾部清空需明确。可复用RouteSpeedRestriction与TrainRestrictionConstraintAdapter，但不直接把head-bound区间宣称为tail-bound。重规划/折返须重投影边界，缺证据保持安全停车。此部分不与普通停车连接混作一个tick切换。

## 4. 编辑、绘图、状态与模拟

DutyEditor继续唯一文档/undo/recovery owner。DutyManagement允许选择本文件所有运行线，显示线路、方向、起终点与衔接诊断，并能跳到对应图。线路切换仅改变视图选择。

graph.ts按运行段投影到指定diagramLine，保留真实serviceId/runId/stepAddress映射。authoring预览已有按RUN独立构造的图，可复用；正式编译/模拟结果不能再只按service.diagramLineId筛选。主图显示A-C及本线A-D；支图显示C-E；不把E硬塞进主线站序。运行线改名/删除/复制后的映射来自稳定文档引用和重编译，不能靠service数组索引跨结果匹配。

HTTP/DTO/status增加必要currentRunId/currentLineId/段映射；维持document hash/input identity和stale response拒绝。线路依赖校验覆盖所有段，修改/删除支线不能让旧应用结果继续装作有效。

单RUN Reset继续只重算自己，始发tick不改。HTTP模拟路径当前按service.lineId匹配，需改为精确指定service+run/segment，校验它确实属于请求线路；不能取第一个匹配段。完整隔离模拟执行整交路与全部线路，沿用真实Core/Basic/SimpleATO路径及原资源保护，报告明确段归属。

## 5. 持久化与交付边界

审计独立边界：WebUI editor/recovery、JS-worker消息DTO、SavedTimetable/运行态快照及编译trace。新增嵌套段描述不另造schema；当前未发布开发格式不因本任务自增版本。已有开发服数据若需结构变换，先干运行并保留恢复点；不增加任意旧开发数据的业务兼容分支。

用户已批准本地实施，改动在独立 station-operation-programming worktree 的 codex/single-document-through-running 分支。部署及提交等待后续明确授权。原有已部署代码和主worktree并行工作保持原状。

## 6. 运营车次与显示信息（已确认）

运营车次分组在同一document内引用runId及参与单元，并沿单元在编组事件前后的实际连续路径组织。重联RUN保留单一执行，但允许各单元拥有不同运营车次；不能仅按duty或trainId决定运营车次归属。分组ID与可变显示车次号分开。跨线段合并停站时，保留有序call occurrence，仅合并合法衔接的同一次C停车；不得按StationId全局去重。STOP/PASS与实际运行进度分别记录；候选尚未确定时不伪造唯一已选停车点。

运行计划事实与可编程显示值应分成两个接口：事实来自编译计划/Dispatcher实际进度；显示值由受控动作更新。显示值不成为lineId、运行路径、库存、权限或物理停车决策的输入。现有TimetableRuntimeContext是只读快照，TimetableActionProvider提供注册动作/一次start和模拟factory，可复用注册与执行生命周期，但目前没有显示状态写入口，不能声称现成支持。

显示状态建议由执行owner按具体周期/交路/运营车次实例持有并发布不可变snapshot；库定义不被运行时程序原地修改。提供明确设置/删除/重置操作，原子发布revision；字段名/值可扩展，不先冻结固定的种别枚举、目的地格式或LCD布局。恢复、取消、手动控制、重联/解编输出的状态映射必须显式定义，模拟与live隔离，不能拿全局trainId字典自动继承。具体API/生命周期见 research/implementation-contract.md。

显示生命周期已由用户确认：同一运营车次内跨段保留，进入下一运营车次时重置到新车次默认值。

单元独立显示已确认：发布的数据须能从实际车辆/单元定位到对应运营车次及显示上下文；物理编组变化保留该关联，换车次重置仅作用于切换的单元。不得为实现不同LCD信息而重复生成物理运行service。
