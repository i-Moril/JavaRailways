# Basic 列车纵向动力学契约

## 1. 状态、范围、触发条件与 Owner

- 状态：`CURRENT`。
- 触发：修改牵引/制动/阻力/坡度公式、手柄执行、人工驾驶、ATO actuator、重力注入或制动包络。
- Owner：`Basic`拥有平台无关的纵向动力学和手柄执行；`Core`只发布列车几何与物理运行方向；`SimpleATO`拥有目标速度规划；`BukkitPlugin`只解析配置和装配profile。
- 非目标：负速度倒车、轮滑、天气黏着、再生能量核算、连续轨迹优化器。

## 2. 模块工作方式

正常实时流程是：Bukkit从当前有效模型解析`TrainProfile`和逐车`CarProfile`，注入
`physics.yml`的`track.gravity`，然后构造Basic controller/actuator。人工控制把玩家选择的
P/N/B手柄交给`TrainDynamicsExecutor`；SimpleATO先归约许可速度，再由`HandleTrainActuator`选择一个配置中
存在的手柄。两者最终都只调用`TrainDynamics.step`，再把同一个`StepResult`的距离和post-step速度通过
`TrainController.moveControlledTrain`交给Core；不得绕回公开的distance-only `Train.moveTrainForward`而丢失本epoch速度。

隔离时刻表模拟在capture时冻结同一profile和重力，克隆世界中不得回查Bukkit renderer或live service。
详细行程预览也调用同一纯函数，但其“最大牵引/最大常用制动”驾驶策略仍是独立的粗略策略。

## 3. 内部机制与关键算法

`TrainDynamics.step`按以下顺序计算：制动建立比例 → 有效质量与当前速度 → 受黏着限制的牵引力 →
受黏着限制的制动力 → Davis阻力 → 逐车有符号坡度力 → jerk限制 → 梯形距离积分。

坡度输入是`sin(theta)`：正值表示当前物理运行方向上坡。实时输入必须读取
`Car.operatingDirection.y()`，禁止读取模型朝向`Car.direction.y()`。

`HandleTrainActuator`只考虑配置中的正作用P档、N和非EB制动档，并每Tick向目标档移动一级。正常停车上限是
最强非EB制动；只有目标为零且最强常用制动预演不能减速时才允许EB。每个候选只预演一次当前Tick，预演不得
修改executor state。

坡度制动包络把已扫描路线切成有限`BrakingGradeSegment`，用稳定最大常用制动力和
`a=(F_brake + m*g*grade)/m_effective`计算每段保证减速度；有利的Davis阻力不计入保证值。包络预先线性累计
`2*a*length`，距离查询二分定位后计算`v_in²=v_target²+work`。进入无法保证正减速度的分段前返回0。

## 4. 接口、数据结构与单位

```kotlin
fun TrainDynamics.step(
    state: TrainDynamics.State,
    config: TrainDynamics.TrainProfile,
    cars: List<TrainDynamics.CarInput>,
    handle: String,
    gravityMetersPerSecondSquared: Double,
    deltaSeconds: Double
): TrainDynamics.StepResult

class TrainDynamicsExecutor(
    train: Train,
    trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed
)

data class BrakingGradeSegment(val lengthMeters: Double, val grade: Double)
```

速度为m/s、加速度与重力为m/s²、距离为m、质量为kg、力为N、时间为s。`grade`是无量纲正弦值。
`TrainDynamicsExecutor.preview`不提交状态，`step`提交`State`和`lastResult`。

`HandleTrainActuator.serviceBrakeReactionTimeSeconds`必须覆盖最坏P→N→最强常用制动的逐级切换时间、
`brakeApplyTime`、`serviceDeceleration/brakeJerk`和规划余量；生产与隔离SimpleATO用该值建立同一安全曲线。

## 5. 不变量、生命周期、线程与版本

