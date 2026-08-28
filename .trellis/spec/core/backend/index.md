# Core Backend 规范

## 适用范围

适用于 `Core/`：平台无关的 rail/train physics、公开底层 API、事件、Tick/runtime、snapshot DTO 和 test fixtures。Paper/Folia entity、command、render model 与玩家交互不属于本层。

## 开发前检查

- [ ] 判断改动属于 `work.art1st.javarailways.core.api.*` contract 还是内部实现；API 变更要检查所有非 Core consumer。
- [ ] 阅读目标类型 KDoc 中的单位、线程、snapshot/live-view 和阻塞约束。
- [ ] 涉及 physics 时确认状态在哪个 epoch 计算、发布，何时可由 simulation/platform 消费。
- [ ] 涉及 rail topology 时确认 occupancy、physics ticket 和 transaction barrier。
- [ ] 涉及 snapshot 时阅读[持久化与测试](./persistence-and-tests.md)和共享[Schema 政策](../../javarailways/shared/persistence.md)。

## 子文档索引

| 文档 | 用途 |
| --- | --- |
| [API、物理与 runtime](./api-and-runtime.md) | API 边界、事件、physics publication、thread contract |
| [持久化与测试](./persistence-and-tests.md) | split DTO、strict codec、testFixtures 和回归门槛 |

## 核心规则

- `core.api` 暴露平台无关 contract；不得引入 Bukkit/Paper 类型。`World`、`Rail` 等接口必须明确 live view、identity、单位与调用限制。
- 轨道几何 `Rail.curve` 是 immutable AB canonical geometry，位置/弧长按米；方向通过 `RailView` 表达，不为平台展示需求改写 Core 几何语义。
- physics worker 只计算/发布 value state；Bukkit 副作用由平台 owner 消费 immutable frame 后调度。
- 重操作和 thread requirement 使用现有 `@Heavy`、`@MainThread`、`@AsyncEventApi` 与 `ThreadGuard`，但注解不是自动 scheduler。
- 删除/替换 rail 或 train 前守住引用和 occupancy 不变量；不要绕过 `CoreWorld.railTopologyTransaction` 或明确 physics ticket。

## 质量检查

- [ ] 运行 `./gradlew :Core:test`。
- [ ] API 变更至少运行 `./gradlew :Basic:test :BukkitPlugin:test :TunnelBridgeBuilder:test` 或所有实际 consumer 的 focused tests。
- [ ] public API 改动运行 `./gradlew :Core:dokkaGeneratePublicationHtml`，检查实现 package 没有意外进入公开文档。
- [ ] 并发/physics 改动覆盖 coalescing、epoch、stalled/failure、immutable publication 和 cleanup。
- [ ] persistence 改动覆盖 strict decode、版本、引用校验、fixed fixture 与 current writer。
- [ ] 运行 `git diff --check`。

证据：`Core/build.gradle.kts`、`Core/src/main/dokka/CorePackages.md`、`Core/src/main/java/work/art1st/javarailways/core/api/physics/World.kt`、`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt`、`Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt`。
