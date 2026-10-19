# 源码证据与交接基线 — 2026-10-18

## 基线与已确认决定

当前dev/signal-system HEAD为a76b076c，坐标统一6548a850及迁移提交401f239c已包含，UI分支12589555已合并。该合并验证108项前端测试及build；不证明隔离模拟实现。规划启动时get_context报告49项工作区变更。

用户已确认：全部领域Feature、排除Bukkit；删除模拟碰撞旁路；事件带World实例且无重复归属校验；World承载模式，不预建environment；Pre/Post按每物理epoch，失败有Pre无Post。保留现有静态设施+合成列车的模拟输入方式。

## 运行隔离事实

- `Core/.../physics/CoreWorld.kt`：轨道/列车、空间/交叉索引、拓扑锁、发布frame和runtimeId属于实例。
- `Basic/.../BasicWorldData.kt`和`SimpleATO/.../SimpleATOWorldData.kt`：安全、运营方向、ATO/Dispatcher、inspection、信号限制和选路source为World数据。
- `BukkitPlugin/.../util/BukkitThreadGuardBridge.kt`：全局simulationLock与线程分类标记绑定；WorldManager及HTTP模拟共同进入，导致整场模拟阻塞其他owner。
- `BukkitPlugin/.../logic/WorldManager.kt`：现有全局tick遍历World并等待各自ticket；不能只换锁而保留全局等待。
- `BukkitPlugin/.../logic/TrainResourceReloader.kt`：资源重载跨全部World进入帧间再发布。
- `BukkitPlugin/.../api/internal/DefaultInfrastructureResourceService.kt`：贡献者注册/卸载检查全部World活动状态，须保持检查-提交一致性。
- `BukkitPlugin/.../control/TrainControlManager.kt`：玩家会话含跨World释放/接管，须保持玩家级一致性。

## 事件事实

- `Core/.../api/event/TrainEvent.kt`：6类现有事件已有Train，可直接提供train.world；TrainLinkEvent单独持参与列车。
- `Core/.../api/event/CollisionEvent.kt`：目前只有字符串worldId；`physics/tick/PhysicsCoordinator.kt`构造时使用attachedWorld.runtimeId。
- `PhysicsCoordinator.runEpoch`：worker计算、train发布、安全processors、候选world frame、railway processors、完整frame提交、ticket完成。普通唤醒/fallback/模拟汇入同一入口；回调不能放入stateLock/topology提交锁。
- `PhysicsCoordinator.publishCollisionEvents`和`SimpleATO/.../timetable/simulation/IsolatedTimetableSimulation.kt`的false参数：实际碰撞决策可被模拟旁路，须移除。
- `BukkitPlugin/.../behavior/TrainReverseBehaviorListener.kt`和`debug/TrainDebugEventListener.kt`：进程级监听器可从模拟事件进入renderer/实际日志；需按World来源选择出口。
- `PreTickEvent`/`PostTickEvent`无publisher；`RenderableEvent`是分组接口。旧待办为`10-09-stage2-code-review/implement.md`第5项，历史保留提交a21c6bba。

## 模拟Feature缺口

- `BukkitPlugin/.../http/IsolatedTimetableSimulationFactory.kt`只捕获Core/Basic/SimpleATO设施和控制profile，无SimpleSignals定义/规则安装。
- `IsolatedTimetableSimulation.kt`中新建空signal/inspection runtime；IsolatedRouteAdmissionHost使用empty contributions，未连publishAdmissionUpdate；生产WorldManager已连接它。
- 生产WorldManager安装SpeedSafetyMonitor并消费发布后脱轨请求；模拟装配未覆盖。
- `SimpleSignals/.../runtime/SimpleSignalsWorldRuntime.kt`的install串联occupancy epoch、route planning及两类inspection source；`SimpleSignalsPlugin.kt`安装固定闭塞资源policy。上述链需全部进入模拟。
- `SimpleSignals/.../signalpack/SignalLogicRuntime.kt`为每次求值建立fresh realm，但manager共享同一串行runtime；模拟须有自己的实例。
- `SignalPackCatalog.kt`仍带CompiledModelAsset；manager和模型回调有Bukkit服务依赖。已有平台拆分任务记录精确边界，不能把复制manager或伪source作为解法。
- `BukkitPlugin/.../behavior/CarBehavior.kt`真实执行逐车JS、方向、拒绝、延迟效果汇总；当前模拟只按modelActionDurationTicks推进，二者不等价。
- `TimetableExtensionRegistry.createSimulationRegistry`只复制显式旧simulation factory；需要当前领域Feature覆盖清单和目标World实例，不可静默遗漏。

## 保留边界与证据等级

本文件来自本聊天只读源码/任务检索和已完成合并事实；没有执行本任务运行测试、远端服务操作或schema修改。现有模拟测试的手写signal source仅证明接口可接入，不证明完整SimpleSignals，也不证明与实际World并发隔离。

原工作区Core/日志、SimpleSignals编辑/规则、模型及docs-site WIP属于其他批次。合并前78个修改/未跟踪文件内容已核对保留，临时恢复点`/tmp/jr-pre-webui-merge-ug92u_gw`；它不是长期备份，也不是当前新任务的实现基线提交。
