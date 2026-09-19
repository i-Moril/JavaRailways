# Physics capacity stress test

## Goal

建立可重复的 Core 物理容量测试，回答在指定硬件、worker 数、编组和轨道密度下，多少列活跃列车仍能满足 20 TPS 的物理预算；同时用确定性回归测试证明配置的 worker 确实并发执行。

## Requirements

- 普通 `:Core:test` 必须包含确定性的 worker 并发度回归，但不得运行耗时容量测试。
- 提供独立 Gradle 入口运行容量压力测试，并允许通过 Gradle property 配置列车数、worker 数、车辆数、预热 epoch、采样 epoch、重复次数和 p95 预算。
- 支持直线与连续变曲率轨道，并在报告中记录轨道形状；长编组曲线测试必须验证无脱轨。
- 至少覆盖独立轨道纯计算场景、独立轨道运行/静止混合场景，以及共享轨道高 occupancy 候选场景。
- 每列车使用真实 `CoreTrain`、多节 `CoreCar`、`PhysicsCoordinator`、位置更新、frame publication 和 swept occupancy rebuild；禁止以 no-op controller 冒充列车负载。
- 直接采集每个 `PhysicsTicket` 返回的 `PhysicsEpochMetrics`，报告 p50/p95/p99/max、worker/coordinator 时间、occupancy 和候选检查。
- 压力测试必须验证 epoch 连续、列车 tick 次数、active/total count、无 delta clamp、无非预期碰撞和无 frame mailbox capacity drop。
- 输出 CSV、JSON 与 Markdown 报告，包含 Git revision、JVM/OS/CPU、场景参数和通过/失败结论。
- 不改生产 physics contract、调度顺序、配置或持久化格式。

## Acceptance Criteria

- [x] `:Core:test` 中有 latch/barrier 驱动的测试，证明实际峰值并发数和 worker thread 数等于 `min(workerThreads, activeControllers)`。
- [x] `:Core:test` 默认排除带 `physics-stress` tag 的压力测试。
- [x] `:Core:physicsStressTest` 可独立运行，并可用小参数完成自动 smoke。
- [x] 压力测试支持 `isolated`、`mixed`、`shared` 三种场景和逗号分隔的列车数矩阵。
- [x] 压力测试支持 `straight`、`curved` 轨道形状，曲线夹具必须有可验证的横向偏移和连续变化的切线。
- [x] 每组样本执行预热和正式采样，正式结果按 repetition 汇总并执行 p95 `< 预算` 判定。
- [x] 输出 CSV、JSON 和 Markdown；报告清楚声明这是 Core/headless 容量，不是 Paper/Folia + renderer 容量。
- [x] `./gradlew :Core:test`、小参数 `:Core:physicsStressTest` 和 `git diff --check` 通过。

## Out of Scope

- 真实 Paper/Folia tick、Folia region ownership、Display Entity 提交、网络包和在线客户端容量。
- 自动改变生产 worker 数、物理 TPS、LOD 或降级策略。
- 在未知目标硬件上声明一个通用的最大列车数。
