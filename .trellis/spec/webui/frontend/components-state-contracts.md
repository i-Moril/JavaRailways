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

## Authoring 与 validation

`TimetableAuthoringDocumentV1.formatVersion` 是独立 editor-document boundary。`validateAuthoringDocument` 负责结构化文档的 ID、period、line/call 顺序、tick/dwell、duty assignment、location/time continuity；`generateTimetableSource` deterministic 生成只读 JS。规则：

- document 是 authoring source of truth；generated JS/graph timetable 是 projection。
- 删除 run 要同步 duty assignment 与 arrival override；移动 run 使用单一 pure transaction，不能先加后删产生双 owner。
- presentation-only `diagramLineId` 不替代 infrastructure `lineId`。
- publish 前仍调用 server compile/validate；frontend success 不代表世界引用/route 有效。

参考 `authoring.test.ts`、`editor.test.ts`、`graph.test.ts`、`lines.test.tsx`。

## 路径点与局部停车点输入

具体路径点输入复用 `EditableIdSelect` / `EditableIdList`，候选由 `idOptions.ts` 及调用者的实际 metadata 提供。单值ID保持可编辑，按ID/可见名称忽略大小写筛选，候选选择、Enter或blur只提交一次；Esc取消未提交文本，fieldKey变化丢弃旧字段文本/回调。有序多值使用tags，允许手输，保持输入顺序。作者草稿可以保留未知/已删除ID，不能自动改成第一个候选、清空或用显示名替代canonical ID；原内容/编译校验仍负责引用合法性。

全局waypoint、station-local berthId、depot-local stabling pointId分别传入自己的候选和提交值。普通station/depot聚合ID和泛型ID_LIST不猜成路径点；Blockly仅对明确连接MOVE.viaIds、SELECT_STOP.berthIds、STORE_AT_DEPOT.stablingPointIds的字面量列表提供对应建议。参数引用和无效原始JSON仍可保留修复。重联会合点要沿实际DutyTimeline→字段组件传递名称metadata，并保留原destinations候选范围，不能只改控件却丢名称。

函数模拟起点候选仅来自 `programSimulationWayPoints`，包括符合既有方向规则的depot存车线；利用现有depotDetails显示归属和canonical ID。手输未知/无方向点仍不可执行。任何raw query编辑立即作废旧模拟结果/采纳资格，不等blur，Esc恢复输入也不能复活旧测量。展示候选不是完整车体可放置证明。

受控 `Select mode="tags"` 的searchValue须在接受onChange/选择后显式清空。rc-select内部选择时不保证调用外部onSearch；否则blur会把残留的名称查询当成额外ID提交。测试不能用mock主动onSearch('')替组件掩盖问题；需真实浏览器或忠实选择→blur事件顺序验证无额外值/历史，连同单步Undo/Redo、Esc、owner替换和窄屏标签换行检查。

## Recovery、i18n 与 theme

`authoringRecovery.ts` 的 key 包含 encoded owner/world，只恢复 version 1 且 owner/world 相同的对象。storage parse/write/remove 均 best-effort，异常不能中断编辑。breaking shape 默认丢弃旧 recovery；任务未要求时不加入永久 reader。测试 corrupt/old/cross-world/cross-owner。

`i18n.tsx` 以 `zh` key set 约束英文 `Record<TranslationKey, string>`，新增 key 必须双语齐全；locale 仅 `zh-CN`/`en-US`，浏览器偏好经 `normalizeLocale`。`theme.tsx` 只允许 `dark`/`light`，同步 `data-theme`、`colorScheme` 与 storage。访问 `window`/`localStorage` 的代码保留 SSR/test guard。

## 测试方式

Vitest environment 是 `node`。优先测试 pure transformation；无浏览器 DOM 依赖的 component 使用 `renderToStaticMarkup` 验证公开文案/控件 contract。涉及 fetch/localStorage 时使用最小 mock，并断言 method/path/body/错误，而不是 snapshot 整页 HTML。

## 日志与当前内容状态

`LogsPage`是独立的已认证服务器级页面，使用api.logSources/api.logs读取真实磁盘查询；前端demo明确说明此能力不可用，不伪造成功日志。App先发布已验证session，再查询worlds；worlds失败不得撤销有效session或隐藏日志入口。世界下拉在日志页禁用，避免暗示日志限定于选中世界。

页面展示来源、关键词查询、刷新、前后游标分页及原始记录详情。`warning`表示来源列表不完整而不是没有错误；`reset`提示刷新；`partial`提示当前片段不完整。关键词只扫描单次有界窗口，空页有nextCursor时仍可继续向前查找。来源切换/查询/刷新立即隐藏旧结果，迟到响应不得覆盖新请求。日志内容按纯文本渲染，禁止HTML插入。堆栈分组由reader负责，UI不自行拆解或恢复世界状态。

`IssuesPage`继续展示当前内容的配置/可用性故障，恢复后移除；不保存计算异常历史，不承担日志搜索。此轮不改现有操作错误提示文案。
