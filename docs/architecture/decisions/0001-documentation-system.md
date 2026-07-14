---
title: ADR-0001：统一文档系统
slug: /architecture/decisions/0001-documentation-system
---

# ADR-0001：统一文档系统

- 状态：Accepted
- 日期：2026-07-13

## 背景

仓库已有少量 Markdown 教程和大量内部实施计划，但没有统一导航、构建检查、API Reference 或配置 schema。模型包文档已经出现示例与解析器不一致的问题，标准 JavaDoc 也无法覆盖以 Kotlin 为主的代码库。

## 决策

- 使用 Docusaurus v3 构建统一静态文档门户。
- 手写文档继续保存在根目录 `docs/`，站点配置保存在 `docs-site/`。
- 工程图使用 Mermaid，重要结构决策使用 ADR。
- Java/Kotlin API Reference 使用 Dokka；公开包确定前不发布全部 public 实现。
- YAML 使用 JSON Schema 提供结构化参考和编辑器补全，真实 loader 测试负责跨文件语义。
- JavaScript DSL 使用 `.d.ts`、可执行示例和运行时 dry-run 共同约束。
- 第一次稳定发布前只维护 current 文档，不创建版本快照。

## 后果

项目新增 Node.js 文档工具链。文档站点可以独立构建为静态文件，但搜索和部署方案要根据站点是否公开、使用何种代码托管平台另行决定。

Material for MkDocs 已进入维护模式；Zensical 的插件和版本化能力仍在演进，因此本次不采用。Antora 的多仓库能力超出当前需求，迁移到 AsciiDoc 的成本也没有收益。
