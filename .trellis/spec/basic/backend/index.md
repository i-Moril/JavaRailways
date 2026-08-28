# Basic Backend 规范

## 适用范围

适用于 `Basic/` 的运营逻辑层：curve/rail authoring、path finding、route/safety、Line/Station/Depot、ATO、timetable、extension provider 和 Basic snapshot。Core 物理机制与 Bukkit 玩家交互分别归属其他 package。

## 开发前检查

- [ ] 确认需求属于可复用的 standalone 运营逻辑，而不是 Paper 命令、entity 或 render 细节。
- [ ] 优先从 `core.api.*` 使用 Core；若现有实现耦合 Core internals，不要把耦合扩散到新 API。
- [ ] 明确 rail arc length 的 AB canonical 基准、`ViewDirection` 和所有速度/距离/时间单位。
- [ ] 时刻表改动先区分 authoring metadata、validation、dispatcher runtime 和 simulation contract。
- [ ] snapshot/extension 变更阅读[持久化与测试](./persistence-and-tests.md)及共享 Schema 政策。

## 子文档索引

| 文档 | 用途 |
| --- | --- |
| [领域、API 与运行逻辑](./domain-and-api.md) | world-local store、curve、route/safety、timetable/extension contract |
| [持久化与测试](./persistence-and-tests.md) | Basic split snapshot、引用校验、fixture 与测试门槛 |

## 核心规则

- `BasicWorldData` 是每个 `World` 的运营数据 owner；Line、Station、Depot、Timetable、provider registry 不得变成跨 world 全局可变状态。
- 公开候选面放在 `work.art1st.javarailways.basic.api.*`；Dokka 隐藏其余实现 package。不要把普通 `public` 实现提升为兼容承诺。
- curve 生成保持无 Bukkit state，并在重计算前检查 off-main-thread；平台层只负责采集 anchor/提交结果。
- route、line、timetable 等持久化引用使用 stable ID/UUID 和方向/弧长值，不持有 Core 内部 location 对象。
- validation 汇总可定位 errors；未知 provider、缺失 rail/station/depot、revision conflict 不得静默猜测或替换。

## 质量检查

- [ ] 运行 `./gradlew :Basic:test`。
- [ ] 公开 API 或 persistence contract 变更运行 `./gradlew :BukkitPlugin:test :WebUI:test`，必要时加 frontend test。
- [ ] curve/route 改动覆盖 AB/BA、方向限制、不可达、端点/单位、数值容差和 worker guard。
- [ ] timetable 改动覆盖 validator、dispatcher、simulation、extension unavailable/error 和恢复状态。
- [ ] snapshot 改动覆盖 strict decode、orphan reference、revision、固定 fixture 与 round-trip。
- [ ] 运行 `git diff --check`。

证据：`Basic/build.gradle.kts`、`Basic/src/main/java/work/art1st/javarailways/basic/BasicWorldData.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/curve/RailCurveFactory.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/timetable/Timetable.kt`、`requirement.md`。
