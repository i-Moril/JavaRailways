# AI Task D：ATO 与车辆动力学一致化

> 状态：Needs product decision
> 类型：behavior change，不是已确认 bug
> 范围：SimpleATO actuator 与 braking prediction；不重构整个控制栈

---

## 1. 前置决策

执行代码修改前，项目必须确认：

> SimpleATO 是否应受与手动驾驶相同的质量、牵引力、坡度、黏着、阻力、jerk 和制动建立时间约束？

若答案是否定的，即 ATO 有意采用游戏化恒加减速模型，则本任务只记录差异并关闭，不应强行统一。

若答案是肯定的，再执行下述最小方案。

---

## 2. 当前事实

当前不是“三套完全不同动力学”：

- `RealisticManualController` 使用 `Basic.control.TrainDynamics.step()`。
- `DetailedTrainTravelSimulator` 也使用同一个 `TrainDynamics.step()`。
- `SimpleATO` 通过 `KinematicTrainActuator` 使用固定加速度和固定服务减速度。

所以真正需要替换的是 ATO actuator 边界，不需要先设计完整：

```text
Planner -> DriverIntent -> Driver -> Dynamics -> Vehicle
```

`TrainActuator` 已经提供了可替换接口，应优先沿用。

---

## 3. 目标（仅在确认一致化后）

1. 新增 dynamics-backed `TrainActuator`。
2. SimpleATO planner 和 constraint system 保持现状，仍输出目标速度。
3. actuator 把目标速度转换为 traction/coast/service-brake intent，并调用共享 `TrainDynamics.step()`。
4. ATO 的 braking prediction 对实际 dynamics 保守，不能承诺车辆无法达到的停车距离。
5. 手动驾驶和时刻表详细仿真不复制新公式。

---

## 4. 非目标

- 不删除 `KinematicTrainActuator`；隔离仿真或兼容配置仍可使用。
- 不重写 SimpleATO route、constraint、station call、dispatcher。
- 不持久化 live ATO/controller；恢复语义仍为 `RECOVERY_REQUIRED`。
- 不实现曲线阻力、电空混合制动、逐车车钩力或轮轨刚体。
- 不要求 planner 进行昂贵的实时全轨迹最优化。

---

## 5. 实施任务

## D1. 锁定现有行为基线

增加测试矩阵：

- 平直、上坡、下坡；
- 空载/满载或不同质量 profile；
- 高/低 adhesion；
- 不同 brake buildup 和 jerk；
- 相同列车 manual 与 ATO 等价 handle intent。

先证明：

- 手动和详细仿真对同一 `TrainDynamics.State/Profile/Input/handle/dt` 结果一致；
- 当前 Kinematic ATO 不响应上述车辆参数差异。

这一步记录行为差异，不把 Kinematic 现状写成测试失败。

## D2. 新增 DynamicsTrainActuator

建议状态：

```kotlin
class DynamicsTrainActuator(
    private val trainProfile: TrainDynamics.TrainProfile,
    private val carInputProvider: () -> List<TrainDynamics.CarInput>,
    private val handlePolicy: AtoHandlePolicy
) : TrainActuator
```

`handlePolicy` 最小只需做：

- 当前速度明显低于 target：选择配置的 service power handle；
- 接近 target：coast；
- 高于 target 或需要停车：选择 service brake handle；
- freeze：返回零移动并明确 state 处理，不假装执行一次物理急停。

要求：

- actuator 持有 `TrainDynamics.State`，包括 acceleration/brakeScale 连续性。
- 每个完整 physics epoch 使用 Coordinator 已应用上限后的 `deltaTimeSeconds`；不补跑墙钟 catch-up tick。
- car grade 来自本 epoch 已发布/冻结的列车输入，不读取 Bukkit。
- `maximumSpeedMetersPerSecond` 与 TrainProfile 一致。
- 同样输入具有确定性。

不要求第一版支持连续 0..1 power/brake；可复用现有 notch profile。

## D3. 让 SimpleATO 可选择 dynamics actuator

- Bukkit live world 使用 model package 的 train/car control profile 构建 actuator。
- isolated simulation 使用同一 profile 构建，不能换成另一套质量或阻力参数。
- 配置若保留 kinematic/dynamics 两种模式，必须显式选择并进入 diagnostics；不得静默 fallback。
- profile 缺失或 car 数不匹配时明确拒绝启动 ATO，而不是使用零质量/默认力。

## D4. 校准保守制动预测

当前 `SimpleATO.serviceBrakingDistanceMeters()` 基于 safety profile 的固定减速度。换用 dynamics 后，预测至少需要覆盖：

- brake buildup；
- jerk；
- 当前速度；
- 最不利允许坡度或当前 route grade；
- adhesion/profile 下实际 service brake 能力。

允许的第一版方法：

- 使用共享 `TrainDynamics.step()` 做固定步长前向制动仿真；
- 缓存按 profile/速度/grade bucket 的结果，若 profiler 证明需要；
- 加明确 safety margin。

关键不变量：

```text
predicted stopping distance >= same assumptions 下 dynamics 实际 stopping distance
```

不得为了贴近时刻表而让预测非保守。

## D5. 一致性测试

- 等价 manual/ATO handle intent 的单步结果一致。
- 上坡/下坡、质量和 adhesion 改变会影响 ATO 实际加减速。
- ATO 不超过 profile max speed。
- brake buildup/jerk 在多 tick 连续。
- 对测试矩阵，预测停车距离不小于实际停车距离。
- fixed 50ms 与 Task B 将较大墙钟 delta clamp 到 200ms 后的单 epoch 结果分别有确定、受测的推进语义。
- DetailedTrainTravelSimulator 继续调用共享 `TrainDynamics`，不复制 actuator 公式。

---

## 6. 推荐提交顺序

```text
1. test: document current kinematic-vs-dynamics behavior
2. feat: add DynamicsTrainActuator behind existing interface
3. test: manual/ATO equivalent-intent parity
4. feat: wire live and isolated ATO profiles explicitly
5. test: conservative braking prediction matrix
6. refactor: dynamics-backed stopping-distance estimator
7. docs: describe kinematic/dynamics mode and limitations
```

---

## 7. 验证

```bash
./gradlew :Basic:test
./gradlew :BukkitPlugin:test
./gradlew :Core:test :Basic:test :BukkitPlugin:test
./gradlew build
```

建议测试：

- `DynamicsTrainActuatorTest`
- `AtoManualDynamicsParityTest`
- `AtoBrakingPredictionConservatismTest`
- isolated/live profile parity test

---

## 8. 完成定义

### 产品决定保留 Kinematic

- [ ] 决策和原因写入 ADR/架构文档。
- [ ] 文档明确 ATO 与手动车辆性能不同。
- [ ] 本任务不产生无必要的控制栈重构。

### 产品决定统一 Dynamics

- [ ] SimpleATO live/isolated 使用显式 dynamics-backed actuator。
- [ ] 手动、ATO 和详细仿真共享 `TrainDynamics` 核心公式。
- [ ] ATO 对坡度、质量、adhesion、jerk 和 brake buildup 有响应。
- [ ] braking prediction 在测试矩阵中保持保守。
- [ ] 无 silent fallback，无复制的第四套动力学。
- [ ] 所有相关测试和 `./gradlew build` 通过。
