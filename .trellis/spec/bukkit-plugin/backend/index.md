# BukkitPlugin Backend 规范

## 适用范围

适用于 `BukkitPlugin/` 的 Paper/Folia composition 与适配层：plugin lifecycle、world/tick bridge、Cloud commands、Wand/Dialog、模型和 render backend、公开 add-on service、Bukkit sidecar 与 world persistence。Core/Basic 的平台无关领域规则不得在本层重复实现。

## 开发前检查

- [ ] 从 `BukkitPlugin.kt`/`WorldManager.kt` 确认对象在哪个 lifecycle 阶段创建、注册、启动、停止和释放。
- [ ] 对每个 Bukkit access 标出 global simulation、async worker、region/location owner 或 entity owner；不要把 `@MainThread` 当作 Folia ownership。
- [ ] 命令/工具逻辑先寻找 Basic/Core API 或 Bukkit `api.*` service，避免直接扩散内部实现依赖。
- [ ] render 变更区分 immutable Core frame、platform dispatch、backend visual、audience 与 model state。
- [ ] persistence/config 改动阅读[交互、渲染与持久化](./interaction-render-persistence.md)和共享 Schema 政策。

## 子文档索引

| 文档 | 用途 |
| --- | --- |
| [Lifecycle 与线程归属](./lifecycle-and-threading.md) | composition root、world tick、Folia scheduler、shutdown |
| [SimpleDisplays 可选服务](./optional-simpledisplays.md) | 已迁移至 Decorations；宿主只开放通用 extension API |
| [显示模板包资源](./package-displays.md) | 通用扩展目录、来源包原文、快照hash与失败重载 |
| [屏幕部件定义](./screen-parts.md) | namespaced 扩展部件、缺失插件保留子树、注册生命周期 |
| [Wand 配置界面](./wand-config.md) | 配置项、注册、会话、动态显示和提交错误 |
| [交互、渲染与持久化](./interaction-render-persistence.md) | Cloud/Wand/Dialog、backend/model、add-on API、snapshot/sidecar |
| [列车 platform 乘客移动](./passenger-movement.md) | 乘客脚底接触、视角相对行走、同 carrier 平台交接与 owner 清理 |
| [一次性离线开发服迁移](./offline-development-migration.md) | 整目录外部归档、旧split转换、受限曲线修复和真实restore；不进入JAR |
| [独立道岔操作工具](./turnout-wand.md) | 来向/出口选择、安全手动切换、真实连接反馈 |
| [铁路编辑标记视觉规范](./editor-markers.md) | 点、方向、区间和警告的共用含义；CURRENT临时文字公开API、玩家隔离及关闭完成；不统一业务模型 |
| [通用文字 Display 后端](./text-display-backends.md) | native/EntityLib文字、Component/样式一致性、现有metadata/owner/cleanup复用 |
| [通用模型显示与轨道定位 API](./model-presentation-api.md) | 曲线查询、不可变模型定义、完整场景应用、跨线程显示与清理完成通知 |
| [每车 JS 行为](./car-behavior.md) | CURRENT：车厢独立脚本状态、逐车调度、延迟效果汇总与来源包快照 |
| [隔离模拟逐车行为](./simulation-car-behavior.md) | 真实JS/gateway、detached每车捕获、结构配置继承与无显示效果出口 |
| [模块错误文件](./error-logging.md) | 独立插件各自errors.log、JUL出口、后台串行追加/轮转与关闭 |

## 核心规则

- `BukkitPlugin` 是 composition root：初始化顺序与反向 shutdown 都是 contract；每个 event/service/task/executor 必须有明确 cleanup。
- Folia 下不存在万能主线程。global task 只做 simulation/value 协调；entity/location/block side effect 使用 `PlatformDispatcher` 或 target-aware scheduler/guard。
- Heavy geometry/query/script/JSON IO 不在 owning region 或 simulation context 执行；异步结果回到正确 owner 后再次验证 world/entity/revision。
- add-on 能力放入 `work.art1st.javarailways.bukkit.api.*`；有生命周期的服务通过 Bukkit service registry 提供，无状态的Wand按键路由可直接调用公开helper。不要要求独立插件链接 fat JAR 内部实现。
- corrupt persistence/config 不得静默覆盖。fail-fast、保留原数据或明确禁用受影响 world，比 fallback 到 defaults 更安全。

## 质量检查

- [ ] 测试优先验证公开行为和实际结果；避免仅检查私有锁、JVM 线程状态，或让等待探针执行额外业务操作。
- [ ] 运行 `./gradlew :BukkitPlugin:test`。
- [ ] 隔离模拟等长直轨夹具显式使用 `PrimeCurveGenerator.buildStraight(..., step = 1.0)`，保留长度、分段和业务断言。
      默认采样为 1 毫米；多轨道及 capture/restore 复制曲线时会使测试 JVM OOM。需要细采样的几何测试单独保留，
      这类夹具降负载须在 512 MiB 测试堆下验证整个模块，不能只检查首个失败方法。
- [ ] `PlatformDispatchLifecycleTest` 等用 latch 阻塞并发参与者的测试，显式使用测试自有的平台线程执行器，
      并在失败时释放 latch、关闭执行器且等待退出；JDK 25 的 2 核公共线程池可能只有一个 worker，不能依赖它启动第二个参与者。
- [ ] 打包/lifecycle/resource 变更运行 `./gradlew :BukkitPlugin:shadowJar`，检查嵌入 WebUI 与 relocation/service merge。
- [ ] Basic/Core contract 变更连同 `:Core:test :Basic:test`；WebUI bridge 变更连同 `:WebUI:test` 和 frontend tests。
- [ ] thread 改动覆盖 owner guard、latest-only/coalescing、retired entity、world unload、plugin disable 和 failure cleanup。
- [ ] 命令/Wand/Dialog 改动覆盖 route/suggestion、interaction routing、PDC codec、输入校验与 locale key。
- [ ] 需要时运行 `./gradlew :BukkitPlugin:runFolia` 做跨 region smoke，并记录未自动化场景。
- [ ] 运行 `git diff --check`。

证据：`BukkitPlugin/build.gradle`、`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt`、`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt`、`docs-site/docs/architecture/threading-model.md`。
