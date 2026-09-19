# Implementation Plan

1. [x] 在 `PhysicsCoordinatorTest` 增加 latch 驱动的真实并发度测试。
2. [x] 在 `Core` test source set 新增 tagged、参数化的 physics capacity harness。
3. [x] 在 `Core/build.gradle.kts` 排除普通测试中的 stress tag，并注册 `physicsStressTest`，转发允许的 Gradle properties。
4. [x] 运行 focused concurrency test 与完整 `:Core:test`。
5. [x] 用小规模参数运行 `:Core:physicsStressTest`，检查 CSV/Markdown 内容和正确性判定。
6. [x] 运行 `git diff --check` 并审查只包含任务范围内变更。
7. [x] 新增可选择的连续曲线路径夹具，并记录在 JSON/CSV/Markdown 报告中。
8. [x] 用 15 节编组在曲线路径上运行 50/100/200/500 列矩阵，验证无脱轨和容量指标。

## Verification evidence

- `./gradlew :Core:test --stacktrace` 通过。
- 50/100/200/500 列、每列 5 节、4 workers、三场景、20 warmup + 100 measured 的矩阵通过；`requiredTrainCount=500` 容量门槛通过。
- `shared` 场景在 50/100/200/500 列时平均候选检查约为 85/171/344/863 次每 epoch，证明候选负载实际生效。
- 15 节编组、20 m 振幅/500 m 波长曲线轨道、4 workers、20 warmup + 100 measured 的矩阵通过；500 列 p95 为 isolated 18.249 ms、mixed 13.915 ms、shared 19.941 ms，且 `requiredTrainCount=500` 门槛通过。
- `git diff --check` 通过。

## Rollback

所有新增能力仅存在于测试与 Gradle task；若压力 harness 不稳定，可删除 tagged test/task，不影响生产 runtime。
