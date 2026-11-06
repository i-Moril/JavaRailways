# 后续任务准备：运行线模拟与停站时间语义

## 用户指示与阶段

2026-11-06：先完成 route-position-semantics，再准备创建新 Trellis 任务，修复运行线模拟和停站时间理解问题。后者留待下一轮详细讨论。本文件仅是移交准备，不是最终 PRD/设计，不授权实施，不提前设定停站计时合同。原任务归档后再创建 planning 状态的新任务，不直接 start。

## 已确认问题与证据

- 运行线模拟被用来生成排图时刻，但当前将真实路线降为长度、平均坡度、终点停车和整体最高速度，没有使用全局模拟中的沿途限速、信号、资源和站点作业执行链。
- 102P 在相同 S100 配置下，预览输出1525/1363/1552/1098 tick，逐项等于保存的计划区间时间；五交路全局实际为1603/1563/1900/1240 tick。单交路仍在折返任务开始时晚1217 tick，说明并非主要由其他列车阻挡。
- 当前 resetRunTimings 用 arrival + fixedDwellTicks 生成发车时刻；StationStopProgramRuntime 则将最小停站截止延至开门完成后。事实不等于需求：用户不接受把这些差异归咎于时刻表，正确停站定义必须下一轮讨论。
- 当前数据终到8738、折返8800仅隔62 tick，而现行程序配置最小停站400 tick；如何协调终到、折返与下一始发的停站程序，也是待讨论问题，不在旧任务修复。
- UI 的“首个延误来源”目前指超限时所在任务，而非最早因果来源。作为已知关联问题保留，不自动扩大新任务范围。

详细时间线、原始回放路径和验证边界见本任务 research/delay-10026-accounting-semantics.md。临时资产不视为已纳入版本控制的永久回归。

## 后续规划起点（未最终批准）

目标：用于排图的单运行线模拟应在相同基础设施和车辆条件下，与实际执行链的到发时刻一致。
建议方向：复用既有完整隔离模拟执行链，仅放入目标运行线和所选车辆，采集到发事件；不再另建简化求时或兜底算法。尚需在新任务研究中确认入口、信号安装、采样事件和取消/超时清理。

预计检查模块：

- BukkitPlugin TimetableHttpBridge、IsolatedLineSimulator、IsolatedTimetableSimulationFactory：入口、捕获与隔离运行。
- SimpleATO DetailedTrainTravelSimulator、IsolatedTimetableSimulation、TimetableDispatcher、StationStopProgramRuntime：复用路径与事件口径。
- WebUI authoring.ts/resetRunTimings 及运行线模拟调用方：输出映射和停站安排。
- SimpleSignals 与 Basic：使用现有模拟安装和真实约束，不新增另一套规则。

## 下一轮需讨论，当前不决定

停站时间从哪一事件开始、到哪一事件结束；开关门时间是否包含其中；最小停站与计划到发的关系；终到、折返、下一始发之间是否共享/重复计算停站。根据用户回答形成明确需求和回归，再完成新任务的 PRD/design/implement 与最终审阅。不得预设“开门完成后另加400”正确，也不得未经讨论直接改成“到站后400”。
