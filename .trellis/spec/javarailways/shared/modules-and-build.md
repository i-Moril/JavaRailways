# 模块与构建边界

## 模块 ownership

`settings.gradle` 是 Gradle 模块清单的事实来源：`Core`、`Basic`、`SimpleATO`、`Annotation`、`BukkitPlugin`、`BukkitPluginDev`、`TunnelBridgeBuilder`、`ConfigLib`、`WebUI`。职责以 `requirement.md` 和各模块 composition root 为准：

| 模块 | 主要职责 | 关键证据 |
| --- | --- | --- |
| `Core` | 平台无关物理、车辆、轨道、事件、Tick 与底层 API | `Core/src/main/java/work/art1st/javarailways/core/api/`、`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt` |
| `Basic` | 当前承载曲线、寻路、安全、线路、ATO、时刻表及扩展 API；重构目标保留平台无关底层机制、固定闭塞/保护区域`AuthoritySection`定义、拓扑资源校验与通用算法 | `Basic/src/main/java/work/art1st/javarailways/basic/BasicWorldData.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/api/` |
| `SimpleATO` | 迁移中的平台无关运营模块；当前拥有手动导航解析/临时时刻表生成和时刻表展示，目标拥有 ATO、运营对象、时刻表、运营寻路与信号/闭塞运用策略 | `SimpleATO/build.gradle.kts`、`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/` |
| `ConfigLib` | kotlinx.serialization 的持久化代理辅助 | `ConfigLib/src/main/kotlin/work/art1st/configLib/persistence/Persistence.kt` |
| `Annotation` | 独立 KSP 代码生成实验 | `Annotation/src/main/kotlin/work/art1st/javarailways/annotation/processor/PersistentByProcessor.kt` |
| `BukkitPlugin` | Paper/Folia composition、命令/工具、渲染、世界生命周期与持久化 | `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt`、`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt` |
| `BukkitPluginDev` | 游戏内开发测试命令和 playground | `BukkitPluginDev/src/main/kotlin/work/art1st/javarailways/bukkit/BukkitPluginDev.kt` |
| `TunnelBridgeBuilder` | 独立 add-on 的横断面扫掠、方块应用和 Wand | `TunnelBridgeBuilder/src/main/kotlin/work/art1st/javarailways/tunnelbridgebuilder/TunnelBridgeBuilderPlugin.kt` |
| `WebUI` | 独立 Javalin sidecar 和 React 编辑器 | `WebUI/build.gradle`、`WebUI/README.md` |

`docs-site` 不在 Gradle `include` 中；它由 `.gitmodules` 声明为独立 Git submodule。

`SimpleATO`已经进入`settings.gradle`，但只完成第一批dependency-safe owner迁移；`SimpleATOBukkit`仍是尚未创建的目标模块。迁移完成后的ownership是：`Basic`保留固定闭塞与保护区域定义、统一authority partition、拓扑校验和资源准入；`SimpleATO`拥有ATO、运行方向、Destination/Station/Depot/Line/Timetable、运营寻路、信号/闭塞运用策略以及`AuthoritySection`消费，目标合同见[SimpleATO规范](../simpleato/index.md)；`SimpleATOBukkit`拥有其Bukkit适配、渲染、RailObjectWand及JS/Java扩展加载，并通过Basic API编辑固定闭塞和保护区域；`BukkitPlugin`只保留RailWand、手动驾驶、world/composition和其他底层平台能力。每一批迁移必须同时移动contract、实现、测试和composition，不得先制造两个运行owner。

## 依赖方向

- `Core` 依赖 `ConfigLib`；`Basic` 以 `compileOnly` 依赖 `Core`/`ConfigLib`，测试通过 `testFixtures(project(":Core"))` 获取 fixture。
- `SimpleATO` 当前以 `compileOnly` 依赖 `Core`/`Basic`，测试直接依赖两者及其 test fixtures；依赖方向不得反转为 Basic→SimpleATO。
- `BukkitPlugin` 是迁移期 platform composition root，可组合 `Core`、`Basic`、`SimpleATO`、`ConfigLib`，但向 add-on 暴露能力时应放在 `work.art1st.javarailways.bukkit.api.*`，例如 `RailTargetService`、`PlayerOperationService`。
- `BukkitPluginDev` 和 `TunnelBridgeBuilder` 对 JavaRailways 模块使用 `compileOnly`；后者由 `depend.add("JavaRailways")` 声明运行时 owner，不得把主插件业务类 shadow 进 add-on JAR。
- `WebUI` 不直接依赖 JVM 领域模块；它通过 HTTP bridge 交互，并由 `BukkitPlugin` 的 `processResources` 嵌入 `JavaRailways-WebUI.jar`。
- `Annotation` 当前未接入 Core 主构建（`Core/build.gradle.kts` 中相关依赖被注释）；不要把实验 processor 当作已发布生成 contract。

禁止在非 Core 模块新增对 `work.art1st.javarailways.core.physics.*` 等实现包的依赖作为默认捷径。若现有代码已经有实现耦合，先判断 API 能否承载需求；不要把技术债扩散到新公开接口。证据：`requirement.md`、各模块 `build.gradle*`、`docs-site/docs/architecture/modules.md`。

## JVM 与打包

- 根、`Core`、`Basic`、`SimpleATO`、`ConfigLib`、`Annotation` 和 `WebUI` 使用 Java/Kotlin 17 toolchain/target。
- 当前 `BukkitPlugin`、`BukkitPluginDev`、`TunnelBridgeBuilder` build 使用 JVM 25；根 CI 也安装 JDK 25。以 build 文件为准，不依赖可能滞后的说明文档。
- `BukkitPlugin:shadowJar` 合并 runtime、重定位 PacketEvents/EntityLib/Cloud，并嵌入 `WebUI:shadowJar`。
- `TunnelBridgeBuilder:shadowJar` 只产出 add-on；JavaRailways 与语言运行库由插件依赖/Paper libraries 提供。
- 根 Dokka 当前只聚合 `Core` 与 `Basic` 的候选 API；`SimpleATO`尚无冻结public API package，所以迁移期内部package不进入聚合文档。

## 变更检查

1. 改 module graph 时同步审查 `settings.gradle`、根/目标 build、`.github/workflows/build.yml` 和运行任务。
2. 改 API 时验证 Core/Basic Dokka 边界，并检查所有 downstream compile/test。
3. 改 platform dependency 时检查 Shadow relocation、service resource merge、plugin dependency 与 dev server staging，不能只确认编译通过。
4. 不修改 module 名、package 名或 submodule 结构来迎合 Trellis package 映射。
