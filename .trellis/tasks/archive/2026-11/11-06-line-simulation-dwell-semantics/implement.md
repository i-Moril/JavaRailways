# 实施计划（已批准，关门边界已简化）

当前in_progress；用户已批准最终方案及自定义程序提前关门的简化边界。先读取implement/check上下文和各owner规范；发现规范与本PRD冲突时以用户合同为准，并在实现后同步规范。

最新授权：技术验收后用户已确认单个任务提交及测试服部署，不推送远程。下方不提交/不部署描述为早期验证阶段历史。部署先核对实际基线、正常停止和恢复点，只替换指定插件包，不迁移或回填时刻表。

本轮误判修复已完成：真实S100门在目标tick完成、Dispatcher下一tick才观察的差异已由提交后只读采样区分。最终真实测量→前端Reset→单交路严格重放四站整数到达时刻全部相等，原1200tick延误阈值不变。详见research/main-verification.md和/tmp/jr-dwell-replay-20261106/README.md；无1tick容差、无fixedDwell伸长。相关模块完整门禁已通过：SimpleATO783、Bukkit725（1项既有skip），零失败，公开Java consumer编译通过；任务未自动归档，尚无提交/部署授权，其他已列验收限制仍需保留。

用户已批准修复该误判。本轮只在既有afterTick记录pending操作的真实成功tick，并由原站点解释器汇总完成时间，不改Basic/车门接口。追加刚好目标成功、实际晚1tick拒绝、WAIT/后继动作/子任务不被回溯、非零时基回归，再执行真实S100测量→前端Reset→同条件单交路严格重放。保留历史失败日志，新结果单独记录。

上下文校验提示dispatcher-runtime.md与studio-authoring.md超过单文件注入上限；实施者须手动完整读取这两份规范，不以截断的自动注入替代完整阅读。

## 必要验收补齐（用户已批准先执行验证）

本轮仅补必要回归并审计已有证据：初始真实换向后的测量与单交路重放一致；真实信号/资源阻塞后释放的等待被完整计入区间；已支持PASS/停车偏移与失败、超时、部分结果、过期请求不回填。已有完整证据不重复跑作无意义扩量。重复station及非零始发offset等既有不支持输入验证明确拒绝，不借验收新增功能；其他路线执行问题单独记录。测试若暴露需要产品设计变更的缺陷，先报告，不扩展实现。本轮不提交、部署或归档。

本轮已完成：真实normal/reverse/blocked测量与完整重放、缺profile及停车偏移失败断言，最终Factory5+请求校验2共7通过；真实DutyEditor挂载的旧响应、普通错误、504和成功一次Undo共4通过，tsc通过，diff检查通过。无新增产品逻辑修改。PRD技术验收逐项完成，下一步仅按用户授权进入提交和收尾；不以已知范围外路线故障扩展当前任务。

## 批次1：停站语义

1. 添加红色回归：STOP开关门包含在D内、晚到从真实A起算、BERTHED与程序创建差一tick、epoch偏移、首尾不自动加D。
2. 修复StationStopProgramRuntime/Dispatcher：删除开门重置，统一准备/最终时间依据。撤掉新增当前门状态查询、prepared close及提交前复核，仅保留原有准备估计和真实ticket；回归自定义程序可以提前关门但不能提前发车，不能在关门后重新添加一段D。
3. WebUI统一PASSENGER+STOP取值，修正生成、图编辑与连接校验，删除终到minimum跨任务跟踪，保留显式计划时刻。区分fixedDwell和minimum。
4. 真实浏览器生成→Graal worker→snapshot/domain回归：外层及内层stopPrograms首尾均不携带生效的全局D，默认值不恢复该等待。
5. 保留门/任务/资源安全、显式程序等待、原位置语义回归。分别测早到、晚到、开门耗时很长、关门失败/取消、无门、首尾有显式时刻、终到→折返→始发。

## 批次2：真实运行线模拟

6. 核对并按design替换HTTP排图估算路径；复用factory、实际域与真实逐车行为，连续运行同一车辆，不重建每区间状态。不新增通用simulation framework。
7. 补齐输入上下文与严格当前HTTP结果映射；采集真实到发事件，覆盖重复站点/PASS/offset/方向。保留现有独立模拟队列、超时、取消和错误显示。
8. 明确测量调度与fixedDwell的关系；只在测量交路使用既有horizon+1延误策略，原全局验收不改阈值。失败/部分采样/超过预算不得Reset写入时刻。
9. Reset按权威整数tick到发结果回填，不重复加停站；fixedDwell低于minimum或真实程序/门无法在fixedDwell内完成时明确报错，不自动伸长、不写入成功时刻。目标之后资源等待仍计区间时间。删除无独立产品consumer的DetailedTrainTravelSimulator及估算适配，不留fallback；保留共享动力学/控制器。

## 验证与交付

10. 对比同输入单运行线与单交路完整模拟：同事件整数tick一致；局部限速和真实信号/资源影响存在；初始profile、实际停站后设备状态一致。真实信号必须安装，不能仅用mock通过。
11. WebUI组件/事务和真实浏览器流程：开始、进度、取消、失败不改文档、成功Reset形成一次history、undo、首尾输入/校验边界。
12. 运行定向与相关模块完整门禁，核对XML测试执行数；离线旧102P和Reset后时刻表结果分开记录，不保证全局其他交互问题都属于此任务。
13. trellis-check独立复核；同步simulation、dispatcher、studio-authoring和涉及的门操作规范；按用户确认范围提交。不自动部署、迁移或推送。

候选命令（实施时确认资源与测试名称；不并行启动多个Gradle）：

```sh
./gradlew :SimpleATO:test --tests '*StationStopProgramRuntimeTest' --tests '*TimetableStationCallDispatcherTest' --offline --console=plain --max-workers=1
./gradlew :BukkitPlugin:test --tests '*BrowserGeneratedStationProgramTest' --tests '*IsolatedLineSimulatorTest' --tests '*IsolatedTimetableSimulationFactoryTest' --tests '*SimulationTrainOperationGatewayTest' --offline --console=plain --max-workers=1
npm --prefix WebUI/frontend test
npm --prefix WebUI/frontend run build
./gradlew :Basic:test :SimpleATO:test :SimpleSignals:test :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava --offline --console=plain --max-workers=1
git diff --check
```

旧临时replay需先检查脚本/资产仍存在，再按新输入合同调整测量而不是复用过时profile假设。缺失真实输入明确报告，不用伪fixture声称用户现场验收。
