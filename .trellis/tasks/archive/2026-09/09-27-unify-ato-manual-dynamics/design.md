# 技术设计：统一 SimpleATO 与人工驾驶的列车动力学

实现后的权威可执行合同见
[`Basic 列车纵向动力学契约`](../../spec/javarailways/basic/train-longitudinal-dynamics.md)；本文件只记录本任务的跨模块设计与迁移范围。

## 1. Ownership 划分

可复用的纵向动力学模型与手柄执行机制属于 `Basic`，因为它们是在 Core 列车几何之上的平台无关控制能力。`Core` 只拥有该模型所需的物理事实：每节车在当前运营方向上的已发布前进方向。`SimpleATO` 拥有目标速度规划和自动手柄选择。`BukkitPlugin` 拥有 YAML 解析、玩家输入、模型/profile 解析和 composition。

目标依赖方向如下：

```text
Bukkit profile/配置适配 ─────┐
                            ├─> Basic 手柄动力学 ─> Core 列车移动
SimpleATO 手柄策略 ──────────┘
```

Basic 和 SimpleATO 均不得依赖 Bukkit 类型。

## 2. Core 物理运行方向合同

在 Core `Car` 公开 API 中增加只读的 `operatingDirection`。它表示正的列车移动距离会让该车前进的归一化方向。`Car.direction` 继续表示渲染/模型朝向，不改变原有语义。

`CoreCar` 根据已发布换向状态派生该值：正常方向使用 `direction`，换向后使用 `-direction`。现有 physics 换向 epoch 本来就只发布方向变化、不执行移动，因此下一次移动 epoch 能读到正确符号。

这会修复已确认的缺陷：当前 `RealisticManualController` 使用 `car.direction.y()`，但 Core 在换向时保留渲染朝向，导致坡度力仍按旧运行方向计算。

## 3. Basic 纵向动力学

### 3.1 重力

删除 `TrainDynamics` 内部的私有重力常量，改为使用经过校验的平台无关设置值 `gravityMetersPerSecondSquared`，或把该值作为显式 step 参数。牵引黏着上限、制动黏着上限和坡度力全部使用注入重力；平坡 Davis 阻力不依赖重力。

`physics.yml` 使用必填正数 `track.gravity`。这是从 `track.gravity-mps2` 的破坏性重命名，因此该独立配置边界升级到 schema 2；开发期不保留旧键runtime兼容。Bukkit composition 把该值传给所有实时人工/ATO 动力学执行器，并在创建隔离模拟快照时捕获同一个值。

### 3.2 共享执行器

在 Basic 中围绕现有纯函数 `TrainDynamics.step` 建立有状态执行器：

- 持有 `TrainDynamics.State` 和最近一次 `StepResult`；
- 接收 `TrainDynamics.TrainProfile`、按车辆顺序排列的 `CarProfile`、重力、当前列车速度、所选手柄和 Tick 秒数；
- 实时列车按 `Car.operatingDirection.y()` 构造逐车输入；
- 向人工或 ATO 调用方返回相同的速度、距离、力、加速度和制动状态；
- 提供不修改状态的纯预演方法，供自动手柄选择使用。

人工驾驶控制器改为 Basic 中包装该执行器的轻量 `TrainController`。删除 Bukkit 中拥有动力学职责的控制器实现，不保留兼容 facade；Bukkit 输入、管理器和命令改为构造 Basic 控制器并传入平台解析出的 profile。

## 4. SimpleATO actuator 与手柄策略

在 Basic 增加基于真实动力学的 `TrainActuator` 实现。它接收实时列车、不可变的列车/车辆 profile、注入重力和初始速度。生产环境与隔离环境的 SimpleATO factory 都改为构造该 actuator，不再使用 `KinematicTrainActuator`。

每个 Tick 从同一个 step 前状态预演配置的手柄：

1. 目标速度高于当前速度时，按作用从弱到强考虑 N 和所有牵引档。
2. 目标速度低于当前速度时，按作用从弱到强考虑 N 和所有非紧急制动档。
3. 选择能够安全接近目标且控制作用最小的候选；弱档与强档结果同样安全时，不得选择强档。
4. 评分完全相同时保持当前手柄，降低档位抖动。
5. 配置中最强的非 `EB` 制动作为正常常用制动上限。
6. 只有当目标速度为零、列车仍在移动，并且最强常用制动预演也无法产生任何减速度时，才使用 `EB`。World Stall 继续保持现有瞬时 freeze 合同。

