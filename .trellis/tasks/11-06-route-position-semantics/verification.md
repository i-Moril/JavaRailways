# 执行与验证记录 — 2026-11-06

## 当前结果

三项代码修复及独立复核完成；全局重放通过原故障点，但没有全程成功。后续延误已完成对照定位，证据见 research/delay-10026-accounting-semantics.md，并移交后续任务规划。
用户已要求完成本任务并确认提交计划；本记录随工作提交保存，随后执行归档及日志记录。不部署。遵守用户确认的范围，未改信号规则或放宽 ATP。

## 已落实修改

- Basic 普通图弧展开使用已知查询端点，不从图边累计距离反算覆盖。
- SimpleATO 通用位置投影不再使用停车容差或截回端点；冻结路线与 inspection 坐标转换也保留实际数值。下游范围拒绝继续有效，不支持非法值穿透。
- 停车允许偏差保留，通过已有 ObservedAtoMissionOrigin 使下一 Mission 从实际停稳车头开始；欠停首段优先于后续重复轨道 occurrence，避免跳圈。
- 站台距离直接复用已经选定的 Mission 前缀长度，移除第二次位置搜索及两份距离的停车容差比较。
- 冻结车体越界不靠停车容差修补；几何接缝使用既有 1e-6m 数值精度处理累计加减，并保留真实坐标。控制目标区间裁剪和停车验收不机械删除。
- 同步三个 owner specs；独立审查发现的绕行分支顺序问题已修复并再次复核，无剩余可行动代码发现。

## 红绿证据

1. Basic 新4项端点回归在旧实现失败；运营适配新1项失败。producer修复后 planner/restriction/uniqueness/projector/adapter 共53项通过。
2. 真实双轨环线欠停测试修复前期待3段、实际1段；调整分支顺序后包含在最终全量通过结果中。
3. 前轮投影边界6项在旧截断实现失败，本轮保留并通过。此前全面严格匹配导致的15项 SimpleATO 失败，本轮完整运行均未再现。

## 最终模块验证

最终命令：

```sh
./gradlew -I /tmp/jr-rolling-tests.gradle -I /tmp/jr-rolling-global-replay/replay.gradle :Basic:test :SimpleATO:test :SimpleSignals:test :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava :BukkitPlugin:rollingGlobalReplay --continue --offline --console=plain -Pkotlin.daemon.jvmargs=-Xmx4g --max-workers=1
```

构建成功；XML 统计：

- Basic：467项通过。最终调用复用本轮全量测试缓存，之后 Basic 源码未变。
- SimpleATO：774项通过。
- SimpleSignals：677项通过。
- BukkitPlugin：729项，728通过、1跳过；跳过的是已有 AtoStoppingAccuracyExperiment.measure，不是为修复禁用的测试。
- 合计2647项，2646通过、1跳过；Java signal API consumer 编译通过。
- git diff --check 通过。仍有既有 JDK/native-access/deprecation 告警，没有通过关闭检查掩盖它们。

## 全局离线重放：不是全程成功

本次验证日志副本：/tmp/jr-rolling-global-replay/position-fix-final-replay.log（12:19:55–12:20:10 UTC）。replay.log 是后续诊断复用的输出路径，不能作为该次验证的固定日志。修复前 Tick302 记录另存 /tmp/jr-rolling-global-replay/before-route-position-semantics.log。

使用原时刻表、真实基础设施、S100与信号资产，保持重放原有 delayThresholdTicks=1200；5个服务使用S100，未恢复浏览器全部指派状态，不声明浏览器条件完全相同。

- 静态验证成功，原 Tick302 站台端点错误不再出现。
- 原 Tick5403 的101N同一轨道车头仍在 AB=582.8596258573868，状态 APPROACHING，正常继续，没有原 ATP 自占用红灯错误。
- 最终结果是 DelayExceeded：Tick10026、102P/new-timetable/2、延误1226 tick，阈值1200。快照显示 REVERSING / waiting for atomic ATO reverse preparation；该瞬时状态不是延误原因的证据。
- 原始验收回放未调整阈值、修改时刻表或忽略错误，不能把 JavaExec/构建成功描述成完整模拟通过。后续只运行诊断对照：单交路仍延误1217 tick；相同路线/车辆的简化运行线预览精确复现四段计划时间，却缺少真实沿途限制；全局回放终到前晚859 tick，终到至折返比计划多367 tick，合计1226。单独提高阈值并限制到10040的诊断显示折返10026–10028完成，不是阻塞；这不替代原阈值验收。

## 验证边界与交接

真实环线回归验证任务提交后的完整路线和不瞬移，使用测试准入/执行器，不是完整绕圈行驶验收；完整离线重放见上。未连接服务器，未进行浏览器/游戏验收。

Trellis context manifests验证通过，但 Dispatcher spec超过单文件自动注入字节上限；后续代理必须完整读取源文件，不能只依赖可能截断的注入。没有为此修改项目全局配置。

用户已确认 Phase 3.4 提交计划，工作提交后执行 trellis-finish-work 的归档和日志步骤。只提交本任务产品代码、测试、三个规范及本任务文档；TODO.md及先前无关untracked文件不纳入。全局运行线模拟和停站时间语义另开规划，后者必须等待下一轮用户讨论，不把当前实现当成已确认的正确语义。
