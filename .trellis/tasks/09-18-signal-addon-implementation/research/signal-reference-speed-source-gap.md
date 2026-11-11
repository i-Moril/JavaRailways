# Research: 信号扫描参考速度来源与unlimited传递

后续纠正（2026-10-15）：`SUBGRADE`就是RailWand保存的线路设计/上级限速（`RailWand.kt:441,2633-2635,3103-3104`），不应把它描述成“不存在物理线路最高速度配置”。用户确认速度不在运行时变化，因此不需要速度事件/订阅；准备时读取既有metadata，编辑后沿原重准备即可。下方保留首次研究证据，当前缺口仅是给附属插件的只读值接线，不新增线路速度配置或使用时刻表Line代替。

- Query: 当前允许最大速度、无上限回退线路最高速度可复用哪些真实API；G不限速需经过哪些类型。
- Scope: internal；只读限定查询，不实施service/default cap、不运行Gradle。
- Date: 2026-10-15

## Findings

### 已有速度数据不等于现有共享信号API

1. **物理rail局部限速已有**：`Basic/.../rail/RailInfrastructureMetadataStore.kt:10-15,24-29`保存rail的`speedLimits`。`RailSpeedMetadata.kt:6-8,22-33`当前类型只有CURVE/SUBGRADE，最小值为effective limit，空集合结果null。不是世界/物理线路最高速度配置。
2. 写metadata会重建该rail的运行期速度transition（`RailInfrastructureMetadataStore.kt:26-29`）；`OperationalSpeedLimitSignalMaterializer.kt:17-21`对AB/BA各生成入口，空limits仍生成release。`OperationalSpeedLimitSignal.kt:11-29`的速度与列车无关，但这是Basic实现包对象，不是SimpleSignals当前公开服务读取字段。
3. **运营Line并没有maxSpeed**：`SimpleATO/.../api/line/Line.kt:20-25`只有ID、显示名、上下行站点与颜色。
4. **时刻表线路最高速度已有另一owner**：`SimpleATO/.../timetable/Timetable.kt:67-95`的`TimetableLineSpeedLimit`与`LineMaximumSpeedResolver`按service.lineId匹配timetable.lineSpeedLimits。这是时刻表/service上下文，不是rail→物理线路查询；多个运营Line可经过同一物理信号，不能擅自用于一台共享信号。
5. `TimetableDispatcher.kt:4841-4853`把上述值应用到每车ATO；还明确TODO跨线时须更新currentLineId重解。`controller/SimpleATO.kt:451-460,809-811`最终为每车external current-speed constraint。不得回读整套ATO最小约束结果作为共享信号输入，避免本信号反馈，并避免混入跟车/停车制动曲线。
6. `AtoPlanningContext.maximumSpeedMetersPerSecond`（`api/ato/AtoPlanner.kt:7-12`）是每车planner上下文，调用生产来源是actuator性能上限，不是共享信号处线路最高速度。Basic `TrainActuator.maximumSpeedMetersPerSecond`同样属于车辆能力。

### 当前公开边界缺口

- `BukkitPlugin/.../api/infrastructure/InfrastructureResourceService.java:36-67`提供exact topology、structural listener及单次operatingDirections读取；后续方法是资源/编辑/admission，没有static speed profile读取或speed change通知。
- `SimpleATO/.../api/signal/SignalEpochSource.kt:48-57,135-143`的输入/interest包括occupancy、continuation、admission、operating-direction、entry-clearance，没有速度变化字段。
- `Basic/.../rail/RailInfrastructureMetadataStore.kt:37-70`提供同步store读写，没有速度revision/通知。不能把一个metadata.put无条件当成拓扑变化，亦不能声称现有topology listener必然通知速度编辑。
- immutable topology的TrackEdge只包含连接、length与railSlices（`Core/.../topology/TopologyState.kt:210-216`），没有速度。
- `InspectionUpdateInput`（`SimpleATO/.../api/inspection/InspectionUpdate.kt:41-48`）有route/perception/declarations/ranges，不提供线路速度；公开RailwayObservation的speed字段是实际列车速度，不可替代允许最高速度。

### 最小接线建议（待归属确认，不是本研究已实现）

- 先澄清用户的“线路最高速度”是否为独立物理线路/轨道运行政策，而非时刻表内运营Line；现有代码不能决定两者等价。
- 若取物理rail政策，应在Basic既有infrastructure owner上补只读不可变速度subset与协调器间帧变更通知，复用当前interest/dirty路径；不让SimpleSignals访问mutable Basic store，不每信号每tick轮询。不擅自创造物理线路fallback默认值。
- 若用户明确选择运营Line，还需定义多个service/line共用信号时怎样合成及无列车请求时取哪条线；不能直接取首个service或当前最快/最慢列车。
- 当前logic扫描距离在`SignalPackLoader.kt:159-170`捕获为静态有限正数。若需随线路政策计算，应在低频准备阶段确定reference speed并重编前视计划，变化失效也要接入；仅给JS运行输入加一个速度字段不能扩大已有计划范围。
- 排除本信号输出应靠来源分离（静态基础设施/明确线路政策），不靠先求全局约束再按灯色或数值猜测扣除。

### G不限速的限定传递链

1. `signalpack/SignalLogicRuntime.kt:15,118-141`：SignalLogicOutput的Double与bridge slot/校验。当前null兼作“未调用”，新增无限制必须区别“调用了不限速”与“漏setter”。不接受Infinity进入JSON。
2. `runtime/SectionSignalState.kt:14-37`：SectionSignalOutput当前有限非负Double；Available有效限速与Unavailable强制0须保持可区分。
3. `signalpack/SignalPackEpochRuntime.kt:99-104,162-167`：输出转state，以及多个下游信号取最严格结果并投影JSON。不限速不能被`?: 0`误判失联/停车；多个下游中有限值优先，无值/Unavailable仍需明确0。
4. `block/FixedBlockInspectionSource.kt:108-128`：Available unlimited应不生成该信号速度restriction，但仍发布完整replacement；Unavailable/缺信号结果保持0。不能取消区间资源/授权/inspection声明。
5. `SimpleATO/.../api/restriction/TrainRestriction.kt:37-50`：RouteSpeedRestriction仍可保持有限非负Double，因为unlimited在信号投影端表示“不增加restriction”，无需把Infinity传播进通用ATO制动算法。

## Related Specs

- `.trellis/spec/simple-signals/backend/js-signal-rules.md`
- `.trellis/spec/javarailways/basic/speed-and-derail-safety.md`
- `.trellis/spec/javarailways/basic/rail-infrastructure.md`
- `.trellis/spec/javarailways/simpleato/timetable-model-and-validation.md`

## External References

无。仅核实仓库已有生产模型和公开API，不引入现实铁路参数。

## Caveats / Not Found

- 没找到可由SimpleSignals公开读取的world/physical-line全局最高速度，也没找到速度变更订阅；有限扫描fallback缺少明确owner/来源，需用户确认，不能凭空设定。
- 未实施任何代码、service、配置、schema、默认值或测试；unlimited第一批由另一implementer负责，本文件仅为研究证据。
- 同一工作区存在并行实施，以上为本次读取时现状，不代替该批完成后的复核。