actuator 保存已选手柄和最近一次力学结果用于诊断，但不持久化。

`KinematicTrainActuator` 可以保留为小型、确定性的规划器/约束单元测试替身，但生产和隔离 composition 均不得继续使用它。

## 5. 简化的前方坡度制动包络

SimpleATO 当前所有约束都使用单一常数减速度公式。本次只替换允许速度曲线的计算方法，不增加轨迹优化器。

每次既有路线扫描完成后：

1. 把已经选定的前方 `RailSegment` 列表转换成连续坡度分段。每段采样端点，并按固定最大间距补充有限采样点；该分段采用样本中最不利、即最下坡的 tangent Y。
2. 使用与 `TrainDynamics` 相同的最强非 `EB` 手柄、黏着限制、车辆质量和注入重力，计算稳定常用制动力。
3. 每个分段的保守可用减速度为“常用制动力 + 有符号坡度力”除以有效质量。Davis 阻力有助于减速，因此不计入保证值。
4. 制动建立时间和 jerk 通过既有反应距离加上派生的 actuator 延迟保守处理，不做迭代前向仿真。
5. 对距离为 `d` 的约束，把坡度分段截断到 `d`，从目标速度开始执行一次反向积分：`v_in² = v_out² + 2 × a_segment × length`。
6. 如果任一分段无法保证正的常用制动减速度，则在进入该分段前返回 fail-closed 的零许可速度；actuator 仍保留前述 EB 异常边界。

`CurrentSpeedLimitConstraint` 仍是即时速度上限。前方限速、停车、跟车、移动授权和路线终点约束使用同一个坡度感知包络。没有提供路线包络的纯约束单元测试可以继续使用原常数减速度 fallback。

该算法对有限坡度分段做一次线性扫描，复用 SimpleATO 已有路线扫描，不包含求解器、搜索或逐手柄全路线模拟。

## 6. Composition 与模拟

### 6.1 生产环境

`RailWorldFactory` 解析列车当前生效的 pinned/live 控制 profile 和每节车 profile，读取 `physicsConfig.track.gravityMetersPerSecondSquared`，构造 ATO 制动设置和真实动力学 actuator。

### 6.2 隔离时刻表模拟

`IsolatedTimetableSimulationSnapshot` 在现有不可变 `SimulationControlProfile` 之外捕获重力值。隔离 factory 使用克隆列车和已捕获 profile 构造同一个真实动力学 actuator；捕获完成后不得查询渲染器或 Bukkit 服务。

### 6.3 详细行程预览

`DetailedTrainTravelSimulator` 已经调用 `TrainDynamics`。其输入 profile 增加重力并使用同一注入值。现有“最大牵引/最大常用制动”的粗略驾驶策略不在本次重构范围内，本次只统一力学公式。

## 7. 兼容性与持久化

- 不改变任何持久化文档结构。
- `physics.yml` schema 升级到2，writer/default resource只使用`track.gravity`；不接受旧version或旧键。
- 不为 Bukkit 原控制器类保留 runtime compatibility alias。
- 现有模型包 DTO 继续实现 Basic profile 接口，YAML shape 不变。

## 8. 失败处理与回滚

- profile 缺失或车辆数量不匹配时，仍在安装控制器前失败。
- 非有限或非正重力既在现有 physics 配置边界拒绝，也在平台无关设置值中再次拒绝。
- profile 或动力学异常继续走现有 SimpleATO world Stall/freeze 路径。
- 因为不修改持久化格式，可以通过恢复运动学 factory wiring 和 Bukkit 人工控制器完成代码回滚。

## 9. 已知边界

坡度 profile 是对当前已扫描 active route 的有界保守采样，不是对任意用户轨道几何的连续解析证明。本任务不宣称铁路安全认证；它保证运营控制器不再完全忽略已知前方下坡，也不再假设强于车辆 profile 的制动能力。
