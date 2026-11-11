# 跨 package 思考清单

## 适用范围

本目录只保存所有 package 都会用到的短检查清单；具体规则、路径和命令归属对应 package layer，不在这里复制。

## 开发前检查

- [ ] 找到最小 owner：领域、平台、transport、UI、文档还是 migration。
- [ ] 用 `rg` 搜索现有 API/helper/token/serializer/test，确认不是重复实现。
- [ ] 列出 producer、consumer、持久化边界和失败 owner。
- [ ] 加载所有 touched package 的 index，而不只加载默认 package。

## 子文档索引

| Guide | 何时使用 |
| --- | --- |
| [复用与 ownership](./reuse-and-ownership.md) | 准备新增 helper/API、看到重复逻辑或跨模块调用时 |
| [跨层 contract](./cross-layer.md) | 数据跨 Core/Basic/Bukkit/WebUI/docs/persistence boundary 时 |
| [可执行契约编写](./code-spec-authoring.md) | 新增或修改模块、算法、索引、缓存、并发或跨层契约时 |

## 质量检查

- [ ] 每条 CRITICAL/WARNING 都回到真实源码、测试或 build 证据确认。
- [ ] contract 变更已同步所有 producer/consumer 和正反 fixture。
- [ ] 没有因跨层方便而放宽 strict decoder、线程 owner 或模块依赖。
- [ ] 已运行每个 affected package index 的 Quality Check。
