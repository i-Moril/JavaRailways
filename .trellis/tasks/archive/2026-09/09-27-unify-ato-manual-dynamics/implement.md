# 实施计划：统一 SimpleATO 与人工驾驶的列车动力学

## 1. Core：明确物理运行方向

- 在公开的 `Car` 合同中增加“当前物理运行方向”，与渲染/模型朝向 `direction` 分离。
- 在 `CoreCar` 中实现并维护该语义，列车换向时更新物理运行方向，但不改变既有渲染朝向语义。
- 增加正向、反向坡度方向测试，并覆盖换向后的状态发布边界。

验证：

```bash
./gradlew :Core:test --tests '*CoreTrain*Reverse*' --tests '*CoreTrainPublicationBoundaryTest'
```

## 2. Basic：动力学设置与共享手柄执行器

- 删除列车动力学中的硬编码重力常量，改为由组合层注入 `physics.yml` 的 `track.gravity`。
- 在 Basic 中实现有状态的共享手柄执行器，并提供不会改变状态的预演接口，统一人工驾驶与 ATO 的实际计算路径。
- 将 `RealisticManualController` 的列车物理计算迁入 Basic；Bukkit 只保留玩家输入、界面、会话和配置装配。
- 将现有人工驾驶动力学测试从 Bukkit 迁到 Basic，并补充重力、坡度和反向运行覆盖。

验证：

```bash
./gradlew :Basic:test --tests '*TrainDynamics*' --tests '*RealisticManualController*'
```

完成这一阶段后形成首个可回退点：公共动力学已归位，但 ATO 尚未切换。

## 3. SimpleATO：使用真实动力学执行器

- 新增基于共享动力学的 ATO 执行器，将目标速度转换为逐级变化的离散手柄位。
- 正常控制只使用配置允许的非紧急制动级位；紧急制动仅用于明确的失效安全边界。
- 从实际列车动力学配置派生 ATO 所需的制动能力，不再依赖与真实手柄路径脱节的运动学常量。
- 增加加速、惰行、常用制动、手柄逐级变化和紧急制动边界测试。

验证：

```bash
./gradlew :SimpleATO:test --tests '*Actuator*' --tests '*SimpleATO*'
```

## 4. 有界的前方坡度制动包络

- 在 Basic 中提供平台无关的坡度切片和制动包络计算。
- SimpleATO 从已扫描路线中提取有限数量、有限距离的前方坡度切片；同一区间采用最不利下坡值。
- 可用减速度使用同一套制动配置、重力、黏着和非紧急最大制动级位计算；为保持保守且简单，不把有利的 Davis 阻力计入保证值。
- 对限速变化、停车点、跟车和行车许可边界执行一次反向 `v²` 传播；单元测试或缺少路线剖面时保留常坡度退化路径。
- 增加下坡、平坡、上坡、扫描截断、零/非法减速度失效关闭，以及线性遍历复杂度测试。

验证：

```bash
./gradlew :Basic:test --tests '*AtoConstraint*' --tests '*BrakingEnvelope*'
./gradlew :SimpleATO:test --tests '*SimpleATO*'
```

## 5. Bukkit：组合装配与人工控制职责清理

- 将人工控制器的引用和构造切换到 Basic 中的实现。
- 删除 Bukkit 自有的列车物理控制器，仅保留输入映射、界面、会话、YAML 读取和动力学配置装配。
- 将 `track.gravity` 传入共享动力学，删除旧 `track.gravity-mps2`，并把 `physics.yml` schema 升级到2。
- 生产环境中的 SimpleATO 改用真实动力学执行器。
- 更新严格配置测试，确认既有配置字段和默认值保持不变。

验证：

```bash
./gradlew :BukkitPlugin:test --tests '*RealisticManual*' --tests '*PhysicsConfig*' --tests '*SimpleATO*'
```

## 6. 隔离模拟与详细模拟一致性

- 隔离模拟快照中携带同一重力设置。
- 克隆列车上的 SimpleATO 使用与生产路径一致的真实动力学执行器。
- 详细模拟配置使用同一重力来源和列车动力学配置。
- 增加生产、隔离模拟和详细模拟在相同初始状态下的关键行为一致性测试。

验证：

```bash
./gradlew :Basic:test --tests '*Simulation*'
./gradlew :SimpleATO:test --tests '*Isolated*'
./gradlew :BukkitPlugin:test --tests '*Isolated*'
```

## 7. SimpleATO 安全回归

- 生产级测试夹具改用真实动力学执行器；运动学执行器只保留在范围明确的纯控制器单元测试中。
- 回归限速、车站停车、行车许可终点、跟车、上下坡和无任务等场景。
- 验证下坡提前制动、上坡不过度保守，并保持既有停车容差和失效安全行为。

验证：

```bash
./gradlew :SimpleATO:test
./gradlew :BukkitPlugin:test --tests '*SimpleATO*'
```

## 8. 规范与完整质量门

- 更新 Core 的方向语义规范、Basic 的 ATO/模拟规范、SimpleATO 的运行时规范，以及 Bukkit 的职责边界规范。
- 执行所有受影响模块的测试、打包、文档和依赖边界检查。

最终验证：

```bash
./gradlew :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar
./gradlew dokkaGenerate
./gradlew packagesContext
git diff --check
```

## 评审门槛

- Basic 和 SimpleATO 不得引入 Bukkit 依赖。
- 生产与隔离模拟不得再构造 `KinematicAtoActuator`。
- 不得新增重复的重力配置；`physics.yml` 只允许本次明确升级到schema 2。
- `Car.direction` 继续表示渲染/模型朝向；坡度计算必须使用新的物理运行方向。
- 不覆盖或回退工作区中与本任务无关的现有修改。
