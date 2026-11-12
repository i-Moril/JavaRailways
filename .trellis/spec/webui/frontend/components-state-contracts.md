# 组件、状态与跨层 contract

## Component 与 state ownership

- `App.tsx` 负责 session/world/navigation 和 page composition；时刻表、交路、校验由同一个挂载的 `DutyEditor` 管 document/history/recovery，受控页面不建立独立文档 owner。世界引用、异步写回及图点事务机制见 [Studio 编辑契约](./studio-authoring.md)。
- 领域变换放纯函数：`authoring.ts` 管 document validation/generation，`schedule.ts` 管 service row projection，`graph.ts`/`railMap.ts` 管 view model。不要在 event handler 内复制同一规则。
- 简单 editor 使用 reducer；document undo/redo 使用 `useDocumentHistory`，每次 accepted update 清空 future，history 默认有界 100。不要将 transient loading/error 放入可撤销领域 document。
- async simulation/compile 返回前确认 document/run/revision 未变化；过期结果不应用到新 state。

## API 跨端 contract

所有 browser request 走 `api.ts` 的 typed `request<T>`：相对同源 `/api/v1`、JSON content、204 → `undefined`、non-2xx 抛 error、401 dispatch unauthorized event。新增 endpoint 同时更新：

1. `types.ts` wire shape；
2. `api.ts` method/path/payload；
3. Java Javalin route/handler；
4. 两侧 contract tests。

不要从 component 直接 fetch privileged API，也不要把 Java enum/token翻译后发回 server。`UP`、`DOWN`、`WORLD_TRAIN`、action `type` 等保持固定值。

登录exchange也走同一request owner。non-2xx由`apiErrors.ts`解析`error/code/requestId`为`ApiError`，按当前`javarailways.locale`显示zh-CN/en-US摘要与日志编号，不能将response.text()的JSON/HTML直接显示。网络失败、错误正文读取中断及成功响应无效JSON同样转换为明确错误类型。未知code使用HTTP摘要并保留安全的字符串诊断，不从502猜桥接或磁盘原因。领域原始诊断可作为标明的详细原因保留；尚未全面翻译领域文本。

`ProgramSimulationDialog`保留ApiError对象并在render时按context locale翻译，已有错误随切换语言更新；其他既有组件在接收错误时取当前语言的message。HTTP200但FAILED/LIMIT_REACHED的模拟按reasonCode翻译摘要、保留reason和failedProgramStepId，不能当成功耗时。

模拟执行超时由界面当前调用选择，秒为展示单位、请求必填整数executionTimeoutMillis（1..3600000）。共享SimulationTimeoutInput默认60秒，明确区别于模拟tick推进上限。函数dialog持本次值；完整时刻表/线路重算按钮先打开SimulationSetupDialog，超时及valid草稿仅由弹窗拥有，确认才提交预算并开始编译/模拟。页顶无常驻超时控件，取消或owner变化关闭不清已完成结果、不遗留invalid禁用；不写文档或恢复存储。确认预算显式传入run方法，并据此构造请求结果身份；generation/docRevision/ownerEpoch/scenario共同隔离迟到响应，不能setState后从旧闭包读预算。后端及bridge HTTP等待读取同一请求预算，不新增simulation配置文件项。

函数模拟无开始tick输入；程序库内部从0开始，调用端有计划时刻则隐式传入。程序库属于当前时刻表，estimatedDurationTicks 是普通可编辑字段，手填或从成功模拟采用后随程序保存；采用值与原提交值相同也必须重置无效数字草稿，仅重建预计耗时输入，不重建程序画布或丢失未保存code。

## Authoring 与 validation

`TimetableAuthoringDocumentV1.formatVersion` 是独立 editor-document boundary。`validateAuthoringDocument` 负责结构化文档的 ID、period、line/call 顺序、tick/dwell、duty assignment、location/time continuity；`generateTimetableSource` deterministic 生成只读 JS。规则：

- document 是 authoring source of truth；generated JS/graph timetable 是 projection。
- 删除 run 要同步 duty assignment 与 arrival override；移动 run 使用单一 pure transaction，不能先加后删产生双 owner。
- presentation-only `diagramLineId` 不替代 infrastructure `lineId`。
- publish 前仍调用 server compile/validate；frontend success 不代表世界引用/route 有效。

参考 `authoring.test.ts`、`editor.test.ts`、`graph.test.ts`、`lines.test.tsx`。

## 完整路径点输入与局部后缀

直接选择完整路径点ID的输入复用 `EditableIdSelect` / `EditableIdList`，候选由 `idOptions.ts` 及调用者的实际 metadata 提供。候选显示与提交均为实际完整 ID，按 ID 忽略大小写筛选。单值ID保持可编辑，候选选择、Enter或blur只提交一次；Esc取消未提交文本，fieldKey变化丢弃旧字段文本/回调。有序多值使用tags，允许手输，保持输入顺序。作者草稿可以保留未知/已删除ID，不能自动改成第一个候选或清空；原内容/编译校验仍负责引用合法性。

站内停车点填写/选择的是局部后缀（berthId），不是完整路径点名称；运行线停车点维持原来的无边框Select，只显示当前站的后缀，由原逻辑解析destinationId。不得因“所有路径点可手输”的要求扩大修改到这个控件。程序SELECT_STOP.berthIds和STORE_AT_DEPOT.stablingPointIds也保留原局部ID/原始列表输入，不套用全局路径点控件；普通station/depot聚合ID和泛型ID_LIST不猜成路径点。Blockly仅对明确连接MOVE.viaIds的字面量列表提供完整路径点建议。参数引用和无效原始JSON仍可保留修复。重联会合点使用原destinations候选范围内的实际 ID。

