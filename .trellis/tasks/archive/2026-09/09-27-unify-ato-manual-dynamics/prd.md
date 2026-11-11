# 统一 SimpleATO 与人工驾驶的列车动力学

## 目标

让人工驾驶、生产环境 SimpleATO 和隔离 SimpleATO 使用同一套平台无关的列车纵向动力学实现，同时把玩家输入和平台适配职责保留在 Bukkit。纵向动力学复用现有 `physics.yml` 重力参数，并确保坡度力始终按照列车当前物理运行方向计算。

## 背景与已确认事实

- `Basic.control.TrainDynamics` 已经拥有纯动力学公式，包括牵引力、制动力、Davis 阻力、黏着限制、jerk、有效质量和逐车坡度力。
- `BukkitPlugin.control.RealisticManualController` 当前同时拥有实时手柄状态，并负责从 Bukkit 模型包配置组装 `TrainDynamics.CarInput`。
- 生产环境和隔离环境的 SimpleATO 当前使用 `KinematicTrainActuator`：它直接按固定加减速度追踪目标速度，不使用手柄、车辆牵引力、阻力、黏着、jerk 或坡度。
- `physics.yml` 原来使用 `track.gravity-mps2`，但该命名不够直接，且 `TrainDynamics` 仍另外硬编码 `9.80665`。
- `Car.direction` 是渲染/模型朝向；Core 在列车换向时刻意保持其语义不变。`RealisticManualController` 当前直接把 `car.direction.y()` 当作前进坡度，因此正向移动方向改变后，坡度符号仍可能保留旧值。
- 2026-09-25 的 SimpleATO 模块迁移提交 `dceb1a25` 是 99% 的文件移动；运动学 actuator 早于该迁移，并且在 SimpleATO 首次实现提交 `79bec3da` 中已经存在。

## 需求

- R1. 把可复用的手柄状态和纵向动力学执行机制放入平台无关的 owner：`Basic`；需要实时列车几何时，只使用 Core 公开 API。
- R2. Bukkit/Paper 输入、玩家/座位/钥匙生命周期、YAML 解析和 composition 保留在 `BukkitPlugin`；Basic 和 SimpleATO 不得依赖 Bukkit 配置类型。
- R3. 在列车状态、profile、各车参数、手柄和重力相同的条件下，人工驾驶和 SimpleATO 必须执行同一个 `TrainDynamics` 公式。
- R4. 生产环境 SimpleATO 必须把约束归约后的目标速度转换为配置中存在的牵引、空挡或制动手柄，再通过共享动力学机制执行该手柄。
- R5. 隔离时刻表 SimpleATO 必须使用同一条动力学路径和已捕获的 profile；隔离世界内禁止访问实时 Bukkit、渲染器或模型服务。
- R6. 纵向动力学必须通过 composition 接收 `physics.yml` 中必填且为正数的 `track.gravity`；删除旧 `track.gravity-mps2`，并把独立 `physics.yml` schema 升级到 2，不保留 runtime 兼容读取。
- R7. 坡度使用每节车当前物理前进方向的正弦分量。列车换向后，坡度力符号必须翻转，而渲染/模型朝向保持原语义。
- R8. 约束和停车计算必须使用复杂度有界、感知前方坡度的制动包络。该包络使用同一组列车/车辆 profile、注入重力、最强常用制动和当前已扫描路线；替换 actuator 不得削弱停车、跟车、移动授权或前方降速约束。
- R9. 解析控制 profile 时，必须保持当前列车模型 snapshot/update-policy 语义。
- R10. 不为旧开发数据增加 runtime compatibility path；预计不修改任何持久化 schema。
- R11. SimpleATO 使用渐进离散手柄策略：通过同一个纯 `TrainDynamics` step 预演配置中的 P/N/B 档，选择能够安全追踪许可速度且作用最弱的手柄；B7 或配置中最强的非紧急制动是正常常用制动上限；EB 只用于有明确条件的 fail-safe。
- R12. 坡度感知制动必须保持简单且有界：从已扫描路段构造有限的分段路线 profile，并只执行一次反向 `v²` 积分。不得引入迭代优化、通用数值求解器或逐候选档位的全路线仿真。

## 验收标准

- [ ] AC1. 对于相同状态、profile、各车参数、手柄和重力，共享的 Basic 执行器为人工与 ATO 调用方产生完全一致的下一速度、距离、加速度、制动状态和各项力诊断。
- [ ] AC2. 生产环境 SimpleATO 不再构造 `KinematicTrainActuator`，而是使用已解析的列车/车辆 profile 和共享动力学执行器。
- [ ] AC3. 隔离时刻表 SimpleATO 同样使用共享执行器，并且不访问实时平台对象。
- [ ] AC4. 修改 `track.gravity` 会按比例改变纵向坡度力；平坡上的阻力和牵引结果不受重力变化影响，黏着上限则按重力变化。
- [ ] AC5. 解除牵引后，上坡列车比平坡列车更快减速；下坡列车减速更慢或发生加速，结果遵循同一阻力模型。
- [ ] AC6. 在同一坡道上切换物理运行方向后，坡度力符号翻转；自动化测试能够复现原 `car.direction.y()` 缺陷并证明修复有效。
- [ ] AC7. 现有 SimpleATO 停车、移动授权、跟车和限速测试在生产 actuator 合同下通过；新增测试证明下坡允许接近速度低于平坡、上坡高于平坡，并且代表性集成场景不超过既有停车容差。
- [ ] AC8. `physics.yml` 继续严格解析，当前 schema version 为 2；旧 version 和 `track.gravity-mps2` 均被拒绝。
- [ ] AC9. Core、Basic、SimpleATO、BukkitPlugin 和隔离模拟的聚焦测试全部通过，`git diff --check` 无问题。

## 不在本次范围内

- 修改信号、联锁、路线准入、时刻表、车站或持久化语义。
- 天气相关黏着、轮滑仿真、再生制动能量核算，或用负标量速度表示倒车。
- 增加第二个重力配置字段，或接受缺失重力键的旧 physics 配置。
- 把 Bukkit 模型包 YAML DTO 移入 Basic；composition 应把它们映射为平台无关 profile。

## 备注

- 本任务属于复杂任务；激活前必须同时具备并审阅 `design.md` 和 `implement.md`。
