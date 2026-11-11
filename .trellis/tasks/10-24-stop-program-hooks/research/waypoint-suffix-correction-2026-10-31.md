# 用户纠正：路径点筛选不扩大到停车站后缀 — 2026-10-31

用户指出：停车站选择输入的是站内后缀，不是完整路径点名称，不需要随“所有可以选路径点的地方允许手输/筛选”一起改。前批的局部后缀控件扩展是主会话误解并扩大范围，不能把“coordinator approved”视为用户确认。

## 本批最小边界

- RunRouteEditor停车点列恢复5e674d12原来的无边框Select，只显示当前站berthId后缀，沿原changeBerth解析对应destinationId。运行线viaIds仍是完整路径点列表，保留新手输/筛选控件。
- Blockly SELECT_STOP.berthIds、STORE_AT_DEPOT.stablingPointIds属于同类局部后缀，撤回前批额外套用的新候选控件，恢复原ID_LIST文本输入。MOVE.viaIds和DESTINATION仍保留完整路径点手输/筛选。
- 删除仅为误扩范围引入且恢复后无调用的berthOptions/stablingPointOptions，调整其既有测试；不修改全局waypoint候选来源/名称、模拟起点、重联会合点或交路起点。
- 不改数据、模型、序列化、schema、解析器、后端或方向规则；无需迁移和JVM全套重测。既有已部署版本仍976f4eac，任务继续in_progress。

## 所有权和验证

实现worker仅修改上述前端组件/helper及相关现有测试。主会话负责spec/PRD/实施记录、一次完整前端测试+TypeScript/Vite build，以及对恢复控件和保留控件的窄范围复核。需要时做真实组件浏览器输入检查；不重复后端3029门禁。

当前纠正请求授权修正实现；不自行提交/部署/归档。完成后报告实际状态，保留无关heap dump。

## 最终验证与状态

仅5个产品/测试文件变化：RunRouteEditor.tsx、ProgramBlockEditor.tsx、idOptions.ts、RunRouteEditor.input.test.tsx、idOptions.test.ts。前者停车点列与5e674d12原无边框Select一致，只传入当前站后缀候选；原changeBerth解析未改。两类程序局部后缀列表恢复原raw Input；MOVE.viaIds、DESTINATION、参数/非法JSON回退保留。两个仅为局部扩展引入的helper已无调用并移除。

实现方聚焦4文件12测试通过；主会话唯一完整前端门禁57文件335测试通过，TypeScript/Vite生产构建成功（index-Lr1DZJIQ.js），git diff --check和任务上下文校验通过。独立窄范围静态复核无阻断，见waypoint-suffix-review-2026-10-31.md。没有后端/模型/API/持久化变更，不重复3029项JVM门禁。

真实浏览器使用本地显式UI fixture（独立jr-suffix-correction会话、127.0.0.1:18925）。通过实际生产dist和HTTP文档/metadata/线路列表加载真实App，身份标明Local UI fixture；没有线上会话/执行引擎，写入/执行请求仍明确拒绝。导航提示仅用于测试setup，文档仍由真实HTTP加载。

- 停车点候选显示3、alternate等本站后缀，不显示station@...或车站名称组合；用实际键盘选择alternate，真实App recovery文档记录berthId=alternate、targetId=kasaipark@alternate，其他站不变。
- 一次Undo恢复原berthId=3/targetId=kasaipark@3。
- 实际站点详情的via字段输入Storage可筛选到Storage one · kasaidpt@1，鼠标选择后追加手输manual@check，Escape关闭候选并真实失焦；文档最终viaIds严格为[kasaidpt@1, manual@check]，后缀/其他站点不变，无查询名称误写。
- 浏览器脚本错误为空；截图已查看，停车点保持原表格布局，证据/tmp/jr-waypoint-suffix-browser/{suffix-select.png,full-waypoint-via.png}。浏览器和本地fixture服务均已关闭。

浏览器核验仅覆盖运行线停车点及完整via，未声称实际Blockly侧栏交互/后端物理执行验收；恢复其原raw分支由差异复核证明。本批未提交/部署/归档，当前在线仍为976f4eac；任务保持in_progress，用户游戏验收继续保留。
