# Basic 可执行契约索引

## 范围与加载条件

当前`Basic/`承载curve/rail metadata、安全、route/resource、WayPoint、route/perception snapshot，以及自动/手动控制共用的物理actuation primitive；ATO mission、constraint、world coordinator与具体controller均由`SimpleATO/`拥有，旧`AtoProvider`切换机制已经删除。Destination、Station、Depot、rolling-stock ledger、Line、timetable model/store/extensions/persistence、Dispatcher、checkpoint和隔离模拟runtime同样由`SimpleATO/`拥有。目标模块边界见[模块与构建边界](../shared/modules-and-build.md)和[SimpleATO目标规范](../simpleato/index.md)：Basic长期只保留平台无关底层机制、通用图算法、资源注册/仲裁、冻结路线的物理资源推导、设备准备、grant与释放。固定闭塞、保护区域和具体联锁策略属于独立信号系统插件，不是Basic的通用领域模型。Core物理与frame shape由[Core层](../core/index.md)定义；Bukkit/Paper entity、scheduler、viewer与model side effect由platform adapter拥有。

## 主题导航

轨道公共运行方向已由 Basic 唯一保存；SimpleATO 和信号插件只消费这份公共配置。
工作流程、稀疏存储与未部署存档修正见[轨道公共运行方向](./operating-directions.md)。

| 文档 | 何时加载 | 唯一归属 |
| --- | --- | --- |
| [API 边界](./api-boundaries.md) | 改`basic.api.*`、Dokka、Core/Bukkit imports | 候选API与consumer边界 |
| [world runtime](./world-runtime.md) | 构造/查找`BasicWorldData`或world stores | world-local composition与RailObject lifecycle |
| [threading 与 publication](./threading-and-publication.md) | main↔physics command、observation publication、Dispatcher顺序 | cross-thread handoff与Basic safety interlock |
| [监测区占用](./railway-area-occupancy.md) | 区间占用、边界ENTER/LEFT、响应式信号输入 | Basic通用物理派生，不包含信号制式策略 |
| [ATO snapshot](./ato-missions-and-perception.md) | route/perception snapshot与revision queue | Basic immutable input primitive；mission归SimpleATO |
| [自动/手动物理控制 primitive](./ato-control-and-constraints.md) | actuator、control demand、braking envelope、world safety latch | Basic共享物理执行；ATO policy归SimpleATO |
| [列车纵向动力学](./train-longitudinal-dynamics.md) | 牵引/制动/阻力/坡度、人工或ATO手柄执行、重力注入 | Basic共享动力学、真实手柄执行与坡度制动包络 |
| [RailObject store](./ato-providers.md) | 注册/删除通用RailObject | generic lifecycle；不包含ATO provider切换 |
| [train operations](./train-operations.md) | door/model/announcement adapter | request/ticket/cancel/completion |
| [curve authoring](./curve-authoring.md) | Anchor/template/solver/measurement | stable token、SI单位、off-main build |
| [rail infrastructure](./rail-infrastructure.md) | metadata、split/merge/turnout | metadata ownership与Core topology同步 |
| [轨道公共运行方向](./operating-directions.md) | 改方向读写、选路限制转换、方向通知或存档 | Basic唯一方向存储与公共只读值 |
| [speed 与 derail safety](./speed-and-derail-safety.md) | speed signal/geometry threshold/derail request | operating limit与worker→main safety |
| [route planning](./route-planning.md) | immutable topology shortest path、neutral restrictions、progress | Basic纯图算法与SimpleATO统一生产seam |
| [resource allocation](./resource-allocation.md) | turnout/section/berth locks/manual resource | atomic ownership与physical release |
| [route resource admission](./route-resource-admission.md) | 自动路线claim、插件附加资源、设备准备与release | Basic目标物理资源底线、原子union与grant生命周期 |
| [持久化](./persistence.md) | Basic documents/codec/restore | split pairing、strict decode、materialize |
| [测试](./testing.md) | Basic fixture/contract tests | isolation、assertion matrix、downstream验证 |

交界只引用owner页：例如Core frame shape不在Basic重复；Basic只在threading页定义消费顺序。issue内容不在spec/index复制。

## 模块验证

```bash
./gradlew :Core:test :Basic:test
./gradlew :Core:dokkaGeneratePublicationHtml :Basic:dokkaGeneratePublicationHtml
python3 ./.trellis/scripts/get_context.py --mode packages
git diff --check
```

API/platform bridge受影响再运行`./gradlew :BukkitPlugin:test :WebUI:test`；WebUI JSON shape变化再运行`npm --prefix WebUI/frontend test`。
