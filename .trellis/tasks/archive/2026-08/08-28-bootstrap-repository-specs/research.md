# 仓库结构调研

## Trellis 初始状态（bootstrap 前）

以下内容是实施前基线，不描述 bootstrap 完成后的当前工作树：

- `.trellis/config.yaml` 当时只映射 `docs-site`，且 `default_package: docs-site`。
- `get_context.py --mode packages` 当时只输出 `docs-site/frontend`。
- `.trellis/spec/` 当时只包含 `docs-site/frontend/*.md` 与共享 `guides/*.md`，均来自重新初始化模板，尚未覆盖根 Gradle 项目。

## 真实 package 边界

`settings.gradle` 定义根项目 `JavaRailways` 与八个 Gradle 子项目：

| package | 路径 | 证据与职责 |
| --- | --- | --- |
| `javarailways` | `.` | `settings.gradle`、根 `build.gradle`；跨模块构建、Dokka 与依赖协调 |
| `core` | `Core` | `requirement.md`；物理、车辆、轨道基础设施与公开底层 API |
| `config-lib` | `ConfigLib` | `requirement.md`；配置/序列化支持 |
| `basic` | `Basic` | `requirement.md`；曲线、信号、ATO、交路、时刻表和扩展 API |
| `annotation` | `Annotation` | `settings.gradle` 与源码；小型共享注解模块 |
| `bukkit-plugin` | `BukkitPlugin` | `requirement.md`；Paper/Folia composition root、交互、渲染、命令、持久化 |
| `bukkit-plugin-dev` | `BukkitPluginDev` | `requirement.md`；游戏内开发/测试插件 |
| `tunnel-bridge-builder` | `TunnelBridgeBuilder` | build/source；独立 Paper 附加插件，包含算法、rail service 与 wand |
| `webui` | `WebUI` | `WebUI/build.gradle`、`WebUI/frontend/package.json`；Javalin 后端与 React/Vite 前端 |
| `docs-site` | `docs-site` | `.gitmodules`、`docs-site/package.json`；独立 Docusaurus Git submodule |

`docs-site` 必须保留 `type: submodule`。根项目 package 使用 `path: .`，负责不能归属于单一子项目的跨模块 contract，并作为默认 package。

## 规模与层次证据

- `Core`：约 117 个 main 文件、40 个 test 文件，并拥有 `testFixtures`。
- `Basic`：约 96 个 main 文件、53 个 test 文件。
- `BukkitPlugin`：约 167 个 main 文件、71 个 test 文件，是最大的适配/composition 模块。
- `TunnelBridgeBuilder`：约 15 个 main 文件、8 个 test 文件。
- `WebUI`：Java service 与独立 `frontend/`；Gradle `shadowJar` 会触发 `npm ci` 和 frontend build。
- `Annotation`、`ConfigLib`、`BukkitPluginDev` 规模较小，应采用聚焦 spec，避免机械拆分模板。

## 已确认的跨模块约束

- `requirement.md`：非 Core 模块优先使用 API，不直接依赖内部实现；缺失能力时可以审慎扩展 API。
- `ai_tasks/persistence_schema_evolution_rules.md` 与 `AGENTS.md`：持久化边界版本、upcast、migration、extension type ID 等规则是强制政策。
- 根 `build.gradle` 使用 JVM 17；Paper/Folia 模块的实际构建约束需从各模块 build 文件与 CI 读取。
- `docs-site` 拥有独立 npm scripts 与 Git 状态，不能与根仓库提交混合。

## Bootstrap 写作原则

- package 与 layer 按真实 ownership boundary 组织。
- 重要规则必须引用真实源码、测试、build 文件或权威项目文档。
- 小模块允许只有一个高密度 `index.md`；不得为满足模板而制造空文档。
- spec 正文使用中文；代码标识符、路径、命令与协议 token 保留原文。
