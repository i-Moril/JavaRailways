# 实施设计

## 已确认边界

用户确认同时实现两处，并于选型结论后明确“可以，那你执行”。采用 npm 稳定版 Blockly 13.4.0、Zelos renderer，保留现有 Ant Design 壳层；无需再次确认选型。基于 cee24662，在同一隔离 worktree 实施，不部署、不推送。

## 编辑器

共享 ProgramBlockEditor 负责真实 Blockly 画布、工具箱和连接；铁路定义与 Blockly 间用可测试纯映射转换。所有已有步骤和表达式均须无损往返，保留稳定 step ID。复杂 JSON 字段可在选中积木后使用既有受校验字段编辑，不再以步骤表单作为主编辑方式。

一个固定入口下连接唯一执行链；游离积木或无效字段明确阻止保存，工作区未完成草稿按 owner/fieldScope 本地保存，不能在重挂载或受控 prop 更新时丢弃。拖拽事件组映射一次领域事务；库使用自身画布历史，到站动作接入文档历史，避免双重撤销。中英文、明暗主题、鼠标与非拖拽操作均需验证。仅布局变化不更新语义哈希。

## 到站动作

AuthoringRunCallV1 新增可选 arrivalProgram（StationProgramDefinition），默认空，现有格式版本保持1。到站编辑为无参数的内联程序，同一积木核心按上下文提供动作；库仍允许完整参数及全部已有动作。到站仅允许 SET_DISPLAY、RESET_DISPLAY、WAIT_DURATION、WAIT_UNTIL、EXTENSION、BRANCH，递归检查。REVERSE、MOVE、SELECT_STOP、STORE_AT_DEPOT、COUPLE、SPLIT 会改变当前停站持有的任务、方向或编组，仍由交路程序负责；到站上下文明确禁用并显示原因，不静默丢弃已有输入。

JS生成将 arrivalProgram 交给 destination options，由现有 ScriptWorker 编译展开为 destination 动作的 arrivalSteps。实际停站且成功开门后（无门则停车后）执行，最小停站时间同时计时；到站程序结束前不得关门/发车。继续使用同一 TimetableDispatcher 程序游标，以 ARRIVAL 子树地址保存正常 continuation，复用已有分支、等待和 extension ticket 行为，禁止启动第二个调度器。定义通过既有 timetable snapshot 捕获恢复；checkpoint 继续执行现有人工恢复策略，不声称自动恢复运行游标。

## 失败及验证

三端校验到站上下文、总步骤/深度和参数；失败保留编辑草稿，编译失败不得留下部分程序。运行期失败使用现有异常/等待处理，保留停站资源和安全约束，不提前发车。正常后续主程序不因到站子树提前完成。

关键验证：每种库步骤双向往返、嵌套分支/参数、复制ID、游离/未完成积木保护；到站动作在实际到站后执行一次、等待阻止发车、条件和扩展失败路径、最小停站及计划发车时间、定义snapshot往返、服务器拒绝非法动作、文档重开恢复。前端全测/构建、WebUI及Bukkit ScriptWorker tests、SimpleATO相关运行测试、真实浏览器编辑保存验证。
