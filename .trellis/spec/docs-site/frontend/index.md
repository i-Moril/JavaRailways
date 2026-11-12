# docs-site Frontend 规范

## 适用范围

适用于独立 Git submodule `docs-site/`：Docusaurus 3 配置、中文文档、sidebar/slug、Mermaid、Pagefind、schema/model/example validation scripts、reference/static 复制与 GitHub Actions。它不是根 Gradle module，提交和 CI 边界必须与主仓区分。

## 开发前检查

- [ ] 运行 `git submodule status -- docs-site` 和 `git -C docs-site status --short`，确认修改属于 submodule。
- [ ] 阅读 `docusaurus.config.ts`、`sidebars.ts` 与目标 Markdown frontmatter，确认 route/slug/导航。
- [ ] 判断内容源是手写 `docs/`、维护的 `reference/`，还是由 script 生成的 `static/`；禁止编辑生成副本。
- [ ] 涉及 schema/model/example 时同时定位主仓 `schemas/`、`examples/` 真实输入和对应 loader/test。
- [ ] 涉及 locale/version 时先确认当前政策：仅 `zh-Hans`、第一次稳定发布前 current-only。

## 子文档索引

| 文档 | 用途 |
| --- | --- |
| [Docusaurus、参考文件与 CI](./docusaurus-reference-ci.md) | 目录/路由、语言版本、validation/copy/search、submodule workflow |

## 核心规则

- 正文以中文为主，代码标识符、路径、命令、schema key、DSL token 不翻译。
- `docusaurus.config.ts` 使用 `routeBasePath: '/'`、`onBrokenLinks: 'throw'`；新增文档应进入 `sidebars.ts` 或有明确非导航理由。
- `docs/` 是手写站点内容；`static/schemas`、`static/types` 由 `copy-reference-files.mjs` 重建，不是 source of truth。
- JSON Schema 约束结构，Kotlin loader/真实测试约束跨文件语义；两者必须同步，不能只改文档示例。
- submodule 自有 `docs-site/.github/workflows/check.yml`、`docs-site/.github/workflows/deploy.yml` 和 `docs-site/package-lock.json`；主仓提交只能记录 gitlink，不把 submodule 内容当普通 staged files。

## 质量检查

- [ ] 运行 `npm --prefix docs-site ci`。
- [ ] 运行 `npm --prefix docs-site run check`（typecheck、model/schema validation、copy、Docusaurus build、Pagefind）。
- [ ] 修改 JavaScript 示例时额外运行 `npm --prefix docs-site run check-examples`。
- [ ] 检查 Docusaurus broken links、sidebar IDs、slug/navbar links、Mermaid/MDX 与 Pagefind base URL。
- [ ] 检查 `git -C docs-site diff --check` 和 `git -C docs-site status --short`，再检查根仓只记录预期 gitlink。
- [ ] 不提交 `.docusaurus/`、`build/` 或生成的临时搜索产物。

证据：`.gitmodules`、`docs-site/README.md`、`package.json`、`docusaurus.config.ts`、`scripts/`、`docs-site/.github/workflows/`。
