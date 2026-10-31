# 路径点输入与存车线模拟起点验证 — 2026-10-30

基线为本地5e674d12，同一worktree/既有进行中任务。本批未提交、未部署、未归档；已部署产品仍为4c19f7f4。保留既有未跟踪java_pid454886.hprof。

## 最终变更

- 使用共享EditableIdSelect/EditableIdList提供手动ID、按ID/名称筛选、canonical候选值、单次提交、Esc取消、owner切换清理、有序多值和Undo/Redo。覆盖交路取车起点、命名列车起点、运行线途经点、站内泊位、重联会合点、明确类型的程序参数/Blockly字段与函数模拟起点。具体输入清单见waypoint-input-inventory.md。
- 全局waypoint、站内berthId和库内stablingPointId保留各自身份域；泛型ID_LIST不猜语义，参数引用/未知引用/无效JSON保留原草稿并交原校验处理。
- 模拟仍严格使用现有programSimulationWayPoints候选，显示存车线所属车库/名称/canonical depot@point ID。输入任何未提交文本立即作废旧结果；未知或无明确方向的起点不能运行。
- 后端生产逻辑未改：现有depot目标注册/恢复已镜像到Basic路径点，没有depot角色过滤。新增真实保存恢复和隔离执行回归，验证六个BA存车点、点/轨道方向优先级、实际换向、未知/无方向/移除拒绝，以及源列车和库存隔离。没有新执行器、持久化字段、版本变更或兼容路径。

## 自动化检查

- 前端完整57文件/335测试通过；TypeScript和生产Vite构建通过，最终浏览器加载assets/index-CjPiqCNe.js。
- BukkitPlugin完整667测试、WebUI Java完整49测试通过，XML均为0失败/错误/跳过。
- Bukkit factory聚焦30测试通过，包含本批新增4实例；该组已包含在完整667内，不重复计总。
- 独立静态差异审查发现并闭环两项实际问题：受控tags选择后残留查询导致blur追加错误ID；重联字段缺少名称metadata传播。修复后前端完整检查和窄范围复审通过。详见waypoint-input-review.md。

## 实际浏览器输入检查

生产dist通过本地HTTP fixture提供给真实App。fixture仅模拟身份、metadata和文档，模拟请求固定422并明确表示没有执行引擎；不伪造完成/耗时，不连接线上认证接口。

- 函数模拟按名称筛选存车线，输入kasaidpt@1后提交；真实浏览器请求携带canonical ID和reverseTrain=false。手输unknown-point立即使起点无效并禁用运行；错误/结果展示随原始输入变化清理。
- 在原有缺陷bundle中复现：输入Storage→选择Storage one候选→blur，产生[kasaidpt@1, Storage]两个标签。最终bundle相同操作仅保留kasaidpt@1、查询为空；一次Undo撤回一个选择，Redo恢复。
- 手输manual-waypoint后保持[kasaidpt@1, manual-waypoint]顺序，查询清空；Esc取消未提交查询后blur不会追加值。
- 实际DutyTimeline重联字段可按EntryPoint/StorageTwo名称筛选并分别提交entry/kasaidpt@2；blur无重复，单次Undo仅撤回最后一次字段编辑。
- 390px实际输入检查通过：长标签换行、字段在容器内、间距正常，无浏览器脚本错误。既有导航布局不在本批范围。
- 证据：/tmp/jr-waypoint-input-browser/{request.json,multi-before.png,multi-after.png,mobile-inputs.png}；浏览器session和本地fixture服务已关闭。

## 验证边界

只读存档/公开轨道图确认开发服kasaidpt@1..6自身BA方向、存车角色及usableLength120；Basic持久化wayPoints为空符合目标自有镜像恢复合同。读取进程bearer token以查询认证metadata的尝试被自动审批拒绝，未读取token、未通过其他秘密提取方式重试。以上真实恢复/执行测试提供安全验证替代，但不宣称当前线上认证metadata响应已验证。

浏览器fixture证明输入和实际组件行为；后端测试证明恢复、放置和执行合同；两者均不等同于部署后的游戏验收。用户尚未授权本批提交/部署，父任务保持in_progress，completedAt=null。
