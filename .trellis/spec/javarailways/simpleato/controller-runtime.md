# SimpleATO controller runtime 契约

## 状态与范围

`work.art1st.javarailways.simpleato.controller.SimpleATO`是当前唯一具体自动驾驶controller owner。
它连同`SimpleAtoPublishedState`、mission queue、confirmed route windows、provider选择、station-call状态、
movement authority和owner-scoped constraint manager位于SimpleATO模块。Basic不保留同名controller、typealias、
factory facade或转发访问器。

Basic暂时提供`AbstractATO`、`AtoSafetyProfile`、actuator、mission/snapshot/constraint value type、route compiler
和world Stall coordinator。这是`SimpleATO -> Basic`的迁移期依赖，不代表Basic拥有具体controller运行状态；
共享primitive的后续归属必须在删除其Basic consumer时单独审查。

## 正常流程与内部机制

一次控制周期按以下顺序运行：

1. Bukkit或隔离仿真的`TimetableAtoFactory`创建一个controller并安装到目标Core train；
2. Dispatcher在serialized owner上提交带revision的mission、route window、station call、reverse或authority命令；
3. physics tick开始时controller只在边界drain已接受命令，形成一份active mission/route/provider状态；
4. controller构造一次perception，按owner原子替换当前constraints，并取所有候选中最严格速度；
5. Basic `AbstractATO`先执行invariant validation，再调用actuator并移动Core train；
6. 成功epoch结束时`AbstractATO`原子发布train snapshot、累计距离和controller value state，Dispatcher只读取这份publication。

Mission、constraint、provider和station-call的mutable集合都只存在于该controller实例，不在
`SimpleATOWorldData`或Basic创建第二份镜像。每列车每tick的route/perception扫描仍为线性；本次模块迁移不增加
扫描、缓存或调度阶段，也不改变legacy route allocator。

controller现在同时拥有该列车唯一的`TrainRestrictionConstraintAdapter`。admission client向adapter发布
immutable restriction snapshot；后续Dispatcher接线将在serialized owner上用当前stamp、同一冻结route binding及route
progress调用apply，adapter把结果原子替换进controller原有`ConstraintManager`。这不是第二份约束存储或第二个
制动器：最终仍由同一个`MinimumConstraintPlanner`与车辆、停车、跟车等constraint一起取最严格值。当前提交只完成
owner/enforcement接缝；在lookahead policy冻结并接入Dispatcher以前，生产运行尚不发布resource-admission restriction。

## Publication边界

`AbstractATO`把一次publication表示为protected immutable `PublishedAtoState`，并通过一次volatile reference
读取返回完整快照。SimpleATO必须只调用一次reader，再从该对象读取train snapshot、累计距离和controller state；
不得把字段拆成多次volatile读取，否则并发publication可能产生跨epoch混合。SimpleATO不能读取worker正在修改的
active字段，也不能改写Basic内部publication reference。

`SimpleAtoPublishedState`把这三个值和immutable controller state组合成Dispatcher可读取的快照。tick完成但
Core尚未调用commit/publication时，旧快照必须保持不变；publication成功后所有字段一起前进。

## Failure与禁止实现

- mission resolve失败或stale revision不改变active mission，也不误触发world Stall；
- physics invariant、provider或controller异常由同一Basic world coordinator fail-close并freeze；
- 不得在Basic恢复具体SimpleATO subclass、兼容alias或callback bridge；
- 不得让Bukkit直接更新active mission/constraint集合；
- 不得把本次ownership迁移和新frozen-route/admission wiring混在同一批。

## 必需测试

- `SimpleATOTest`覆盖mission boundary、constraint归约、provider切换、publication原子性、Stall与freeze；
- `StationCallRuntimeTest`覆盖停站状态与controller publication；
- Dispatcher、manual navigation和隔离仿真测试证明所有composition caller使用新owner；
- Basic测试独立编译，证明Basic main/test source均不依赖SimpleATO。

```bash
./gradlew :Basic:test :SimpleATO:test :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar
git diff --check
```
