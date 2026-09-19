# Basic 可执行契约索引

## 范围与加载条件

本层由`Basic/`拥有：平台无关运营逻辑、ATO、Destination/Station/Depot/Line、curve/rail metadata、安全、route/resource、timetable/Dispatcher/simulation与Basic snapshot。Core物理与frame shape由[Core层](../core/index.md)定义；Bukkit/Paper entity、scheduler、viewer与model side effect由platform adapter拥有。

## 主题导航

| 文档 | 何时加载 | 唯一归属 |
| --- | --- | --- |
| [API 边界](./api-boundaries.md) | 改`basic.api.*`、Dokka、Core/Bukkit imports | 候选API与consumer边界 |
| [world runtime](./world-runtime.md) | 构造/查找`BasicWorldData`或world stores | world-local composition与RailObject lifecycle |
| [threading 与 publication](./threading-and-publication.md) | main↔physics command、ATO publication、Dispatcher顺序 | cross-thread handoff与world Stall |
| [ATO mission 与 perception](./ato-missions-and-perception.md) | mission、route/perception snapshot | revision、resolve、immutable perception |
| [ATO control 与 constraints](./ato-control-and-constraints.md) | ATO tick、actuator、speed/stop/authority | control skeleton与constraint math |
| [ATO providers](./ato-providers.md) | 注册provider/boundary | world-local factory、worker purity |
| [train operations](./train-operations.md) | door/model/announcement adapter | request/ticket/cancel/completion |
| [Destinations](./destinations.md) | 创建/migrate operational stop | canonical destination与derived WayPoint transaction |
| [Stations](./stations.md) | berth/call/dwell/allocation | exact berth、door gate、tail release |
| [Depots](./depots.md) | rolling stock、storage/reset | ledger、physical selection、reset transaction |
| [Lines](./lines.md) | line pattern/revision/planning | infrastructure line identity与shortest leg |
| [curve authoring](./curve-authoring.md) | Anchor/template/solver/measurement | stable token、SI单位、off-main build |
| [rail infrastructure](./rail-infrastructure.md) | metadata、split/merge/turnout | metadata ownership与Core topology同步 |
| [speed 与 derail safety](./speed-and-derail-safety.md) | speed signal/geometry threshold/derail request | operating limit与worker→main safety |
| [route planning](./route-planning.md) | shortest path/progress/compiler | authoritative route与stable refs |
| [resource allocation](./resource-allocation.md) | turnout/section/berth locks/manual resource | atomic ownership与physical release |
| [timetable model 与 validation](./timetable-model-and-validation.md) | authoring/runtime model、validator | execution authority与validation gate |
| [Dispatcher runtime](./dispatcher-runtime.md) | clocks/run state/station/depot/control | main-thread operational transitions |
| [simulation](./simulation.md) | detailed/isolated simulation | clone boundary、determinism、provider opt-in |
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