- profile数量必须与`train.cars`数量完全一致，顺序与`train.cars`一致。
- executor/controller/actuator是单列车、单physics worker拥有的有状态对象；profile和重力在安装后不可变。
- `RealisticManualController`提交的distance与speed必须来自同一个dynamics step；Core在同一epoch据此发布car/bogie速度。
- 换向只改变`operatingDirection`和列车head/tail语义，不改变`Car.direction`模型朝向。
- 重力只来自composition注入；Basic不得读取YAML或Bukkit singleton。
- `track.gravity`是schema 2的必填正数字段；它由旧`track.gravity-mps2`破坏性重命名而来，不保留runtime兼容读取。
- SimpleATO test source中的`KinematicTrainActuator`只可作为无真实车辆profile的纯规划/约束测试替身，production和
  isolated composition不得构造它；Basic main source不拥有该ATO测试策略。

## 6. 校验与错误矩阵

| 条件 | 结果 |
| --- | --- |
| 重力非有限或`<=0` | 构造/step立即`require`失败，不移动 |
| 车辆profile数量不匹配 | executor构造失败 |
| 速度或Tick时间非法 | actuator拒绝，不提交state |
| 没有非EB常用制动档 | 制动能力派生失败，controller不安装 |
| 包络某段保证减速度`<=0` | 对进入该段的目标fail-closed返回0许可速度 |
| ATO运行时动力学异常 | 走既有world Stall并freeze |
| no mission | SimpleATO仍输出零需求，速度上限不构成行车授权 |

## 7. 性能、复杂度与退化行为

一次动力学step和手柄预演成本为`O(cars × handles)`，配置手柄数是小型有界集合。路线坡度切片最多256段，
构造为`O(route samples)`，每个constraint查询为`O(log segments)`；不得为每个手柄做全路线仿真或引入通用求解器。
超过切片上限时，把余下路线合并为一个采用最不利下坡样本的分段。SimpleATO复用既有一次路线扫描；真实包络存在时，
扫描远端同时作为可制停边界，防止平坡horizon在已知下坡上不足。

## 8. Good / Base / Bad Cases

- Good：换向后模型外观不翻转，但同一坡道的`gradeForce`符号翻转。
- Base：平坡相同profile、手柄、重力下，人工和ATO executor得到完全相同的下一状态、距离和力诊断。
- Bad：SimpleATO直接按固定加速度改速度，或Bukkit controller自行复制一套`TrainDynamics`公式。
- Bad：用`Car.direction`计算坡度，导致反向运行仍按旧方向受重力。
- Bad：只用稳定最大制动距离，却遗漏手柄换档、制动建立和jerk反应时间。

## 9. 必需测试与断言点

- 重力变化：坡度力和黏着上限随重力变化；无黏着截断的平坡牵引与Davis阻力不变。
- 上/平/下坡：惰行加速度和制动允许速度顺序正确。
- 换向：`Car.direction`不变、`operatingDirection`反向，executor的坡度力符号翻转。
- executor一致性：相同step前状态下，人工/ATO包装结果等于直接`TrainDynamics.step`。
- manual publication：真实50 ms physics epoch的head位移、train frame speed及全部car/bogie frame velocity与同一次dynamics step一致。
- actuator：逐级P/N/B、最弱安全档、EB边界、preview不提交状态、反应时间覆盖换档/apply/jerk。
- 包络：分段边界、扫描截断、无正减速度fail-closed和256段上限。
- composition：生产和隔离均无`KinematicTrainActuator`，捕获后不访问live renderer/Bukkit。

## 10. 迁移、未决事项与 Wrong / Correct

迁移已删除Bukkit中的`RealisticManualController`实现，不保留runtime兼容alias。`physics.yml`从schema 1升级到2时，
使用`scripts/migrate_physics_config.py`先dry-run审查，再用`--apply`在保留`.bak`恢复点后把旧重力键单向改名。
未决事项只有未来是否把共享ATO primitive从Basic进一步拆分；在发生真实consumer变化前不得预先增加抽象层。

### Wrong

```kotlin
val grade = car.direction.y()
val gravity = 9.80665
```

### Correct

```kotlin
val grade = car.operatingDirection.y()
val result = TrainDynamics.step(
    state, profile, cars, handle, gravityMetersPerSecondSquared, deltaSeconds
)
```
