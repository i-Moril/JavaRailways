# 修正列车坐标约束并比较性能

## Goal
恢复车内转向架连接点的真实三维距离约束，统一运行、显式摆位和恢复的几何语义，并量化相对当前实现的性能差距。

## Background and evidence
- 用户指定参考 chat `01a12510-b884-7f42-a7f1-0b96ef278e14` 最后建议：领车确定、复用上一位置、沿既定路径局部修正、验证实际间距；不照搬离散 RTM 或跨 tick 渐进纠偏。
- 性能方法参考 chat `01a0b360-1912-70d0-9278-973d8ff7b728`：真实 physics epochs、预热、多次重复、分位数、worker/coordinator 分解、正确性门禁。历史绝对数字不可作为本机基线。
- 基线 `dev/signal-system` / `00a44abd16c284a2ec7f908a650637f70aeeebd3`。
- CoreCar.kt:215、220 的摆位直接使用 bogieDistance 弧长；:300-310 的运行条件也用弧长；:322、335 附近车体位置却以连接点弦方向计算。
- CoreTrain.kt:471-473 恢复校验仍强制 bogie 弧长间距，需要跟随几何契约修正。
- 当前有 Core PhysicsCapacityStressTest 和 physicsStressTest；参考 chat 后期 Basic ATO harness 不在当前 checkout。

## Requirements
R1. 按连接点（包含现有 floorHeight/normal 规则）的三维直线距离约束配置轴距和相邻车钩距离；保留配置 offset 含义。
R2. 保持 controller 决定领车位移、后车沿 train-local 路径跟随；保留道岔、换向、端部 traversal、occupancy 与 publication 合同。
R3. 正向、反向、显式摆位及当前格式恢复采用一致几何检查；求解失败沿既有 PhysicsException→derail 边界处理，不把不满足残差的候选当成功结果；不新增整个编组运动回滚保证，也不增加旧开发格式 runtime compatibility。
R4. 同机同 JVM/worker/轨道/编组/速度/预热/采样配置实测 baseline 与 fixed，保存原始报告、代码 provenance、运行顺序与环境。报告速度比和耗时差，不保证提速。
R5. 保留所有已有无关 dirty/untracked 内容。

## Acceptance criteria
- 直线、紧曲线（包含 6m 轴距/30m 半径反例）、S 弯、坡道接头、不同 offset/floorHeight、双向运行与停止/恢复覆盖，轴距和车钩误差不超过现有 Curve.TOLERANCE（5mm）。
- 验证领车目标不被后车纠偏改变；保留支路选择、tail 路径与已有恢复失败清理回归。
- Core+Basic 全量回归通过，受影响的持久化和消费者测试通过。
- 基线与修复使用相同 harness；每个场景至少三次分别重建 world 的重复（同一 JVM 内，非独立 JVM forks），提供 p50/p95/p99/max、workerTotal/workerMax/coordinator p95 和 50ms p95 预算判断；无脱轨、碰撞、mailbox drop。
- 压测至少覆盖 15节、100/500列、4workers、straight/curved、isolated/mixed/shared、10m/s、50ms逻辑tick、200预热+1000采样。若结果噪声过大，延长预热或补交错重复并明确记录。
- 新几何回归在 baseline 展示实际误差；性能旧实现结果标注其几何不正确，不声称等精度优化。

## Out of scope
用户于2026-10-11明确排除接近半圆轴距的极紧曲线专门支持；不为此增加特殊搜索分支。联结事务、远距离简化模拟、玩家可见性、ATO/Dispatcher 优化、存档迁移与部署、全服容量结论。JSON 字段和版本号保持，旧错误开发数据不默默兼容；如需保留既有数据另立离线迁移范围。
