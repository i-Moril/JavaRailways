# Docusaurus、参考文件与 CI

## 内容、route 与导航

手写页面位于 `docs/`，frontmatter 使用稳定 `title`/`slug`，`sidebars.ts` 显式维护工程设计、扩展开发、列车模型和时刻表导航。navbar/footer link 应指向实际 slug。`onBrokenLinks: 'throw'` 是构建门槛，禁止用关闭 broken-link 检查掩盖迁移遗漏。

Mermaid 由 `@docusaurus/theme-mermaid` 提供；自定义页面行为放 `src/theme/`，样式放 `src/css/custom.css`。Pagefind UI 在 `Root.tsx` 中通过 `useBaseUrl` 计算 site/base path；不要硬编码 GitHub Pages `/JavaRailwaysDocs/` 到 component，部署值由 `DOCS_URL`/`DOCS_BASE_URL` 提供。

## 语言与版本

当前 `docusaurus.config.ts` 只有：

```text
defaultLocale: zh-Hans
locales: [zh-Hans]
```

`docs/architecture/decisions/0001-documentation-system.md` 决定第一次稳定发布前只维护 current 文档，不创建版本快照。因此：

- 不创建假 version 目录、selector 或英文空镜像来满足模板；
- 增加 locale 时使用 Docusaurus 官方 i18n content path，并同步 config、navigation、search `lang`、CI 与完整关键页面；
- 开启 versioning 前先更新 ADR/发布政策，明确 current/stable 归属、source path 与旧链接迁移；
- serialized token/代码块在任何 locale 都保持原值。

## Schema、model、example 与 reference

`package.json` 的 production build 顺序：

```text
validate-models
  → test-schemas
  → copy-reference-files
  → docusaurus build
  → Pagefind index
```

- `validate-model-packages.mjs` 从主仓 `schemas/train-model/` 读取 Draft 2020-12 schema，并验证 `models/` YAML；YAML 1.2 strict、unique keys，`on/off/yes/no` scene name 必须 quote。
- `test-model-schemas.mjs` 固定 schema rejection cases，防止 schema 接受 loader 不支持的 shape。
- `check-examples.mjs` 对主仓 `examples/`、`models/` 中 `.js` 执行 Node syntax check；示例改动要显式运行，因为当前 `npm run check` 未包含该 script。
- `copy-reference-files.mjs` 把主仓 `schemas/` 和 submodule `reference/types/` 复制到 `static/`。修改 source 后重建，不直接编辑 `static`。

这些 script 通过相对路径读取嵌入主仓的输入；变更 script/path 时既要在主仓 submodule 位置验证输入集成，也要审查 submodule 自有 CI checkout 的运行边界，不能把 Git 独立性和 build input ownership 混为一谈。

当前存在明确的 standalone CI 缺口：`check.yml`/`deploy.yml` 只 checkout `docs-site` repository，但 `validate-model-packages.mjs`、`test-model-schemas.mjs`、`check-examples.mjs` 和 `copy-reference-files.mjs` 会从父目录读取主仓 `schemas/`、`models/`、`examples/`。因此嵌入主仓时的 `npm --prefix docs-site run check` 可以验证集成输入，不能据此宣称 submodule 自有 CI 已具备这些输入。修改 CI/script 时应显式提供有版本归属的主仓输入或重新定义 source ownership，并分别验证 standalone checkout 与嵌入位置；不要通过手工提交 `static/` 生成副本掩盖缺失 source。

## CI 与 submodule

- `check.yml`：Node 22、`npm ci`、`npm run check`。
- `deploy.yml`：main push，设置 Pages URL/base URL，build 后上传 `build/`。
- 本地 `README.md` 要求 Node >=20；lockfile 是 reproducible install 的事实来源。

修改 submodule 内容应在 `docs-site` repository 中形成自己的 commit；主仓只更新 gitlink。不要把根仓 runtime code、Gradle task 或未提交产品改动加入 docs-site 提交来“让 CI 通过”。
