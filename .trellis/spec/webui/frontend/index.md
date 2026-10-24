# WebUI Frontend 规范

## 适用范围

适用于 `WebUI/frontend/` 的 React/Vite/TypeScript timetable editor、rail map、line/simulation UI、API client、authoring model、local crash recovery、i18n/theme 和 Vitest。Java/Javalin route owner 见 sibling backend layer。

## 开发前检查

- [ ] 读取 `api.ts`、`types.ts` 与对应 Java route/test，核对 method/path/JSON/status/auth。
- [ ] 判断 state 是 server state、editor document、derived presentation、undo history 还是 best-effort local recovery；不要混为一个 source of truth。
- [ ] 领域变换优先放入纯 `.ts` 函数并写 Vitest，React component 负责 orchestration/render。
- [ ] 新文案同时加入 `zh-CN`/`en-US`，固定 protocol token 不翻译。
- [ ] 修改 localStorage shape 前阅读[组件、状态与跨层 contract](./components-state-contracts.md)和共享 persistence policy。

## 子文档索引

| 文档 | 用途 |
| --- | --- |
| [组件、状态与跨层 contract](./components-state-contracts.md) | component/state/API、authoring/recovery、i18n/theme 与测试 |
| [Studio 编辑与异步隔离](./studio-authoring.md) | 页面共享 owner、迟到响应、交路身份、图上改时刻与前置条件 |
| [程序积木编辑](./program-blocks.md) | 共享画布、语义映射、未完成草稿、到站上下文与历史 |

## 核心规则

- `api.ts` 是 browser HTTP 调用集中 owner，`types.ts` 是 wire type owner；component 不手写第二套 fetch/path/payload cast。
- server validation 是发布权威；frontend pure validation 提供即时反馈但不能绕过 backend/Basic validation。
- structured authoring document、generated JS、compiled timetable 和 graph projection 是不同 contract；derived graph 不反写为 authoritative runtime steps。
- guest 只导航到 public map；401 统一触发 `javarailways:unauthorized` 清理 privileged state。
- local recovery 是按 owner+world 隔离的 best-effort crash recovery，可丢弃重建，不建立永久 migration chain。

## 质量检查

- [ ] 运行 `npm --prefix WebUI/frontend test`。
- [ ] 运行 `npm --prefix WebUI/frontend run build`（包含 `tsc -b` 与 Vite build）。
- [ ] API/wire 改动运行 `./gradlew :WebUI:test`，必要时运行 Bukkit bridge tests。
- [ ] state/authoring 改动覆盖 pure reducer/validator/history、invalid/empty/boundary、stale async result 与 deterministic generation。
- [ ] component 改动检查 guest/auth navigation、loading/error、accessible label/role 和中英文文案。
- [ ] recovery/theme/locale 改动覆盖 unavailable/corrupt storage、owner/world isolation 和 SSR/window guard。
- [ ] 运行 `git diff --check`。

证据：`WebUI/frontend/package.json`、`tsconfig.app.json`、`src/App.tsx`、`src/api.ts`、`src/authoring.ts`、`src/authoringRecovery.ts`。
