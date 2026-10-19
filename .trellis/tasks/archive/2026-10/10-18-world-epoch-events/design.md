# 子任务设计

## 技术方案

World增加不可变运行模式，WorldEvent只暴露World；TrainEvent复用train.world，独立事件显式带所属World。Pre/Post使用PhysicsRailEvent，按协调器计算前/成功完整发布后通知，payload提供本次epoch/实际delta与Post的已提交frame。保留普通listener异常隔离及原决策语义；不做每次发布归属校验。删除publishCollisionEvents开关及所有false调用，不改正常碰撞算法。

完整交界契约见[父设计](../../../10-18-isolated-simulation-runtime/design.md)，本任务不能自行改变用户已确认的模式/事件/失败语义。

## 代码ownership

Core的World/CoreWorld、api/event、PhysicsCoordinator及真实调用方/fixtures；SimpleATO模拟工厂的碰撞开关参数；Bukkit现有事件consumer。

派发前按实际最小变更文件限定ownership；其他子任务和会话可能同时存在，不能恢复或覆盖他人改动。源文件或持久化格式若需扩大范围，先记录真实依赖并更新设计。

## 依赖与回滚

无前置子任务；最终规划获批后第一个实施。
每批独立验证/提交；回滚仅撤本子任务提交，保留原合并基线与无关WIP。
