# 提交与收尾计划（已确认）

2026-11-06 用户回复“可以”，批准本计划：提交任务相关改动、归档和记录日志，再创建 planning 状态的新任务；不推送、不部署。

## 1. 工作提交

`fix(route): preserve exact endpoints and observed train progress`

仅以下文件及当前任务文档目录；不使用整个仓库或整个 .trellis 的批量暂存。

### 实现与测试

- Basic/src/main/java/work/art1st/javarailways/basic/route/TopologyRoutePlanner.kt
- Basic/src/test/java/work/art1st/javarailways/basic/route/TopologyRoutePlannerTest.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/controller/SimpleATO.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/routing/FrozenMissionRouteAssembler.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/routing/MissionRouteProgress.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/RetainedTimetableRoutes.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt
- SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/api/station/StationCallResolverTest.kt
- SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/routing/FrozenMissionRouteAssemblerTest.kt
- SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/routing/MissionRouteProgressTest.kt
- SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/routing/OperationalRoutePlannerTest.kt
- SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcherSharedAdmissionTest.kt
- SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcherTest.kt

### 规范与任务资料

- .trellis/spec/javarailways/basic/route-planning.md
- .trellis/spec/javarailways/simpleato/ato-policy-runtime.md
- .trellis/spec/javarailways/simpleato/dispatcher-runtime.md
- .trellis/tasks/11-06-route-position-semantics/（PRD、设计、实施、验证、诊断、上下文清单、后续规划准备与本计划）

## 2. 收尾提交

工作提交后，运行 Trellis archive，仅归档本任务；随后记录本轮 journal。脚本各自生成归档/日志提交，不混入其他任务，不 amend、不 push、不部署。

## 3. 后续任务

按 follow-up-planning.md 创建运行线模拟及停站时间语义的 planning 任务。只保存初步需求和证据，不 start、不实施；停站时间的正确语义留待下一轮讨论，不擅自补全设计。

## 明确排除的既有改动

- TODO.md
- .trellis/tasks/10-18-simulation-signal-features/research/deployment-operating-migration-research.md
- .trellis/tasks/10-18-simulation-signal-features/research/test-server-deployment-2026-10-20.md
- ai_tasks/handoff_conditional_branch_simplification.md
- ai_tasks/review-fix-contract-audit-2026-10-23.md
- ai_tasks/review-fix-contract-audit-2026-10-23.tsv
- tmp.1

## 核验

提交前独立核对：现存 XML 2647项，2646通过、1项既有实验跳过；产品与测试文件修改早于最终 XML；signal API consumer 编译产物存在；diff --check 通过。代码未在这次收尾中再改。全局原始回放仍因独立后续问题在10026延误超限，不能声明全周期通过。AC4原文要求原故障回归并披露完整结果，已满足。
