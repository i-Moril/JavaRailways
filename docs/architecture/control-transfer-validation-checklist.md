---
title: Phase D1 控制权转移与重新规划验收清单
slug: /architecture/control-transfer-validation-checklist
---

# Phase D1 控制权转移与重新规划验收清单

人工接管结束后采用“**不恢复旧 Mission，管理员从明确 WayPoint 重新规划**”的策略。重新规划生成新的 ATO 与严格递增 Mission revision；系统不会根据旧位置自动猜测恢复路径。

> 本清单记录 D1 基线及已完成的 T5 扩展。给定时刻表列车接管时默认进入 `MANUAL_ROUTED`；只有钥匙 UI 关闭进路辅助后才进入 D1 的 SUSPENDED/replan 流程。

## D1-A 接管边界

- [x] **D1-A01** Bukkit `TrainControlManager` 在替换活动 SimpleATO 前同步通知所属 World 的 Dispatcher。
- [x] **D1-A02** legacy/full-manual 接管进入 `SUSPENDED`；默认 timetable 接管记录稳定 transfer owner、进入 `MANUAL_ROUTED`，并在安装人工 controller 前解除旧 ATO controller。
- [x] **D1-A03** 接管会取消 pending operation ticket；取消后的迟到模型回调不能恢复运营状态。
- [x] **D1-A04** 活动 StationCall 标记为 transfer-cancelled，不继续开关门或自动授予发车。
- [x] **D1-A05** 未由 Dispatcher 管理的列车仍可沿用原人工控制流程。

## D1-B 资源与位置

- [x] **D1-B01** 接管、释放驾驶权和 replan 都不会强制释放已确认 RouteSection 或 StationBerth。
- [x] **D1-B02** Dispatcher 在 `SUSPENDED` / `REPLAN_REQUIRED` 中从已发布 CoreTrain 位置继续更新 head/tail progress。
- [x] **D1-B03** 资源仍只在车尾越过原 release boundary 后释放；偏离原经路时保持占用并要求人工检查。
- [x] **D1-B04** transfer-cancelled StationCall 只有在 berth allocation 安全释放后才从 run 清理。

## D1-C 释放与重新规划

- [x] **D1-C01** 只有匹配 transfer owner 的 release 能把状态推进为 `REPLAN_REQUIRED`；驾驶 session 释放时人工 controller 进入紧急制动，直到列车停稳。
- [x] **D1-C02** replan 要求人工 session 已释放、列车停止、列车位于管理员指定 WayPoint，并且旧 route/berth 资源全部释放。
- [x] **D1-C03** replan 从指定 WayPoint 到被中断 movement 的原目标重新寻路，不静默恢复旧 Mission 或跳到任意目标。
- [x] **D1-C04** 成功 replan 创建新的 SimpleATO；仍有 movement 时提交更高 Mission revision。解析、方向或资源条件失败时保持 `REPLAN_REQUIRED`。
- [x] **D1-C05** 若指定 WayPoint 已是原目标，则直接推进剩余 timetable steps，但仍使用新 ATO 生命周期。
- [x] **D1-C06** 管理员通过 `/jr timetable replan <id> <service> <current-waypoint> [cycle]` 显式恢复；仍有人工 session 时命令拒绝。

## T5 MANUAL_ROUTED 扩展

- [x] 默认钥匙接管由司机 controller 独占速度输出，Dispatcher 仅保留计划经路和资源生命周期。
- [x] MANUAL_ROUTED 不自动执行 door、dwell hold 或 departure grant。
- [x] 未确认保护点越界、PlannedRoute 偏离和 confirmed turnout 破坏触发 World-local Stall。
- [x] UI 可显式切换 FULL_MANUAL；此后不申请新 Section，旧资源继续按车尾释放。
- [x] 释放任一人工 session 后必须显式 replan，不自动恢复 ATO。

## 验收证据

- `TimetableDispatcherTest` 覆盖 transfer owner、ATO detach、显式 release、资源阻止 replan、新 revision 和完成运行。
- `TimetableStationCallDispatcherTest` 覆盖 pending door ticket 取消以及 berth 所有权保留。
- Bukkit 编译与完整测试覆盖 `TrainControlManager` 的接管/释放适配和 replan 命令。
