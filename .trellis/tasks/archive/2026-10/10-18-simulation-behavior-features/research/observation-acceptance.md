# Observation 构造隔离验收补项（2026-10-20）

## 最小缺口与修改

父任务 AC4 要求实际 World 与模拟 World 的三组并发组合。原 `DefaultRailwayObservationServiceTest` 已用真实 `RailwayObservation.fromCore` 的列车列表读取 barrier 暂停 A，并验证 B 的真实物理位移、observation 发布及领域阶段继续推进，但两个测试 World 均使用默认 LIVE 模式。

本补项仅修改该测试，将原方法 `conversion paused inside fromCore leaves another world physics publication and domain progressing` 参数化为：

- `LIVE paused conversion leaves LIVE progressing`
- `LIVE paused conversion leaves SIMULATION progressing`
- `SIMULATION paused conversion leaves SIMULATION progressing`

两端模式在 `createTestWorld` 创建真实 CoreWorld 时传入 `WorldExecutionMode`，并断言实际 World 属性与输入一致。原 `ConversionBarrier` 未改动，仍确认暂停发生在真实 `fromCore` 内部，没有新生产 hook、转换替身或标签式模拟。

## 保留的断言与生命周期

- A 的真实转换暂停且 release latch 仍未打开时，B 完成 exact physics ticket，物理 epoch 到 1，真实列车前进 0.25m。
- B 完成共同 `OperationalWorldRuntime.afterPublication` 领域阶段及 observation 通知，通知恰为 epoch 1，服务 latest 已发布；A latest 仍为空。
- A 释放后返回的 observation 与相同 frame 的缓存查询保持对象身份。
- 原同 World 实际转换串行、一次通知、listener 锁外重入、remove/close 迟到结果失效用例完全保留。
- 两个 World 仍在 finally 真正停止 physics/tick worker、关闭领域 runtime，并释放服务、guard 和测试 executor。

生产服务接收 UUID/frame，不直接分支读取运行模式。模式矩阵仍有意义，因为它使用实际不同模式的 CoreWorld 及相同真实物理/领域装配，覆盖父任务要求的组合，而不改变服务 contract。

## 验证状态

实现者已完成 `git diff --check`，遵守单一 Gradle 构建令牌约定，未自行启动构建。根会话在本修改完成后执行最终完整门禁，结果为 `BUILD SUCCESSFUL`；本次直接复核保存的 observation XML：共 12 项测试，0 failures/errors/skipped，包含以上三个确切 display name，均实际执行通过。

证据：`../simulation-parent-acceptance-baseline-20261020/evidence/final/BukkitPlugin/TEST-work.art1st.javarailways.bukkit.api.internal.DefaultRailwayObservationServiceTest.xml`；完整门禁日志为 `/tmp/simulation-parent-complete-gate.log`。根会话汇总本次 BukkitPlugin 544 项、8 个模块合计 2507 项／364 suites，全部零失败。本补项的直接验收证据是保存 XML 中的 12 项及三组明确 case；整体模块计数由根会话汇总，不替代本用例断言，也不代表 Paper/Folia 现场验证。本修改前候选的检查仍属于历史证据。

无生产源码、依赖、持久化格式或版本变化，未提交/合并/部署。
