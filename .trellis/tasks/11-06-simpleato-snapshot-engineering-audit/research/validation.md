# 审查验证记录

2026-11-06；生产基线 `b89b0d5d`。不更改项目构建、生产或正式测试源码。

## 现有测试

1. 默认执行 `./gradlew :SimpleATO:test --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g`：失败。测试 JVM `OutOfMemoryError: Java heap space`，堆栈落在 `PrimeCurveImpl` 构造，由 `StationCallRuntimeTest` 的 4000m 单段轨道舍入回归 fixture 触发。这是测试进程内存失败，不是功能断言失败；本轮没有证明生产会 OOM。
2. `/tmp/jr-simpleato-audit-az6Vw0/audit.init.gradle` 临时设置 Test `maxHeapSize='3g'`、`maxParallelForks=1`、`ActiveProcessorCount=2`。原命令追加 `-I <该文件>`：**788 项测试，85 suites，0 failures/errors/skipped，BUILD SUCCESSFUL，1m18s**。
3. 基线 XML 已复制到 `/tmp/jr-simpleato-audit-az6Vw0/baseline-test-results/`，避免随后探针运行覆盖。临时文件不是持久验收档案；本报告保留计数和关键结论。

## 定向审查探针（预期暴露现有缺陷）

通过第二个临时 init script 给 SimpleATO test sourceSet 加入 `/tmp/jr-simpleato-audit-az6Vw0/probes`；使用 `--tests '*AuditProbe'` 执行，真实生产代码未修改。

- `AutomaticBodySpeedAuditProbe`：使用真实 Core 两车几何；1m 长的 1.5m/s 限速轨道在车身下、车头后，且无转向架落在该短轨。真实 ATO `tick(0)` 构建约束，反射读取 private control frame，**“包含当前整车限速硬目标”断言失败**。几何先决断言通过。这动态验证的是控制输入遗漏，不是加速后脱轨或服务器行车全过程。使用既有 KinematicTrainActuator 且不积分运动，不能冒充真实设备动力学验收。
- `InspectionRetainAuditProbe`：真实 InspectionRuntime 建立绑定，使用未变化的列车集合 `retainTrains(setOf("train"))`，**binding 身份不变断言失败**。结合源码确认无变化仍重建；没有做性能 benchmark。
- 结果：2 tests，2 failed（均为目标断言失败，非编译/fixture 故障），3s。新增源码只在任务 research 或 /tmp，不进入正式 sourceSet。

探针失败恰是审查证据，不能表述成“修复后测试通过”；尚未实施任何修复。

## 验证边界

- Tick 14219 的既有本地真实服数据回放证据由主会话重新核对，见 `known-14219-issues.md`；本轮未修改后再跑对照组。
- 新发现中只有上述两项有新增小型动态探针；其余为逐行来源/消费/生命周期静态证据，具体限制在各报告。
- 未运行测试服务器、Bukkit 客户端验收、全仓测试、API Java 调用兼容构建或形式化证明。
- 现有 788 测试全过与新探针失败同时成立，说明现有用例遗漏了这些边界；不代表整体实现全部错误。
