# Timetable Studio 编辑流程与异步隔离

## 已实现范围与负责模块

CURRENT。`App.tsx` 管认证、世界、页面导航和世界引用数据；`DutyEditor` 是时刻表、交路、校验三个页面共用的唯一文档、history 和 recovery owner。`DutyManagement`、`AuthoringValidation`、`TimetableWorkspace` 是受控视图。此契约覆盖交路编排、运行线工具、真实校验/模拟结果及图上时间编辑，不新增后端接口、存储格式或运行时模型。

## 一次正常编辑如何完成

用户选择世界和线路，在时刻表创建指定方向的运行线，通过 Reset 或停站表填写时刻（时间输入只在失焦或 Enter 后提交）；交路页引用这些唯一运行线并编排已有函数。所有接受的文档变更进入同一个 `useDocumentHistory`。校验页先展示 `generateTimetableSource(document).issues`，再通过现有 `api.compileSource` 获得服务器编译和世界校验结果。有效编译允许发布；每个交路完成车型映射后，现有 `api.simulateTimetable` 返回报告。页面切换只切换受控视图，不重建文档 owner。

## 保证正确性的内部机制

- 世界引用数据整体带 `world` 身份；选中世界不匹配时向页面提供稳定空引用，而非上一个世界的线路/站点。effect 清理取消旧请求的 state/error/busy 写回；线路集合更新也检查来源世界。
- 编辑器 owner 使用 owner/world 身份和递增 epoch。世界改变、文档加载/删除会使旧 owner 失效；异步加载、保存、正式脚本操作在写回前检查 epoch。加载还检查请求起始文档快照，避免覆盖请求期间的新编辑。
- 保存允许服务器成功保留请求起始快照，但不得用该快照覆盖期间的新编辑；saved baseline 记录实际保存内容，新编辑仍显示未保存。删除成功后，同一 owner 的新编辑必须保留并解除已删除文档/正式脚本 ID 的关联；不同 owner 的响应不能写入当前文档。
- 编译绑定文档生成输入；模拟绑定世界、生成输入、车型映射和阈值。输入改变后旧结果不展示；Reset 写回还检查世界/文档快照。Reset 失败不改变时刻或 history。
- 交路 ID 在失焦/Enter 时提交，空名和重复 ID 在事务前拒绝。运行线分配复用 `moveRunToDuty` 原子移动，不能同时留下两个 owner。
- 图点来自 `authoringGraphTimetable` 的两步一段投影：初始 CHECK 后，每段 options 在奇数 step、destination 在偶数 step。先用 graph service index 取真实 run ID，再映射到 call，禁止把投影 step 当作运行时 step。节点菜单保存 runId、callId、事件和当时有序 `[call.id,status]` 签名；站序或 STOP/PASS 状态改变时立即隐藏/禁用旧菜单并关闭平移，不能用过期 stepIndex 修改别的站。
- Graph 只负责选择、点击/Enter/Space 激活和画布平移缩放；不再拖动节点改时刻，也不提供方向键微调。选点回传屏幕坐标供浮动菜单定位，移动视图时关闭菜单。
- `CommittedNumberInput` 使用原始字符串本地草稿，保留逐键输入的 `-` 等中间值；用 `stringMode` 避免 `Number('-')` 的 NaN 把负号清掉。输入不写文档；失焦/Enter 解析并提交一次，最后提交值防止 Enter 加失焦重复写入。Escape 恢复已提交值；fieldKey 包含 owner/world/document/run/call/event 身份，更换身份销毁旧草稿。复制间隔/数量各自记录合法性，合并禁用条件，不能让一个合法字段清除另一个非法字段。
- `shiftAuthoringGraphSuffix` 先还原有效到达覆盖并找到实际有时刻的锚点，再计算绝对目标或偏移对应的 delta。ARRIVAL 移动本次到达、同站发车和后续调用；DEPARTURE 保持同站到达，只移动发车及后续；PASS 的两个投影点归并到一次通过事件。被移动的有效到达值写入 calls，并仅清除这些到达的旧覆盖；之前的 call 和覆盖不变。提交前校验受影响时刻与未移动的前一事件、最小停站及周期，非法时不提交，零偏移返回原文档。

## 接口、数据与单位

```ts
Graph.onSelectPoint?: (point: GraphPoint, anchor: {x:number;y:number}) => void
Graph.onDismissPoint?: () => void
editAuthoringGraphTime(document, runId, {kind, stepIndex}, tick): TimetableAuthoringDocumentV1
shiftAuthoringGraphSuffix(document, runId, point, mode: 'OFFSET'|'ABSOLUTE', value): GraphSuffixShift
// GraphSuffixShift: document, delta, affectedEvents, callId, kind, anchorTick, targetTick
// Invalid time/point throws before document update; parent displays the error.
```

