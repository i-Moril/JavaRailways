# Design

## Boundaries

实现位于 `Core` test source set。生产 `PhysicsCoordinator` 已返回完整 epoch metrics，不需要扩展公开 API。Gradle 新增 tagged test task；普通测试排除压力 tag。

## Deterministic concurrency verification

在一个 epoch 中注册多于 worker 数的 controller。每个 batch 的第一个 controller 通过 latch 阻塞，测试等待所有 worker 都进入后再释放。记录并断言峰值同时执行数、worker thread identity、每个 controller 的调用次数和 ticket 完成边界。测试不使用 sleep。

## Capacity harness

每个场景创建独立 `CoreWorld` 和 `PhysicsCoordinator`，通过 `stepSimulation(50ms)` 快速执行确定性逻辑 tick：

- `isolated`：每列车独占 rail，全部移动，隔离 train geometry/controller/publication 成本。
- `mixed`：每列车独占 rail，固定比例列车保持静止，测 active controller 与 frame publication 差异。
- `shared`：列车在同一条长轨道上保持安全固定间隔和相同速度，增加 occupancy candidate 查询而不产生碰撞。

轨道形状是独立维度：`straight` 使用现有直线夹具；`curved` 使用 X 单调的正弦形水平曲线，按累计弦长建立 `PrimeCurve` 弧长，并通过固定振幅和波长产生连续变曲率。共享轨道起点余量按编组总长度计算，避免长编组尾部越过轨道起点。

轨道曲线对象尽量复用；计时区间不包含 world/rail/train 构造和 JVM 预热 epoch。每个 repetition 使用新 world，关闭 coordinator 后释放引用。

## Metrics and reports

保存正式采样 epoch 的 `PhysicsEpochMetrics`，按 repetition 形成一行 CSV；Markdown 汇总环境、参数、百分位、正确性和判定。容量通过条件为所有 repetition 的 p95 小于预算且所有正确性检查通过。

报告明确标注 `Core headless physics capacity`。真实平台容量必须另做 Paper/Folia 基准，不能由本报告外推。

## Failure behavior

profiling coordinator 使用很高的内部 overload 阈值，避免测试在收集失败样本前因默认 STALLED 终止；压力测试自己按配置预算判定。生产 overload/STALLED 行为保持由现有单元测试验证。
