# JavaRailways 全仓共享规范

## 适用范围

本层只管理根仓库的跨模块契约：Gradle 多项目结构、模块依赖方向、持久化演进政策、文档语言和全局验证。具体实现规则必须下沉到最小 ownership module/layer；不要因为 `javarailways` 的 package path 是 `.`，就在这里记录 `Core`、`WebUI` 或插件内部细节。Core 与 Basic 已分别由 sibling [Core](../core/index.md) 和 [Basic](../basic/index.md) layer 管理。

正文以中文为主；类名、函数名、package、路径、命令、配置 key、JSON 字段和固定序列化 token 保留原文，不翻译 `UP`、`DOWN`、`typeId` 等协议值。

## 开发前检查

- [ ] 根据改动路径加载对应子项目 layer，而不只读取本共享层。
- [ ] 对照 `settings.gradle` 和目标模块 build 文件确认实际依赖、JVM target 与打包方式。
- [ ] 跨模块调用优先使用 `core.api.*`、`basic.api.*` 或明确的 Bukkit add-on API；发现能力缺口时先判断是否应扩展 API。
- [ ] 涉及任何跨重启数据时先确定最小独立持久化边界，并阅读[持久化与 Schema 演进](./persistence.md)。
- [ ] 确认没有把 `docs-site` 的独立 Git/CI 边界当作根仓库普通目录。

## 子文档索引

| 文档 | 用途 |
| --- | --- |
| [模块与构建边界](./modules-and-build.md) | 十个 Gradle 子项目、依赖方向、JVM 与打包关系 |
| [持久化与 Schema 演进](./persistence.md) | 世界快照、配置、extension payload、WebUI recovery 的统一政策 |
| [全局质量检查](./quality.md) | 根构建、CI 对齐和按改动范围选择验证命令 |

跨 package 思考清单见[共享 guides](../../guides/index.md)。

## 核心规则

1. `requirement.md` 是模块职责和“非 Core 模块优先使用 API”的权威来源；不要用 Java/Kotlin 的 `public` 可见性替代 API 承诺。
2. 根 build 负责 JVM 17 的通用 API/Dokka 聚合，Paper/Folia 模块按各自 build 使用 JVM 25；不得用根 target 覆盖平台模块 target。
3. `WebUI` 同时含 Java/Javalin backend 与 React/Vite frontend，两个 layer 都必须审查。
4. `docs-site` 在 `.gitmodules` 中是独立 submodule。修改它时单独检查 submodule 工作树、npm CI 与提交归属。
5. 不把开发期旧数据兼容路径默认加入 runtime；除非任务合同明确要求，采用一次性、可审计迁移。

## 质量检查

- [ ] 运行 `python3 ./.trellis/scripts/get_context.py --mode packages`，确认根 package 能发现 `basic, core, shared, simpleato` layers，其余 package 与 `docs-site [submodule]` 仍可发现。
- [ ] 对照[全局质量检查](./quality.md)运行所有受影响模块的测试/构建。
- [ ] 搜索实现包依赖、持久化版本、协议 token 与文档引用，确认跨层 contract 同步。
- [ ] 若改动 submodule，分别运行 `git status --short` 与 `git -C docs-site status --short`。
- [ ] 运行 `git diff --check`，并确认没有顺带修改无关产品文件。

证据：`settings.gradle`、`build.gradle`、`.gitmodules`、`.github/workflows/build.yml`、`requirement.md`、`ai_tasks/persistence_schema_evolution_rules.md`。
