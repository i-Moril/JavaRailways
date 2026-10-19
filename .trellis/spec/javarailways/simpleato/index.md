# SimpleATO 可执行契约索引

## 范围与加载条件

本层描述处于迁移期的 `SimpleATO/` Gradle 模块。模块已经建立，当前拥有每世界唯一的 `TimetableDispatcher`和`AtoWorldCoordinator`，以及Destination、Station、Depot/rolling-stock ledger、Line、timetable model/store/extensions/persistence、ATO安全参数/mission/constraint policy、具体`SimpleATO` controller、checkpoint与隔离仿真运行时、手动导航命令、临时时刻表生成、时刻表JSON展示，以及第一批外部列车限制公开契约与约束适配器。production与隔离仿真Dispatcher均只使用Basic通用route admission；旧allocator、Confirmed Route Window、null-provider fallback以及`AtoProvider`切换机制已删除。Basic提供公共轨道方向，并保留自动/手动共用的actuation、安全包络和route/perception snapshot primitive。独立源码模块`SimpleATOBukkit`已建立adapter library边界，并由JavaRailways主`BukkitPlugin`直接组合、嵌入同一部署JAR；它不拥有第二个Bukkit plugin lifecycle。production world composition、tick和snapshot仍由主插件统筹，后续只将已有明确SimpleATO producer/consumer的平台适配逐批迁入adapter模块。因此本层状态仍是`TRANSITIONAL`，不能据此声称全部Stage 2平台拆分已经完成。新增或迁移ATO、运行方向、运营对象、时刻表、运营寻路、资源检查调度或列车限制仲裁时，必须加载本层。固定闭塞、保护区域和具体联锁安全校验属于独立信号系统插件，不属于SimpleATO或Basic的通用领域模型。

SimpleATO 只拥有平台无关的运营模型、ATO/ATP决策、资源检查调度和最严格限制归约。物理铁路图和列车几何由Core拥有，平台无关的纵向动力学与手柄执行由Basic拥有；资源身份校验、冻结路线的不可删除物理资源底线、物理占用、原子 claim、设备准备与尾部释放由 Basic 拥有。每一个独立信号系统由自己的Java/Kotlin插件拥有固定闭塞、保护区域、联锁规则、信号显示及可选JS/YAML配置。`SimpleATOBukkit`只作为嵌入主插件的SimpleATO平台适配模块，不为独立信号插件提供通用运行时或取得其领域ownership。

轨道长期允许运行方向不属于 SimpleATO：唯一存储、公共值和持久化归[Basic方向契约](../basic/operating-directions.md)。
SimpleATO 规划时读取公共配置并组合其他选路限制；目标选择、车次行驶方向和任务连续性仍由本模块负责。

## 主题导航

| 文档 | 何时加载 | 唯一归属 |
| --- | --- | --- |
| [运营选路与行车授权](./operational-routing-and-authority.md) | 改 mission、运营 overlay、冻结路线、资源准入调用或约束合成 | SimpleATO 选路、准入客户端、单一列车保护管线 |
| [停车目标与授权终点分离](./terminal-route-completion.md) | 补全授权物理后续路径、区间内停车与后缀复用 | SimpleATO中立补全，信号插件决定完整区间 |
| [ATO policy runtime](./ato-policy-runtime.md) | 改mission、constraint、world Stall或route progress | SimpleATO唯一policy owner及Basic safety interlock桥接 |
| [Controller runtime](./controller-runtime.md) | 改SimpleATO physics tick、mission/constraint状态或publication | 具体`SimpleATO` controller及其唯一可写运行状态 |
| [入口整列车净空](./entry-clearance.md) | 改中立入口要求、当前停车约束证明及信号交接 | SimpleATO 单次感知、共享保守长度及入口停车检查 |
| [响应式信号epoch](./reactive-signal-epochs.md) | 改监测区更新路由、信号callback或同步restriction publication | 平台无关source host、校验、fail-closed epoch顺序 |
| [Dispatcher runtime](./dispatcher-runtime.md) | 改 timetable clock/run state、车次推进、车站/车辆段动作或恢复 | `SimpleATOWorldData.dispatcher` |
| [Timetable model 与 validation](./timetable-model-and-validation.md) | 改 authoring/runtime model、validator、store或extension | execution authority与validation gate |
| [Timetable simulation](./simulation.md) | 改 detailed/isolated simulation | 共同领域装配、clone boundary、determinism、detached prepare与目标安装 |
| [Destinations](./destinations.md) | 创建/migrate operational stop | canonical destination与derived WayPoint transaction |
| [Stations](./stations.md) | berth/call/dwell/allocation | exact berth、door gate、tail release |
| [Depots](./depots.md) | rolling stock、storage/reset | ledger、physical selection、reset transaction |
| [Lines](./lines.md) | 改line pattern/revision/planning | infrastructure line identity、store与shortest leg |
| [SimpleATO 持久化](./persistence.md) | 改Destination、Line/Dispatcher、SimpleATO infrastructure/runtime 文件或恢复顺序 | `SimpleATOInfrastructureSnapshotV1`、`SimpleATORuntimeSnapshotV1` |

后续为信号脚本建立owner spec；在对应接口冻结前，不把概念草图写成已存在API。

## 迁移期规则

- 当前仍位于 `Basic/` 的共享物理actuation、制动安全和snapshot primitive继续遵守[Basic规范](../basic/index.md)，但不得据此新增长期属于Basic的运营ownership。
- 每一批迁移同时移动 contract、实现、测试与 composition；不得让 Basic 和 SimpleATO 同时成为同一运行状态的可写 owner。
- SimpleATO 只提交自己选择的 topology-bound frozen route，不提交可替代物理底线的第二份资源清单。信号插件可以增加注册资源；Basic 的验证与 claim 合同见[路线资源准入](../basic/route-resource-admission.md)。

当前 timetable owner slice 使用 `work.art1st.javarailways.simpleato.timetable`：

- `ManualNavigationParser` 保持严格 token/JSON 语义；
- `ManualNavigationTimetableFactory` 生成 SimpleATO 的 `Timetable` model，并从同world `SimpleATOWorldData.destinations`读取站台角色；
- `TimetableDumper` 按原 shape 展示 SimpleATO timetable；
- Bukkit command 是该 slice 的 composition consumer，Basic production 不反向依赖 SimpleATO。

Destination、Station、Depot、rolling-stock ledger、Timetable与Dispatcher已经连同store、persistence、测试和composition一起迁移，Basic没有保留别名、转发或镜像store。Destination service只把通用route anchor投影到Basic WayPointStore，Station berth投影到共享RailObjectStore，Depot role则直接维护同一SimpleATO DepotStore；这些derived records不构成第二个Destination owner。

## 质量检查

```bash
./gradlew :Core:test :Basic:test :SimpleATO:test
./gradlew :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava
python3 ./.trellis/scripts/get_context.py --mode packages
git diff --check
```

`SimpleATO` 当前只将 `work.art1st.javarailways.simpleato.api.*` 加入根 Dokka/Javadoc publication。迁移期内部 timetable、routing 和 restriction adapter package 必须保持 suppression，不能因文档聚合而意外形成兼容承诺。