时间单位是 tick，每个 timetable day 为 24000 tick。菜单提交安全整数，范围 `0 <= tick < periodDays * 24000`。后续平移允许正负偏移或绝对目标，但不把非法目标偷偷夹到另一个时刻。没有选择 callback 的图保持只读。

`AuthoringCommandState` 按已有命令记录禁用原因；空字符串代表当前可执行。ribbon 和编辑器命令处理器使用同一状态，不能只把按钮灰显而让 handler 绕过前置条件。browser HTTP 继续只经 `api.ts`；请求 shape 和 DTO 未改变。

## 生命周期与必须保持的规则

单点菜单提交到达时写 `arrivalOverrides`，发车时写对应 run call 的 `departureTick`；PASS 的离开投影也编辑通过到达时刻。有效 arrival override 参与到发和最小停站校验。节点后续平移和复制整批各形成一次文档/history 更新；本地输入草稿、预览、busy、报错和 profile 设置不进入 history。

Reset 仅重生选中运行线的时刻，始发 Tick 来自该运行线原有首站 departureTick，没有另一个始发输入；缺少始发时刻则提示先填写。独立 Copy run ribbon 打开间隔/数量弹窗，复制使用 effective run，按 D、2D、…、ND 平移并生成全新 run/call ID，不分配交路。运行选项不再混入复制设置。线路和方向共同限制选中运行线，删除后不得选中其他线路 inspector。

本地 recovery 沿用已有 owner/world 隔离和 shape；不新增 schema version、迁移链或持久 panel 偏好。

## 校验与失败处理

- 有 authoring error：展示真实 issue 和路径，阻止编译；没有 issue 也不代表世界校验已执行。
- 没有当前有效编译：禁用发布和完整模拟，并显示原因。
- 缺少车型、车型不存在或相同 selector 映射不同 profile：阻止完整模拟；输入变化立即隐藏旧报告。
- 图点不存在、step 奇偶不符、后续平移锚点缺少实际时刻、时间越界、后续事件跨越前一时刻或不足最小停站：拒绝事务，保留原文档。
- Reset API 失败、文档变化或返回时刻超周期：保留原文档；复制整批任一越界时也不提交。
- 服务器错误显示原错误详情；报告只显示返回字段和真实 trace，不生成虚构检查/轨迹。已知 UI 状态双语展示，未知运行状态保留原值。

## 成本与退化行为

键入只更新字段本地草稿；提交时才更新文档。单点更新扫描运行线/call/覆盖。后续平移对每个 call 查找有效覆盖，当前实现成本 O(calls × overrides + runs)，并线性校验事件顺序；仅保留一份预览文档，不创建逐字符 history。交路分配扫描现有元素。复制 UI 限制 1–100 份，生成成本为 copies × calls。生成/快照比较继续复用现有扫描和有界 history，不新增持久缓存。世界引用未就绪时提供空状态并禁用新建。

## 具体情形

- A 交路移动 AP102 到 B：A 引用删除、B 引用加入同一次文档事务；Undo 恢复唯一归属。
- 把已手工覆盖到达 2100 的站发车改到 2000：拒绝，不能仅检查原始 arrivalTick。
- B 世界 metadata 尚未返回又切回 A：旧 B 请求即使完成也不得覆盖 A 的线路或错误提示。
- 删除已保存文档期间修改时刻表 ID：成功删除后保留新内容，显示未保存并清除旧 documentId；下一次保存必须创建新文档。

## 必需测试与实际验证边界

纯事务测试见 `authoringGraphEditing.test.ts`，覆盖单点/后续平移、有效覆盖、PASS、非法 step/越界/事件顺序/最小停站、零偏移和 Undo。图选择/只读/去除拖动语义见 `graph.test.ts`、`Graph.test.tsx`；数值解析与受控视图见 `CommittedNumberInput.test.tsx`、`authoringViews.test.tsx`、`auth.test.ts`。浏览器另验输入未提交时图/history 不变、Enter 加失焦去重、Escape、逐键负数、节点菜单定位、后续平移前缀不变、复制字段独立合法性和 Reset 保留始发时刻，并保留世界迟到响应等既有门禁。组件/纯函数测试不证明实际服务器编译、世界模拟或发布质量。

## 迁移、非目标与反例

无存储迁移或版本变化。顶部 JS timetables/Simulation 两个独立页面已删除；正式 JS 保存/编译及校验页完整模拟仍保留。模板、积木式动作、地图拓扑编辑、完整轨迹叠加和持久模拟缓存仍需独立任务。

错误做法：每个页面创建自己的 document/recovery；把任何无错误列表渲染为“全部世界检查通过”；迟到响应无条件 `setValue(capturedDocument)`；删除成功却保留已删除的 documentId。
正确做法：受控视图共享一个 owner；结果区分未执行、当前结果与阻塞原因；响应先核对 owner 和输入，保留期间的新编辑并正确更新持久身份。
