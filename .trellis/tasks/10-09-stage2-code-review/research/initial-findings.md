# 只读初审线索

来源：任务创建前本会话的源码初审，基线 `1f31961b`。未在本任务创建过程中重新执行审查或测试。以下是复核入口，不是批准删除或重构的清单；行号可能随代码变化。

1. **旧寻路/目的地索引**：`Basic/.../pathFinding/PathFinder.kt:13` 仅发现测试调用；`RailMap.kt` 仍由 `BasicWorldData.kt:20` 创建，`SimpleATO/.../controller/SimpleATO.kt:1105` 仍读取 destinationsOn，但未发现生产 addDestination。注意 RailMap 文件也包含大量调用的 World 数据访问扩展，不能整文件直接删掉；旧索引测试仍有应保留的行为覆盖。
2. **运营适配器归属**：`BukkitPlugin/.../tool/RailObjectWand.kt:832` 与 `:870` 仍处理 Destination/Station/Depot 编辑；SimpleATOBukkit 主要包含模块接线与诊断。应区分运营编辑与宿主共用方向配置，不机械整体迁移 Wand。
3. **公开签名**：`SimpleATO/.../api/line/LinePlanner.kt:20` 暴露 `basic.route.ExpandedRoute`，`api/mission/AtoMission.kt:79` 暴露 `basic.route.PlannedRoute`，而 Basic Dokka 隐藏非 api 包。复核正式公开承诺和合理归属。
4. **无调用 Java 原型**：`Basic/.../controller/command/AccelerateCommand.java`、`BasicControlCommand.java` 与 `BukkitPlugin/.../rail/CreateRail.java` 未发现生产调用。Lombok 使用集中于 BasicControlCommand，删除候选需连同构建配置复核；有意提供 Java interop 的接口和 consumer 测试不属于同类问题。
5. **复杂度热点**：RailWand 约 6204 行、TimetableDispatcher 约 4957 行、SimpleSignalsWorldRuntime 约 1927 行。重点追踪 Dispatcher 的 ActiveRun/advanceService、runtime 的 applyDefinitionPatch 事务和多标志异常分支；现有 stale/unload/save 回归保护不能因复杂而直接删除。
6. **文档漂移**：docs-site `docs/architecture/modules.md:10` 模块归属过时；文档 ADR 仍指向旧 docs 根路径；SimpleSignals README 的 Kotlin 规则/环限制表述过时；requirement.md 与已调整方向归属存在冲突；`.codeabs` 缺新模块且残留旧 Basic 路径。父任务清单部分未勾选事项已实现（例如拓扑失效/重验证），历史记录不能作为当前缺口证明。
7. **并行 metadata 形状**：`Basic/.../rail/RailInfrastructureMetadataStore.kt` 同时提供分组 current model 与 flat constructor/getter/copy，生产调用混用。评估语义与维护负担；合法一次性迁移不应直接标为废弃 runtime compatibility。
8. **风格与玩家文案**：未发现统一 ktlint/detekt/spotless 门禁；部分 Wand 事件处理压缩为一行，SimpleSignals 的硬编码英文与宿主 i18n 不一致。先定义范围，不做全仓格式化。
9. **空目录**：六个目标模块 src 下初审发现 22 个空目录，多与迁移相关。Git 不跟踪空目录；生成的 bin/logs 与用户未跟踪文件不能当作可直接删除的废代码。

尚未发现可以据此批量删除的“过度兜底”。物理扫描按分支遇环停止与信号输出依赖 DAG 是不同语义，不能混为一谈；也不能将已有拓扑 epoch/原子发布防护视为假想问题。
