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

## Recovery、i18n 与 theme

`authoringRecovery.ts` 的 key 包含 encoded owner/world，只恢复 version 1 且 owner/world 相同的对象。storage parse/write/remove 均 best-effort，异常不能中断编辑。breaking shape 默认丢弃旧 recovery；任务未要求时不加入永久 reader。测试 corrupt/old/cross-world/cross-owner。

`i18n.tsx` 以 `zh` key set 约束英文 `Record<TranslationKey, string>`，新增 key 必须双语齐全；locale 仅 `zh-CN`/`en-US`，浏览器偏好经 `normalizeLocale`。`theme.tsx` 只允许 `dark`/`light`，同步 `data-theme`、`colorScheme` 与 storage。访问 `window`/`localStorage` 的代码保留 SSR/test guard。

## 测试方式

Vitest environment 是 `node`。优先测试 pure transformation；无浏览器 DOM 依赖的 component 使用 `renderToStaticMarkup` 验证公开文案/控件 contract。涉及 fetch/localStorage 时使用最小 mock，并断言 method/path/body/错误，而不是 snapshot 整页 HTML。
