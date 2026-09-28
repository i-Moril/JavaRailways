# 模块与构建边界

## 模块 ownership

`settings.gradle` 是 Gradle 模块清单的事实来源：`Core`、`Basic`、`SimpleATO`、`SimpleATOBukkit`、`SimpleSignals`、`Annotation`、`BukkitPlugin`、`BukkitPluginDev`、`TunnelBridgeBuilder`、`ConfigLib`、`WebUI`。职责以 `requirement.md` 和各模块 composition root 为准：

| 模块 | 主要职责 | 关键证据 |
| --- | --- | --- |
| `Core` | 平台无关物理、车辆、轨道、事件、Tick 与底层 API | `Core/src/main/java/work/art1st/javarailways/core/api/`、`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt` |
| `Basic` | 曲线、通用寻路、安全与资源内核，以及自动/手动共用的actuation、制动包络和route/perception snapshot primitive；长期保留平台无关底层机制、通用图算法、拓扑/物理资源推导、资源仲裁、设备准备与释放 | `Basic/src/main/java/work/art1st/javarailways/basic/BasicWorldData.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/api/` |
| `SimpleATO` | 迁移中的平台无关运营模块；当前拥有Destination、Station、Depot/rolling-stock、Line、Timetable、Dispatcher、ATO安全参数/mission/constraint/coordinator、具体controller、手动导航、运营选路/准入client和限制归约 | `SimpleATO/build.gradle.kts`、`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/` |
| `SimpleATOBukkit` | 独立源码/测试边界的Paper/Folia adapter library；由BukkitPlugin直接组合并嵌入同一主插件JAR，不具有第二个插件descriptor或lifecycle | `SimpleATOBukkit/build.gradle.kts`、`SimpleATOBukkitModule.kt` |
| `SimpleSignals` | 独立安装的具体信号系统插件；当前拥有部署生命周期、host capability discovery、严格世界定义sidecar、保护区域定义/拓扑切割、固定保护进路表及planning runtime；authoring、固定闭塞与 rendering 后续实现 | `SimpleSignals/build.gradle`、`SimpleSignalsPlugin.kt`、`persistence/SimpleSignalsWorldDefinitionStore.kt`、`region/ProtectionRegionCatalog.kt` |
| `ConfigLib` | kotlinx.serialization 的持久化代理辅助 | `ConfigLib/src/main/kotlin/work/art1st/configLib/persistence/Persistence.kt` |
| `Annotation` | 独立 KSP 代码生成实验 | `Annotation/src/main/kotlin/work/art1st/javarailways/annotation/processor/PersistentByProcessor.kt` |
| `BukkitPlugin` | Paper/Folia composition、命令/工具、渲染、世界生命周期与持久化 | `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt`、`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt` |
| `BukkitPluginDev` | 游戏内开发测试命令和 playground | `BukkitPluginDev/src/main/kotlin/work/art1st/javarailways/bukkit/BukkitPluginDev.kt` |
| `TunnelBridgeBuilder` | 独立 add-on 的横断面扫掠、方块应用和 Wand | `TunnelBridgeBuilder/src/main/kotlin/work/art1st/javarailways/tunnelbridgebuilder/TunnelBridgeBuilderPlugin.kt` |
| `WebUI` | 独立 Javalin sidecar 和 React 编辑器 | `WebUI/build.gradle`、`WebUI/README.md` |

`docs-site` 不在 Gradle `include` 中；它由 `.gitmodules` 声明为独立 Git submodule。

`SimpleATO`已经进入`settings.gradle`，并已拥有Dispatcher、ATO mission/constraint/coordinator、具体controller、Destination、Station、Depot、Line、Timetable、运营冻结路线、通用准入client、信号限制与inspection runtime；旧`AtoProvider`切换机制已经删除，Basic仅保留共享物理actuation、安全包络、snapshot与通用resource primitive，所以ATO policy owner迁移已经完成。`SimpleATOBukkit`已建立为独立Gradle adapter library，并由`BukkitPlugin`直接调用、打入同一个JavaRailways JAR；它没有第二个Bukkit plugin descriptor、lifecycle或内部service-discovery跳转。首个独立信号系统插件`SimpleSignals`已建立独立JAR、descriptor、单一`JavaPlugin` lifecycle和host service capability gate，并已拥有严格世界定义sidecar、保护区域定义/图切割/catalog/index、固定保护进路表及按world注册的planning runtime；authoring、固定闭塞和运行期信号规则仍待后续批次。目标ownership是：`Basic`保留拓扑校验、不可删除的物理资源底线、通用资源仲裁、设备准备与释放；`SimpleATO`拥有ATO/ATP、运行方向、Destination/Station/Depot/Line/Timetable、运营寻路、资源检查调度和限制归约，目标合同见[SimpleATO规范](../simpleato/index.md)；`SimpleATOBukkit`只拥有SimpleATO自身的Bukkit适配；`SimpleSignals`拥有首套简易固定闭塞、保护区域、联锁、信号模型、authoring/rendering、持久化以及可选JS/YAML；其他独立信号系统插件不依赖其领域模型；`BukkitPlugin`是唯一JavaRailways主插件的composition root，并保留通用平台调度/渲染、RailWand、手动驾驶、world composition和其他底层平台能力。每一批迁移必须同时移动contract、实现、测试和composition，不得制造两个运行owner。

