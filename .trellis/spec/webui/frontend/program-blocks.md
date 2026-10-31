# 程序库与共享停站程序的积木编辑

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

“读取当前列车信息”是 JSON_OBJECT 数据源 `{trainInfo:{outputKey:field}}`，field 为 NAME／CAR_COUNT／UNIT_COUNT／DIRECTION，仅可连接 SET_DISPLAY.values 或 EXTENSION.parameters。键名1至64项、非空白且最长256 UTF-16 单元，保留原文；编辑器重复键、空键和不完整映射必须保留为无效工作区并阻止保存，不能转换对象后静默覆盖。与 literal/parameterId 互斥，普通 JSON 对象内容不触发数据读取。键/属性侧栏复用画布历史和草稿；`__proto__` 等键通过 own-property 安全构造保留。四项数据分别是实际列车名称、车厢数、完整登记单元数和当前编组正向／反向（FORWARD／REVERSED），方向不是上/下行或地理方向。运行时在动作开始时求值，不在编译或参考车辆上提前取值；不开放计划条件、移动、选站或等待的动态参数。

交路由 DutyTimeline 时间轴编辑RUN、交路所属PROGRAM和运行图所属EVENT引用；不再提供交路积木或内联程序画布。程序体仍使用本规范的程序库编辑器，交路时间与耗时规则见 studio-authoring.md。

重联／解编仅由交路 FORMATION 任务安排。玩家程序定义、参数类型和 Blockly 工具箱不包含 COUPLE、SPLIT 或 FORMATION_EVENT；当前普通未知步骤／类型校验负责处理不支持的输入，不保留旧程序动作兼容分支。内部时刻表执行器与直接 split/couple DSL 仍由交路生成使用，不因此删除物理执行能力。接收交路由玩家手动创建，输出和来源互反关系由交路文档事务维护，详见 studio-authoring.md。

`WAIT_EVENT` 选择运行图所属的无方向定时作业/纯时间锚点并填写有符号offsetTicks，生成时规范化为 WAIT_UNTIL；删除仍被引用的事件必须显示 issue，不能让生成期间抛错导致页面空白。事件定义、工作区和合法领域定义分别由文档/history、草稿恢复、程序 owner 管理。

## 程序库删除与交路资源同步

CURRENT。程序库文件由 `VisualProgramRepository` 直接删除；交路文档的 `boundPrograms` 是显式绑定的固定快照，既不是库文件，也不是归档记录。已有调用和本地恢复文档保留其快照，删除共享库来源不暗中改写文档、生成代码或撤销历史。

`StationProgramLibrary.onLibraryChange` 在新建、保存、重命名和删除请求成功后通知 App。通知必须在库页面卸载后仍能送达：用户可以在删除请求完成前切到交路页。组件自身列表与选中状态仍只接受当前 owner/world 的响应；失败请求不发布成功通知。App 增加临时 `programLibraryRevision`，它是资源失效信号，不是存档版本或程序 revision。

保留挂载的 DutyEditor 使用 `useStationProgramLibrary(world,page,revision)` 读取服务器列表。世界、页面或失效信号改变时清空旧缓存并重新查询，递增请求编号；旧编号及已卸载请求不得更新列表、错误或 loading。查询失败保留错误且不把失效列表恢复为可用来源。每次失效一次列表查询，存储空间与返回库条目数成正比，不触及文档持久化。

新增交路动作和运行图事件只能选择当前库条目。来源已删除的快照仅作为现有调用的当前值展示，标注“仅绑定快照”，禁用为新选择，并隐藏无效的编辑来源入口。例如删除程序后仍保留现有折返动作，但新增折返不能再从库中选到它。不得将所有绑定快照合并进新增程序选项来伪装成库仍存在。

回归覆盖四种成功写入、失败删除、删除完成前离开页面、迟到列表响应、世界隔离、失效后查询失败，以及恢复快照与新增选择的区别。此修复不改变存档、接口或快照执行语义，无需迁移。

## 函数耗时模拟

程序库和时间轴调用复用 `ProgramSimulationDialog` 与 `programSimulation.ts`。库发送当前有效且已接受的画布定义，原始无效草稿阻止模拟；时间轴发送 exact bound snapshot/arguments，来源删除不改变快照。选择真实参考车辆、metadata 中已声明方向的起点，并显式选择保持方向或放置后原地反转，不自动选择反转值。

表单为临时状态，按 owner/world/source 隔离；任意输入变化使结果失效，请求 epoch 阻止 A→B→A、关闭、卸载、世界变化后的迟到结果。WAIT_EVENT 只从当前文档解析，无文档的库不伪造事件。模拟推进上限包括取车与准备换向，函数耗时取真实任务边界；未完成/失败不能生成耗时或假造全时刻表模拟报告。

时间轴只有用户明确采纳成功结果时才经现有 history 写入固定计划耗时；这不令执行器强制等待。库结果不写文档。界面同时提供中英文、错误和loading，真实浏览器检查必填项、两种布尔请求、错误不冒充成功及窄屏滚动；不把HTTP夹具称为物理模拟验收。

## 必需验证

纯映射测试覆盖所有步骤、literal/参数引用、分支深度、可选字段保持、复制身份与不完整/游离结构。组件验证中英文、明暗主题、禁用与删除、字段校验、非拖动操作、来源聚焦和 Undo。浏览器验证真实拖动/连接、嵌套分支、关闭重开草稿、保存后重新载入；纯函数或静态 HTML 测试不证明这些浏览器行为。

新字段均为可选或有明确空默认，保持既有格式版本。工作区缓存属于可丢弃本地草稿，不建立任意旧开发数据兼容链。

## HTTP deployment acceptance

The deployed WebUI is accessible over HTTP. Step IDs use `crypto.getRandomValues`, which works in this environment; do not require secure-context-only `crypto.randomUUID` during toolbox initialization. Test without `randomUUID` and verify both library and arrival editor initialization on an HTTP origin. A localhost-only browser check does not cover this deployment condition.

Each canvas must set Blockly's full locale before `inject`, including the duty canvas. The duty page can be the first Blockly page after reload; initializing messages only in ProgramBlockEditor makes the sibling duty inject fail in accessibility-label construction (`undefined.replace`) and blanks the app. Browser acceptance must open Duties directly after a fresh document load, without first visiting any station/library editor, and restore a disconnected duty draft.

## 编组单元输入

UNIT_SLOTS 常量在积木侧栏和调用参数中复用 UnitSlotsInput，协议仍为 number[]／Blockly VALUE JSON。以当前实际运行车头起从1编号的完整编组单元选择，单元可能包含多节车厢，换向后重新按运行车头编号；不得暗示编号是车厢号或伪造实际编组清单。接受1至Int32上限的互异整数，非空且最多128项。候选编号仅在添加或回车后提交一次既有历史，切换字段/积木/世界丢弃候选；重复和非法候选禁用添加。无效积木原始JSON必须保留、可修复且继续阻止保存，参数引用不转换成常量。

Blockly焦点转到侧栏控件产生的空选择，不应卸载正在编辑的侧栏；新积木选择和权威外部加载仍更新选择。多控件参数行使用div与显式文字，不用单个label包裹多个输入。输入框回车/Esc不冒泡至弹窗。


## 数字与字符串表达式

程序值支持递归纯运算和逐项对象构造；类型、格式、求值与预算遵守[表达式合同](../program-expressions.md)。浏览器、Java保存校验和Kotlin编译必须保持一致，原trainInfo动态绑定范围不扩张。
