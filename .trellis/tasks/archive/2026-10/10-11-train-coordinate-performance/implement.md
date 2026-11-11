# Execution plan

## Planning gate
- [x] 用户允许创建任务并规划。
- [x] 已读取两个指定 chat、当前源码、Core/共享规范和 schema 政策。
- [x] PRD 已按 Goal/Background/Requirements/Acceptance/Out of scope 收敛。
- [x] 用户于 2026-10-11 在具体算法解释后明确回复“可以”，批准实施。

## Ordered work
1. 固定 baseline HEAD、dirty inventory、测试配置和原始源码；读取 touched 模块剩余规范，配置真实 implement/check manifests。
2. 串行运行 baseline Core 压测：15节，100/500列，4workers，straight/curved，isolated/mixed/shared，10m/s，200预热/1000采样，3重复。每次保留报告，禁止后续覆盖。
3. 新增独立真实几何回归，先验证旧实现的R30等曲线轴距缺陷；不让旧实现错误几何门禁阻止收集其性能数据。
4. 按 Trellis implement 子代理实现车内与车间局部修正、统一摆位/恢复检查；主会话负责范围和集成。子代理不得触及无关 dirty 文件。
5. 定向跑几何、运动/velocity、道岔、换向和 persistence 回归；处理全部失败，冻结候选源码。
6. 跑相同 fixed 矩阵，必要时补 baseline/fixed 交错重复确认噪声；保留原始数据，不用历史 chat 数字替代。
7. Trellis check 子代理独立复核数值区间/路径/失败 publication/存档合同与测量公平性。
8. `./gradlew :Core:test :Basic:test --rerun-tasks`；持久化/消费者影响时跑 `:BukkitPlugin:test :TunnelBridgeBuilder:test`；按规范运行 fixtures/Dokka 检查及 `git diff --check`。
9. 同步 Core train-physics/persistence 相关规范，保存比较报告与验证限制；提交仅任务范围内文件，不推送或部署，按 finish-work 收尾。

## Reporting
逐场景报告 before/after 各重复分位数、p95差值与比值；将误差修正、吞吐/时延变化和未验证的实服边界分开。最终性能报告可以用 canvas 展示并链接可审计原始文件。

## Preflight and baseline evidence (2026-10-11)
- Original straight harness passed; raw output preserved in results/baseline-straight as PRECHECK ONLY.
- Original curved run stopped during fixture creation. Read-only jstack showed AtGradeRailCrossingIndex.detect comparing coincident independent rails; no curved result was produced. Independent rails now translate laterally by 100m per line in both arms; shared scenario unchanged.
- IDE background Gradle also wrote Core/build during edits, causing disappearing compiler classes. All comparable runs now use /tmp/train-coordinate-build.init.gradle and isolated /tmp/train-coordinate-build/<project> outputs. Build init is saved in results/benchmark-provenance.json.
- New rigid geometry regression against restored baseline sources: 4/4 fail. R30 axle shortfall 9.9953mm, S 6.2349mm, banked slope 20.5298mm; straight restored centre drift 0.722656mm. Saved baseline-red.xml and .log.
- Fixed CoreCar/CoreTrain snapshots safely retained in /tmp/train-coordinate-fixed while baseline sources run; no unrelated dirty work overwritten.

## Frozen fixed verification
- Rigid geometry4/4 green; focused128 tests (6existing skips), final turnout8/8 green; evidence results/fixed-focused/.
- Full isolated gate with --rerun-tasks: Core316/6skip, Basic385, BukkitPlugin361, TunnelBridgeBuilder31, zero failures/errors; fixtures and Core Dokka pass. All41 Gradle tasks executed;87seconds.
- Source frozen for after benchmark; source hashes and full production diff saved in fixed-provenance.json/fixed-production.diff.
- Independent review confirms exact junction equivalence assertion adjustment retains selected rail/facing/AB start checks. Extreme near-halfcircle roots excluded by explicit user request.

## Final measurement and delivery state
- Main comparable matrix complete: 72 runs, exact before/after config/environment match; all p95 <50ms. Raw ranges and p50/p99/max/worker/coordinator retained. Original reports are not pooled selectively.
- Targeted order reversal adds 12 shared500 runs. Straight median delta -19.2% confirms direction of initial -5.2%, with uncertain magnitude; curved +0.8% versus initial -8.1% establishes no stable gain. Initial curved isolated/mixed500 wall p95 +8.3%/+5.0%, not a universal speedup.
- Fixed confirmation initially could not load test classes after source switching; no samples produced. Failure log retained; isolated testClasses --rerun-tasks rebuild followed by successful repeats. Final fixed source/harness hashes match frozen quality-gate state.
- Comparison: results/comparison.md and comparison.json; targeted repeat data: results/confirmation.json. compare.py regenerates summaries from raw reports.
- Code/spec/regression/performance work complete. User approved commit-plan.md and ending this Trellis task on 2026-10-11; proceed with scoped work commit, archive and journal. No push or deployment authorized.

## Closeout
- User approved commit and task completion on 2026-10-11. Work commit: `24035887` (`fix(core): enforce rigid train connector geometry`).
- Only this task is archived; unrelated dirty/untracked work and other tasks remain untouched. No push or deployment.