函数模拟起点候选仅来自 `programSimulationWayPoints`，包括符合既有方向规则的depot存车线；候选直接显示实际路径点 ID。手输未知/无方向点仍不可执行。任何raw query编辑立即作废旧模拟结果/采纳资格，不等blur，Esc恢复输入也不能复活旧测量。展示候选不是完整车体可放置证明。

受控 `Select mode="tags"` 的searchValue须在接受onChange/选择后显式清空。rc-select内部选择时不保证调用外部onSearch；否则blur会把残留查询当成额外ID提交。测试不能用mock主动onSearch('')替组件掩盖问题；需真实浏览器或忠实选择→blur事件顺序验证无额外值/历史，连同单步Undo/Redo、Esc、owner替换和窄屏标签换行检查。

## Recovery、i18n 与 theme

`authoringRecovery.ts` 的 key 包含 encoded owner/world，只恢复 version 1 且 owner/world 相同的对象。storage parse/write/remove 均 best-effort，异常不能中断编辑。breaking shape 默认丢弃旧 recovery；任务未要求时不加入永久 reader。测试 corrupt/old/cross-world/cross-owner。

`i18n.tsx` 以 `zh` key set 约束英文 `Record<TranslationKey, string>`，新增 key 必须双语齐全；locale 仅 `zh-CN`/`en-US`，浏览器偏好经 `normalizeLocale`。`theme.tsx` 只允许 `dark`/`light`，同步 `data-theme`、`colorScheme` 与 storage。访问 `window`/`localStorage` 的代码保留 SSR/test guard。

## 测试方式

Vitest environment 是 `node`。优先测试 pure transformation；无浏览器 DOM 依赖的 component 使用 `renderToStaticMarkup` 验证公开文案/控件 contract。涉及 fetch/localStorage 时使用最小 mock，并断言 method/path/body/错误，而不是 snapshot 整页 HTML。

## 日志与当前内容状态

`LogsPage`是独立的已认证服务器级页面，使用api.logSources/api.logs读取真实磁盘查询；前端demo明确说明此能力不可用，不伪造成功日志。App先发布已验证session，再查询worlds；worlds失败不得撤销有效session或隐藏日志入口。世界下拉在日志页禁用，避免暗示日志限定于选中世界。

页面展示来源、关键词查询、刷新、前后游标分页及原始记录详情。`warning`表示来源列表不完整而不是没有错误；`reset`提示刷新；`partial`提示当前片段不完整。关键词只扫描单次有界窗口，空页有nextCursor时仍可继续向前查找。来源切换/查询/刷新立即隐藏旧结果，迟到响应不得覆盖新请求。日志内容按纯文本渲染，禁止HTML插入。堆栈分组由reader负责，UI不自行拆解或恢复世界状态。

`IssuesPage`继续展示当前内容的配置/可用性故障，恢复后移除；不保存计算异常历史，不承担日志搜索。此轮不改现有操作错误提示文案。

## Station JSON metadata editor（2026-11-10）

车站 metadata 入口位于 LinesPage 上下行车站列表的按钮，不设独立导航 tab。StationMetadataModal 通过现有 api.stations 读取完整站点资料；按 world 隔离 owner、按已访问 station 保持 editor 挂载，关闭、重开和切换车站均保留各自草稿与基线。保存成功回调更新 App 的同站全部 berth 名称，使两个方向列表及其他 consumer 同步。根必须 object，未知自定义 key 保留；格式化只操作当前草稿。server baseline 与编辑 draft 分离，PUT 携带 expectedMetadata 做按站点冲突检测。解析错误/保存失败/冲突均不清空 draft。world/station identity 改变必须隔离 late response，不能以旧站点资料对新 world 发写请求；刷新列表不能悄悄将旧 draft 重基到新 baseline 后覆盖他人数据。不新增 localStorage 边界。API/types 集中、错误/操作文案双语。

## 线路/运行线 JSON metadata（2026-11-10）

共享metadata.ts parser与MetadataJsonEditor；stationMetadata保留解析入口但委派中立实现。线路以metadataDraft控制原始JSON，非法draft禁用autosave/flush，现有Line revision保护不变；metadata保留在LineV1 wire。运行线raw draft由DutyEditor按run identity持有，切换页面/运行线保留；同步draft-validity ref拦截save/compile/publish/simulation，不能先让旧有效model保存再报告非法。文档切换清理，Undo后显示文档值。复制/resize/reset/模拟等spread paths保留metadata，generated segment必须明确携带run.metadata??{}。Java editor validator同时校验对象/深度/节点/字节，未知metadata内部字段不丢失。

线路标题按钮打开 metadata 弹窗，metadataDraft 仍由 LineEditor 持有，关闭弹窗不丢失有效或无效草稿；无效草稿在主页面显示错误并可重开修复。MetadataJsonEditor 按 station/line/run scope 展示当前 cabin-route XML 消费的约定说明与字段控件：车站 name/transfer/transfers，线路 name/badge/themeColor，运行线 line/class。控件与原始 JSON 共用 draft，不建立第二份元数据；只修改选中字段或语言，保留自定义字段及额外语言。形状不适配的已有字段仍保留在 JSON 中供直接编辑，不强制转换或删除；删除字段是显式操作。换乘线路复用 EditableIdList，提交实际线路 ID，保留手输及未知 ID，选择后失焦不得添加残留查询。颜色是显示约定，不收紧通用 JSON 存储规则。说明按钮在 JSON 无效时仍可使用。
