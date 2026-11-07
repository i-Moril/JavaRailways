# 首批执行计划（已获批准）

## 追加执行 F6–F8（已批准）

- [x] C1：SimpleATO交接/物理授权定位修复及生产controller回归；49项聚焦通过，临时仅恢复旧假终点分支时5项新回归失败，移除后49项再次通过。
- [x] C2：SimpleSignals精确occurrence端点复用及fixed-block/region回归；40项聚焦通过（含5项新增），完整联合检查待C3。
- [x] C3：串行运行完整相关模块检查（3320通过、1原有跳过）、消费者编译、正常全局回放；原2002/14219阻塞消失，后续18402车门作业失败单列，未声称完整模拟通过。
- [x] C4：独立trellis-check复核无剩余阻断项，主会话同步owner规范；报告见research/authority-boundary-check.md，最终联合结果见research/authority-handoff-validation.md；不提交部署。

实现说明见 research/authority-handoff-repair.md；C1/C2文件互不重叠，可并行实现但不得同时运行Gradle。

## 追加执行 F9（已批准）

- [x] C5：在现有TimetableValidator有限传播中加入最小车门状态证明；真实18402模式静态失败，显式关门修正后通过；不改运行期执行语义。
- [x] C6：独立trellis-check复核终到/始发/NONE/有限后继传播语义，运行validator聚焦测试及完整SimpleATO测试；同步validation规范，不提交部署。

实现证据见research/door-state-static-validation.md。优先复用TimetableValidator当前State和分支合流，不引入跨世界缓存、WebUI副本或自动关门。

## 进入实施前

- [x] 用户在最新方案摘要后明确批准。
- [x] 重新确认 HEAD/dirty；保留 TODO.md 和既有无关文件。
- [x] 两个 jsonl 清单有效，PRD/设计范围无实质变化，才运行 task.py start。
- [x] 按 auto 模式派发 trellis-implement；prompt 首行注明 Active task，按 manifest 注入或让子代理回读。主会话负责整合、规范和最终验证，不并行修改同一文件。

## A：统一物理帧（F1–F3）

负责范围：Core TrainController/PhysicsCoordinator 与快照类型、Basic PerceptionSnapshot、SimpleATO AbstractATO/controller/Dispatcher/inspection/诊断、直接相关消费者与测试。跨模块机械迁移由同一 implement owner 完成，避免半迁移。

1. 添加真实 PhysicsCoordinator 的新 frame hook 回归，以及显式 controller.commit 回归；覆盖位置变化与速度/body 同帧。
2. 改发布顺序和门禁，验证 close/stall/barrier，保持 callback 锁外。
3. 全仓迁移 TrainSnapshot 引用，删除类型；保留控制/感知计数与历史诊断采样语义。
4. Dispatcher 一次捕获实际帧；人工状态同步当前物理位置；inspection 去掉旧/新坐标筛选和 null→全路线。
5. 回归完整 Dispatcher/inspection/Basic 路径、缺帧拒绝、真实车体/BA/重复轨道、历史诊断不变；更正认可全路线兜底的测试。
6. 主会话使用 trellis-update-spec 更新 Core publication、Basic perception、SimpleATO controller/entry-clearance 等对应合同，避免按旧错误规范“修回去”。

## B：整车原生限速（F4；依赖 A）

A 的物理采样接口稳定且聚焦测试通过后再派发 B。负责范围：SimpleATO 自动/手动控制帧合成及必要共享小函数、对应测试；不可修改 Basic 仲裁或导入其他审查项。

1. 将 research/AutomaticBodySpeedAuditProbe.kt 的反例转为正式行为测试，优先通过真实生产入口观察结果；避免以反射作为唯一长期验收。
2. 用同一已捕获 body frame 提供自动/手动当前原生限速，reference 与 ATP protection 一致。
3. 验证短轨位于车厢/转向架之间、尾部清除才释放、AB/BA、前方限速提前响应；加真实 Basic 设备控制回归。
4. 主会话同步限速合同，不扩展成新限速系统。

## 验证与交接

聚焦执行后再跑完整相关模块；按机器内存顺序运行。现有 SimpleATO 默认测试堆曾 OOM，复用审查记录中的临时3GB Test heap配置，不把 -Pkotlin.daemon.jvmargs 当成测试 JVM 配置。不要为本次验证改全仓构建默认值。

候选命令（实际 test class 用 rg 确认，不能将 NO TESTS 当通过）：

```bash
./gradlew :Core:test --tests '*PhysicsCoordinatorTest' --tests '*CoreTrainPublicationBoundaryTest' --tests '*ImmutableSnapshotTest' --offline --max-workers=1
./gradlew :Basic:test :SimpleATO:test :SimpleSignals:test --offline --max-workers=1 -I /tmp/jr-simpleato-audit-az6Vw0/audit.init.gradle
./gradlew :BukkitPlugin:compileKotlin :BukkitPlugin:compileJava :BukkitPlugin:compileTestKotlin verifySignalModuleBoundaries --offline --max-workers=1
```

- 定位仓库实际 Java API consumer fixture/task 并构建；不虚构不存在的任务名。
- 搜索全仓 TrainSnapshot 生产/测试引用，历史任务/审查文档保留；检查不存在替代镜像、copy(speed) 或全路线空值兜底。
- 复用 `/tmp/jr-delay-14219-diagnostic/replay.gradle` 与 src，以及原真实输入；只在临时目录调整观察探针。先检查脚本和文件仍存在，不运行不明脚本、不写远程服务器。记录旧帧/新帧、车长、请求边界、blocker、最终延误，区分原因消除与完整模拟结果。额外故障单列，不隐式扩大范围。
- 派发 trellis-check，要求沿生产发布/手动接管/准入/限速链核对；允许修复本批范围内缺漏，不得顺手修审查后续项。最终主会话复核差异与 git diff --check。
- 两个子批均完成、集成验证及规范完成才报告首批完成；本任务其余审查项仍是后续工作，不宣称全部修复。
- 不自动提交、推送、部署或归档；向用户汇报成果和仍待处理项。

## 失败处理与回退边界

若 A 改 hook 顺序破坏关停门禁或需要新对外持久化合同，停在对应问题返回规划，不追加兼容分支。B 不通过则保留 A 的验证结果，不扩大修改为 ATP 重写。源码回退只处理本任务拥有的变更，禁止恢复整个脏工作区。无数据迁移，故无存档回滚步骤。

## 当前交接

F1–F8限定修复及独立check完成，规范同步；最终联合3320项通过、1项原有opt-in跳过。
原始正常回放越过Tick2002和14219；后续在18402因车门作业检查失败，未扩大到该流程修复，详见research/authority-handoff-validation.md。
F1–F8 已提交为 `31e4cf17` 并部署至 JavaRailwaysTest；精确候选、恢复点与健康检查见 `research/f1-f8-deployment-2026-11-07.md`。任务不自动归档，不宣称完整时刻表通过；测试服 Tick14219 仍需用户重新运行 WebUI 全局模拟验收。后续审查项另行确认范围。
