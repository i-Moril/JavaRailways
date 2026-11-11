# BukkitPluginDev Backend 规范

## 适用范围

适用于 `BukkitPluginDev/` 的游戏内开发/测试插件。它只提供 `/jrdev`、`/phys` 等人工 playground 和诊断入口，不承载 production 领域规则、正式 API、持久化 contract 或运行时修复路径。

## 开发前检查

- [ ] 确认场景确实需要游戏内人工验证；可在 Core/Basic/Bukkit 单元测试完成的逻辑先写自动测试。
- [ ] 读取主插件的公开 API/service，避免为了测试复制 production 实现。
- [ ] 标记创建的 world/entity/task/listener 状态及清理命令，确保反复执行和 plugin disable 不污染开发服。
- [ ] 检查命令只对适当 sender 开放，并给出可操作的错误/用法信息。

## 子文档索引

本模块规模小且职责单一，仅保留此 index；生产线程、交互和持久化规则分别链接到 [BukkitPlugin 规范](../../bukkit-plugin/backend/index.md)，不在本模块复制。

## 本地模式

- `BukkitPluginDev` 在 `onEnable` 注册测试命令；`JrDevCommand` 是 dev command hub，`PhysTestCommand` 管理物理 playground。
- build 对 `Core`、`Basic`、`ConfigLib`、`BukkitPlugin` 使用 `compileOnly`，plugin metadata `depend.add("JavaRailways")`；主插件业务类由 JavaRailways classloader/runtime 提供。
- Shadow JAR 显式排除 JavaRailways modules、Kotlin stdlib 和 kotlinx；不要把 production modules 重复打包。
  本插件声明 `depend: [JavaRailways]`，且调用的宿主 API 签名包含 `FunctionN` 等 Kotlin 类型，因此必须让插件类与宿主 API
  解析到宿主 classloader 中的同一份 Kotlin runtime。若 Dev JAR 自带 stdlib，会在真实 Paper 启用时触发 loader constraint violation。
- dev command 可以组合公开能力做观察/故障注入，但 production 行为修复必须回到 owner module 并增加自动测试。
- 不把临时 world 数据、硬编码开发玩家或开发服快照变成正式 default/config。

证据：`BukkitPluginDev/build.gradle`、`BukkitPluginDev/src/main/kotlin/work/art1st/javarailways/bukkit/BukkitPluginDev.kt`、`BukkitPluginDev/src/main/kotlin/work/art1st/javarailways/bukkit/tests/JrDevCommand.kt`、`requirement.md`。

## 质量检查

- [ ] 运行 `./gradlew :BukkitPluginDev:test :BukkitPluginDev:shadowJar`。
- [ ] 检查 deployable JAR 不含 `kotlin/`、`kotlinx/` 或 JavaRailways 业务 classes；只通过编译不能发现 Kotlin classloader identity 冲突。
- [ ] 使用到的主插件 API 改动同时运行 owner module tests。
- [ ] 人工 smoke 记录命令、前置 world、预期输出和 cleanup；至少验证 console/player boundary 与重复执行。
- [ ] 检查 deployable JAR 未包含 Core/Basic/BukkitPlugin 业务 classes。
- [ ] 确认没有把 dev-only code/resource 依赖到 production modules。
- [ ] 运行 `git diff --check`。
