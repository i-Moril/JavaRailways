# 程序库、共享停站程序与交路的积木编辑

## 范围与数据权威

程序库与到站动作使用同一个 Blockly/Zelos 编辑组件。页面仍使用既有 Ant Design、i18n 和 theme；Blockly 只负责积木交互，不拥有服务器执行引擎。铁路语义定义仍为 `StationProgramDefinition`，库 revision、绑定快照与内容身份规则保持独立；库条目可以真实删除，已绑定快照不随之移除。

正常编辑流程是从定义建立唯一入口和有序连接积木，玩家从工具箱添加动作、连接顺序并在分支内部编辑两臂；参数插槽区分 literal 与声明引用。只在得到完整、合法的语义定义时向所属文档提交变更，经既有 validation、JS生成和服务器编译后执行。绝不使用通用 JavaScript generator 绕过铁路模型。

## 工作区与领域文档

画布布局、缩放、选中状态与未完成积木不是领域内容。有效定义的内容身份不因布局变化而改变；已有 step ID在拖动时保持，复制则生成新身份。定义/画布双向转换必须覆盖每一种已支持步骤和表达式，不能借替换编辑器删减能力。

固定入口连接唯一可执行序列。游离积木、未完成输入或不允许的上下文结构必须明确阻止保存/编译，不能只导出入口子树而丢弃其余内容。未完成工作区本地恢复按 owner/world/fieldScope 隔离，并同时核对原始服务器基线与最后接受的语义定义；语言切换重建画布时必须保留原始基线，不能将未保存内容当成新服务器基线；它是 best-effort 草稿，不能替代服务器保存的程序。受控父组件接受同一语义定义时，不得重建工作区并丢掉游离积木。

一次拖动及自动连接按一个事件组形成一次领域编辑。到站有效领域编辑使用既有文档 history；尚未提交领域的无效画布编辑先撤销本地草稿，不能让父文档 Undo 丢弃游离积木。库可使用画布历史。不得让两个独立 Undo 历史相互覆盖。卸载注销事件并 dispose；弹窗或隐藏容器显示时重新计算尺寸，禁止旧回调写入已切换世界/文档。

## 三种编辑上下文

程序库使用完整声明参数与类型匹配的参数插槽。共享停站程序、交路内联程序均为零声明参数；内联不暴露参数引用。铁路模型仍由 `StationProgramDefinition` 定义，不接受任意 JavaScript。

当前编辑文档以 `stationPrograms?: StationStopProgram[]` 按 stationId 共享定义，每站含 `arrival`、`departurePreparation` 和非负 `preparationLeadTicks`。同一时刻表经过同一站的所有运行线使用同一定义；缺省为到达开门/结束、准备关门/结束。选中 ORIGIN、STOP、TERMINAL 均可打开共享站点弹窗，两页签编辑独立定义，PASS 无触发。合法性缓存按站点而非 call 分配；关闭弹窗不清除无效工作区的保存阻塞。

停站程序允许门操作、换向、显示、等待、静态条件、扩展，以及 `ASYNC {taskId,steps}`、`AWAIT {taskId}`、`END`。每个入口主序列必须显式结束，结束后不能接可执行动作；添加动作插在已有 END 前。任务默认阻塞，ASYNC 立即启动子任务；等待指定任务时包含它的后代。换向保留真实门/物理授权检查；移动、出入库、编组和动态选站不能借停站画布执行。运行时语义由 [Dispatcher 契约](../../javarailways/simpleato/dispatcher-runtime.md) 定义。

条件工具箱仅提供可由编辑文档确定的计划值：run_type、line_direction、station_id、run_id、line_id、call_status、duty_id，使用 `javarailways:plan_*` 的 version1 literal values；ScriptWorker 在编译时选择分支。不能按实际车长、车型或运行迟到求条件。

交路积木是单入口的 RUN、FUNCTION、PROGRAM、INLINE 有序序列，保持元素 ID 与完整参数。`DutyBlockEditor` 由 `dutyBlocks` 无损映射；选中 INLINE 用同一程序组件编辑。未连接积木阻止保存与编译，重开保留原始画布，显式丢弃草稿才重置；不能仅导出入口而静默删掉游离元素。INLINE tick 可空，空表示接续前一动作，填写则为绝对计划时刻。

`WAIT_EVENT` 选择无方向事件点并填写有符号 offsetTicks，生成时规范化为 WAIT_UNTIL；删除仍被引用的事件必须显示 issue，不能让生成期间抛错导致页面空白。事件定义、工作区和合法领域定义分别由文档/history、草稿恢复、程序 owner 管理。

## 必需验证

纯映射测试覆盖所有步骤、literal/参数引用、分支深度、可选字段保持、复制身份与不完整/游离结构。组件验证中英文、明暗主题、禁用与归档、字段校验、非拖动操作、来源聚焦和 Undo。浏览器验证真实拖动/连接、嵌套分支、关闭重开草稿、保存后重新载入；纯函数或静态 HTML 测试不证明这些浏览器行为。

新字段均为可选或有明确空默认，保持既有格式版本。工作区缓存属于可丢弃本地草稿，不建立任意旧开发数据兼容链。

## HTTP deployment acceptance

The deployed WebUI is accessible over HTTP. Step IDs use `crypto.getRandomValues`, which works in this environment; do not require secure-context-only `crypto.randomUUID` during toolbox initialization. Test without `randomUUID` and verify both library and arrival editor initialization on an HTTP origin. A localhost-only browser check does not cover this deployment condition.

Each canvas must set Blockly's full locale before `inject`, including the duty canvas. The duty page can be the first Blockly page after reload; initializing messages only in ProgramBlockEditor makes the sibling duty inject fail in accessibility-label construction (`undefined.replace`) and blanks the app. Browser acceptance must open Duties directly after a fresh document load, without first visiting any station/library editor, and restore a disconnected duty draft.
