# SimpleATO ATO policy runtime

## 状态、范围与正常流程

SimpleATO是ATO mission、constraint与world Stall诊断的唯一owner。它拥有`AtoSafetyProfile`、`AbstractATO`、`AtoPlanner`、
`ConstraintManager`及具体constraints、`AtoMission`/resolver、`MissionRouteLayout`和`AtoWorldCoordinator`。
旧`AtoProvider` factory/registry/boundary切换机制已经直接删除，不保留alias、facade、runtime fallback或替代抽象；
信号扩展只使用Stage 1 inspection与namespaced restriction合同。
恒加减速的`KinematicTrainActuator`只位于SimpleATO test source，不是Basic API或production fallback。

一次正常流程是：serialized owner解析WayPoint mission并生成完整legs → revision queue在physics boundary激活 → controller用一次
route/perception scan生成车辆、停车、跟车、授权与外部restriction constraints → `MinimumConstraintPlanner`取最严格速度 → Basic actuator
执行物理运动 → 成功epoch原子publication。resolver仍调用Basic通用`RoutePlanner`与route compiler；SimpleATO负责目标、方向、
mission连续性和选择结果，Basic算法不成为运营状态owner。

## World Stall与共享物理interlock

`SimpleATOWorldData`持有每world唯一`AtoWorldCoordinator`。controller、Dispatcher、Depot reset、
隔离仿真和Bukkit诊断全部解析这一份owner。coordinator首次Stall保存结构化报告并锁住Basic同world
`WorldSafetyInterlock`；后续失败不覆盖first-failure报告。reset同时清除报告、恢复coordinator并释放interlock。
因此manual controller仍fail closed，但Basic无需知道ATO报告或constraint类型。
`SimpleATOWorldData`在组合边界要求coordinator引用的必须是同一Basic world的exact interlock实例；
不允许ATO与手动控制各自锁定一份彼此隔离的latch。

## Mission与constraint机制

- mission request与legs防御性复制；resolver逐leg执行WayPoint resolve、Basic通用寻路和compile，任一失败不产生partial mission；
- `MissionRouteLayout`一次编译累计距离；重复rail/环线用先前progress选择非倒退occurrence；
- constraint manager按owner原子replace，拒绝同owner重复key，并线性取全部当前candidate的最小许可速度；
- signal inspection按已选冻结路线声明需要检查的来源，restriction adapter把插件的namespaced输出作为只会收紧的constraint并入同一个manager；
- controller没有可切换provider状态，也不通过RailObject边界替换整套信号逻辑。

## 失败、复杂度与测试

mission resolve和stale revision是typed rejection，不Stall；controller/invariant异常Stall并freeze。mission route
编译对segment数为`O(S)`，每次position投影当前实现扫描compiled segments为`O(S)`；constraint归约为`O(C)`。
不得逐tick重新寻路或累计旧constraint。

```bash
./gradlew :SimpleATO:test --tests '*AtoPolicyTest' --tests '*AtoMissionTest' \
  --tests '*MissionRouteProgressTest' --tests '*SimpleATOTest'
./gradlew :Basic:test
```
