# 统一定时作业 — 用户确认后的实施合同

用户先提出4条建议，root给出计划摘要：统一定时作业（开始时刻、程序调用、可选车站、点/手动/模拟耗时），交路所属仅交路可编辑、运行图只读投影；运行图所属仅运行图编辑、交路引用；运行图定义不自行执行，多个交路引用各自列车执行；耗时仅规划显示、车站不自动移动；程序体仍归库。用户明确“可以，那你实现”。继续既有in_progress任务，不归档，无需另建任务。

## 当前边界与模型

当前未发布 editor-document formatVersion1直接更新，无升版、无runtime兼容读链。旧开发服数据通过一次性dry-run/hash备份离线迁移。作者文档仍唯一history/recovery owner。

共用 `TimedOperation`：`{id:string,tick:number,stationId?:string,label?:string,duration?:{type:'MANUAL',ticks:number}|{type:'SIMULATED'},program?:{programId:string,arguments:Record<string,StationProgramLiteral>}}`。

- document.events 为运行图所属TimedOperation[]；无program时保留已有纯时间标记/WAIT_EVENT锚点，界面明确“仅时间标记”。新增界面可以绑定程序库调用。只有含program的运行图作业可加入交路执行。
- Duty PROGRAM元素为 `{type:'PROGRAM',...TimedOperation}`，program必需；不用额外嵌套call或重复tick/id。交路所属保留元素id作为执行taskId；移除旧call.id仅在离线迁移发生。
- Duty EVENT元素 `{id,type:'EVENT',eventId}` 引用运行图作业，不复制其时刻/程序/参数/站点/耗时。引用元素id作为每次执行的taskId。同一事件可被多个交路引用，各自在列车上下文执行；未引用的运行图作业不执行。缺失或纯标记引用产生明确issue，不silent跳过或fabricated执行。
- 现有WAIT_EVENT依旧引用document.events的tick；不指向交路所属投影（避免身份歧义和扩大语义）。

## 共享逻辑与呈现

共享resolver根据document和duty元素返回真实作业/owner、通用参数表单、时间/耗时校验、timeline/projector及模拟耗时解析。UI不得两套独立业务规则或复制语义配置。共用字段编辑组件负责tick/optionalstation/label/program/args/duration；外围拥有采用库快照、保存和history，不在组件直接请求。

- 图上创建/编辑运行图作业；显示为站点上的点/区间（未绑定站点的作业可在列表编辑，不伪造车站）。交路创建的带站点作业只读投影，点击/菜单仅“前往交路”；不能改/删它的所有者内容。
- 交路可创建自己的程序作业并选车站；可选择引用已含program的运行图作业。引用条目只读字段，提供前往运行图编辑；移除仅删引用。交路自己的条目沿用时间轴拖拽/手动耗时调整，图源引用不得拖动改时间。
- 运行图作业模拟耗时按当前完整成功simulation中实际引用执行taskId计算。单次/多个引用测得时长相同才提供唯一模拟时长；未引用、缺失、不完整或各次不同显示未知，不取第一个或伪造0。交路投影始终使用自己的执行报告。
- 模拟/编译hash仅含执行语义：时刻/调用/引用解析影响执行；station/label/manualduration仅显示，不无故失效有效模拟。引用删除缺失清晰issue，绑定快照和源码导航仍可定位到具体交路/元素/程序步骤。
- 图/交路/列表统一中英文、主题、间距、键盘与右键操作；所属来源可辨识。切换文档/世界/线路/页面清瞬时草稿，避免迟到响应或旧菜单覆盖。

## 验证与分工

core代理：types/authoring/dutyTimeline/timetableEvents/stationPlans/programNavigation/必要其他纯helpers与其tests。
UI代理：DutyEditor/DutyTimeline/DutyManagement/Graph/TimetableEventsEditor/共享字段组件、i18n/CSS及组件tests；不得改core模型文件。
backend代理：Java EditorDocumentValidator/测试、一次性离线迁移脚本与测试；不得改前端。
root：文档契约、协调集成、全量测试/浏览器/打包/本地提交/安全部署和迁移。互相不覆盖文件，任何越界先告知root。

必要验证：两种归属、无station/点/手动/模拟/多引用不同耗时；未引用不生成执行；缺失引用报错；共用程序参数与旧WAIT_EVENT；源码导航/hash/late响应/history/save/restart。真实浏览器图建函数→交路引用→只读→移除不删；交路建函数→图只读→跳转编辑；保存重启。迁移dry-run与hash前置、备份、重新校验，当前writer只写当前模型。最终完整npm tests/build、WebUI Java tests，必要相关运行生成backendtests；正常停服保存备份再迁移/替换，线上资源与健康核验。游戏客户端验收另行由用户完成，任务保持未归档。

## 实现与验收记录

共用TimedOperationFields、timedOperations resolver/validator/projector；PROGRAM与EVENT分别拥有/引用统一数据。Java共享字段校验，未增加runtime旧字段转换。正常纯标记保留WAIT_EVENT用途。

- 完整前端42文件250项通过，TypeScript与生产构建通过；WebUI Java40项通过。聚焦迁移9项通过（另完整迁移测试既有4项集成skip）。
- 独立只读复审追踪生成、身份、排序、编组、导航、恢复、Java和离线迁移；发现交路图投影右键直跳已改为单操作菜单。root复查修正换程序清空无效tick状态和列表暴露内部元素ID两处。
- 实际打包WebUI本地浏览器：图建tick800、手动100、参数units[1]的作业；交路EVENT引用显示禁用的共享字段，移除仅删引用、Undo恢复；跳转图上可编辑。
- 交路建tick1000、B站、手动50作业，在图上有只读标识，ShiftF10菜单仅“前往所属交路”，选择后正确聚焦并编辑。服务器保存EVENT纯引用及扁平PROGRAM、图上公共模型均正确。
- 输入无效tick后切换程序仍禁用保存；取消选择未绑定程序3不采用快照；清除station使作业仅留列表、保存成功。无模拟报告的SIMULATED显示未知。浏览器error/unhandledrejection为空。
- 英文浅色和中文暗色布局复查。最终包/重启/开发服迁移与部署证据在后续补录。任务不归档。