## 依赖方向

- `Core` 依赖 `ConfigLib`；`Basic` 以 `compileOnly` 依赖 `Core`/`ConfigLib`，测试通过 `testFixtures(project(":Core"))` 获取 fixture。
- `SimpleATO` 当前以 `compileOnly` 依赖 `Core`/`Basic`，测试直接依赖两者及其 test fixtures；依赖方向不得反转为 Basic→SimpleATO。
- `SimpleATOBukkit`以`api`依赖SimpleATO，当前只因启动组合使用Core compile classpath；只有实际SimpleATO平台适配需要时才添加Paper或其他platform dependency。它不依赖`BukkitPlugin`，而由`BukkitPlugin`以`implementation`嵌入，避免module cycle。具体信号插件按需要依赖SimpleATO/Basic公开API；真实跨插件需求经评审后才使用BukkitPlugin的公开platform facade，不能让Basic或SimpleATO反向依赖某个国家信号插件。
- `SimpleSignals`对Core、Basic、SimpleATO、BukkitPlugin和Paper只使用`compileOnly`，通过`depend.add("JavaRailways")`与ServicesManager建立运行时边界；宿主不依赖或嵌入该模块。
- `BukkitPlugin` 是唯一 platform composition root，可组合 `Core`、`Basic`、`SimpleATO`、`SimpleATOBukkit`、`ConfigLib`，但向 add-on 暴露能力时应放在 `work.art1st.javarailways.bukkit.api.*`，例如 `RailTargetService`、`PlayerOperationService`。
- `BukkitPluginDev` 和 `TunnelBridgeBuilder` 对 JavaRailways 模块使用 `compileOnly`；后者由 `depend.add("JavaRailways")` 声明运行时 owner，不得把主插件业务类 shadow 进 add-on JAR。
- `WebUI` 不直接依赖 JVM 领域模块；它通过 HTTP bridge 交互，并由 `BukkitPlugin` 的 `processResources` 嵌入 `JavaRailways-WebUI.jar`。
- `Annotation` 当前未接入 Core 主构建（`Core/build.gradle.kts` 中相关依赖被注释）；不要把实验 processor 当作已发布生成 contract。

禁止在非 Core 模块新增对 `work.art1st.javarailways.core.physics.*` 等实现包的依赖作为默认捷径。若现有代码已经有实现耦合，先判断 API 能否承载需求；不要把技术债扩散到新公开接口。证据：`requirement.md`、各模块 `build.gradle*`、`docs-site/docs/architecture/modules.md`。

## JVM 与打包

- 根、`Core`、`Basic`、`SimpleATO`、`ConfigLib`、`Annotation` 和 `WebUI` 使用 Java/Kotlin 17 toolchain/target。
- 当前 `SimpleATOBukkit`、`SimpleSignals`、`BukkitPlugin`、`BukkitPluginDev`、`TunnelBridgeBuilder` build 使用 JVM 25；根 CI 也安装 JDK 25。以 build 文件为准，不依赖可能滞后的说明文档。
- `BukkitPlugin:shadowJar` 合并 runtime、重定位 PacketEvents/EntityLib/Cloud，并嵌入 `WebUI:shadowJar`。
- `SimpleATOBukkit`只生成普通library JAR供Gradle组合；该JAR不得包含`plugin.yml`，最终class由`BukkitPlugin:shadowJar`合入JavaRailways主插件，不作为独立服务器插件发布。
- `SimpleSignals`生成带独立`plugin.yml`的普通部署JAR；该JAR不shadow Core、Basic、SimpleATO或BukkitPlugin class。
- `TunnelBridgeBuilder:shadowJar` 只产出 add-on；JavaRailways 与语言运行库由插件依赖/Paper libraries 提供。
- 根 Dokka 当前聚合 `Core`、`Basic` 与 `SimpleATO` 的候选 API；`SimpleATO` 只公开 `work.art1st.javarailways.simpleato.api.*`，迁移期内部package仍保持suppression。

## 变更检查

1. 改 module graph 时同步审查 `settings.gradle`、根/目标 build、`.github/workflows/build.yml` 和运行任务。
2. 改 API 时验证 Core/Basic Dokka 边界，并检查所有 downstream compile/test。
3. 改 platform dependency 时检查 Shadow relocation、service resource merge、plugin dependency 与 dev server staging，不能只确认编译通过。
4. 不修改 module 名、package 名或 submodule 结构来迎合 Trellis package 映射。
