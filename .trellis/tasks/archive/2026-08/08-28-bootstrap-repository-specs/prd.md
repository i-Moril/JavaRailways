# 全仓库 Trellis Spec Bootstrap

## 目标

修正 `trellis init` 只识别 `docs-site` 的 package 映射，使根 Gradle 多项目与文档子模块都进入 Trellis 上下文；随后按官方 bootstrap 流程，从真实源码、测试、构建配置和项目文档生成以中文为主的项目级 spec。

## 背景与已确认事实

- `settings.gradle` 定义根项目 `JavaRailways`，并包含八个 Gradle 子项目：`Core`、`Basic`、`Annotation`、`BukkitPlugin`、`BukkitPluginDev`、`TunnelBridgeBuilder`、`ConfigLib`、`WebUI`。
- `.gitmodules` 定义独立 Git submodule `docs-site`。
- 任务启动时 `.trellis/config.yaml` 只包含 `docs-site`，并将它设置为 `default_package`。
- 任务启动时 `.trellis/spec/` 只有 `docs-site/frontend` 与共享 thinking guides，无法为根仓库 JVM 模块提供 package-scoped spec。
- `requirement.md` 明确了 Core、ConfigLib、Basic、BukkitPlugin、BukkitPluginDev 的职责和“非 Core 模块优先使用 API”的依赖约束。
- 项目开发者偏好中文；spec 正文应使用中文，代码标识符、路径、API/协议字段、命令与固定序列化 token 保留原文。

## 范围

1. 修正 `.trellis/config.yaml` 的 package/default package 映射。
2. 重新组织 `.trellis/spec/`，覆盖根项目、八个 Gradle 子项目和 `docs-site`。
3. 按真实 ownership boundary 为各 package 选择适用 layer，不机械复制 backend/frontend 模板。
4. 阅读代表性源码、测试、构建脚本和项目文档，写入可执行规则、真实路径、正反例与验证命令。
5. 清理不适用的模板内容、重复内容和 placeholder。
6. 验证 package discovery、索引、相对链接、中文约定及 spec 与代码证据的一致性。

## 已确认决策

采用逐模块映射：根项目、八个 Gradle 子项目和 `docs-site` 都拥有独立 package；根项目 package 负责跨模块规范并作为默认 package。

## 要求

- 第一个实施步骤必须是修正 package 映射；映射验证通过前不开始批量写 spec。
- package 映射必须覆盖 `settings.gradle` 的全部八个子项目和 `docs-site`。
- 根项目的跨模块构建、依赖方向和持久化政策必须有明确归属，不能挂靠到 WebUI 或 docs-site。
- `WebUI` 必须区分 Java/Javalin 后端与 React/Vite 前端。
- `docs-site` 必须保留 `type: submodule`，其 Git/CI 边界不得与根仓库混淆。
- spec 必须以源码或权威项目文档为证据，不得保留通用模板填充语句。
- 不修改产品运行时代码。
- 不覆盖或整理当前工作区中与 Trellis bootstrap 无关的未提交变更。

## 不在范围内

- 修复 bootstrap 期间发现的产品代码缺陷或架构技术债。
- 修改 Gradle module、package name 或 docs-site 子模块结构。
- 为旧开发数据新增 runtime compatibility path。
- 提交、push 或清理用户现有的未提交代码。

## 验收标准

- [ ] `get_context.py --mode packages` 能列出根项目、八个 Gradle 子项目和 `docs-site`，且默认 package 不再是 `docs-site`。
- [ ] 每个 package 的 spec layer 与真实职责一致；`WebUI` 同时拥有 backend/frontend 指引。
- [ ] 每个 spec index 都说明适用范围、开发前检查、子文档索引与质量检查。
- [ ] 关键规则引用真实源码、测试、构建脚本或项目文档路径。
- [ ] 所有 spec 正文以中文为主，技术标识符保持原文。
- [ ] `.trellis/spec/` 不含 placeholder、明显无关的上游 Trellis 内部示例或重复模板章节。
- [ ] Markdown 相对链接、代码围栏、UTF-8、行尾与索引引用检查通过。
- [ ] bootstrap 只修改 Trellis/agent 集成与任务文档，不触碰无关产品变更。
