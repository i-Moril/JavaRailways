---
title: 线程与 Tick 模型
slug: /architecture/threading-model
---

# 线程与 Tick 模型

MineRails 同时使用 Paper 主线程、通用 Tick worker 和列车物理 worker。线程归属是扩展开发和故障排查时最重要的约束之一。

## Server Tick 流程

```mermaid
sequenceDiagram
    participant Main as Paper 主线程
    participant Physics as PhysicsCoordinator
    participant Workers as Physics workers
    participant Mailbox as LatestTrainFrameMailbox
    participant Generic as TickManagerImpl

    Main->>Physics: TickStart: wakeFromMainThread()
    Main->>Generic: preTick(now)
    Physics->>Workers: 按 batch 计算控制器
    Workers-->>Physics: next physics state
    Physics->>Physics: 发布 Core 状态并重建 swept occupancy
    Physics->>Mailbox: offer immutable TrainFrameSnapshot
    Main->>Physics: TickEnd: captureCurrentTicket().await()
    Main->>Mailbox: drain latest frame per train
    Main->>Main: Bukkit visual frame consume
    Main->>Generic: postTick()
```

TickEnd 只捕获一次当时的 physics ticket，并等待该 ticket。它不能在等待期间改为较旧 epoch，也不能在物理失败后提交旧状态。

## PhysicsCoordinator

每个 `CoreWorld` 有一个 Coordinator：

- 同时最多一个 in-flight epoch；
- 主线程和 fallback timer 的重复唤醒合并到同一个 ticket；
- worker 数由 `physics-worker-threads` 显式配置；
- 相邻 physics epoch 起点决定 `deltaT`，首个 epoch 使用 50 ms；
- 主线程 heartbeat 超过 75 ms 时，50 ms fallback timer 可以继续唤醒物理；
- 10 秒内 3 个 epoch 达到或超过 50 ms，Coordinator 进入 `STALLED`，不会自动恢复；
- `close()` 会停止 timer、Coordinator executor 和 worker pool。

## 发布与渲染

物理 worker 计算完后，Coordinator 统一发布列车状态并重建 swept occupancy，同时为每列车原子发布 value-only、带 revision/physics epoch 的 `TrainFrameSnapshot`。frame 不保留 live JOML、World 或 Bukkit 对象。

`CoreWorld` 使用有界 `LatestTrainFrameMailbox` 将 physics publisher 与平台 visual consumer 解耦：同一列车积压多帧时只保留最新 revision，stale frame 不能覆盖新状态，容量、合并和丢弃均有指标。因此渲染消费者暂停时不会形成逐 tick 无界 backlog，也不会阻止下一 physics epoch 发布。

当前过渡实现仍由 `WorldManager` 在 Paper TickEnd drain frame，并让 `CarExtension.applyFrame()` 执行 Bukkit visual 更新；但旧的 `CoreWorld.commitPublishedTrainRenderState()` 调用链已经移除，渲染扩展消费 frame 而不是由 Core `commit()` 进入 Bukkit。后续 Folia 阶段会把 visual frame 按 entity/region/player 所有权交给 `PlatformDispatcher`，并移除 Paper 主线程 drain。

## 通用 TickManager

`TickManagerImpl` 继续负责动画、延迟 action、速度安全监控等通用 `TickingObject`。它与列车物理 Coordinator 是两条独立调度路径，不应把列车控制器重新注册回通用 TickManager。

## 事件线程

MineRails 事件按线程归属分成两套同步总线：

- `MainRailEventBus` 只接受 `MainThreadRailEvent`，发布时通过 `ThreadGuard` 断言平台主线程；
- `PhysicsRailEventBus` 只接受 `PhysicsRailEvent`，发布时断言 MineRails async Tick worker；
- 两者都在调用线程立即执行监听器，不会自动调度，并逐个隔离监听器异常；
- 注册会返回可关闭的 `EventRegistration`，插件和服务必须在停止时关闭；
- 原 `EventManager` 仅保留为兼容 facade，新代码不再使用。

标记为 `@AsyncEventApi` 的物理事件要求监听器显式 opt-in 且保证线程安全。worker 监听器不得直接操作 Bukkit 实体、世界、玩家或 Dialog；需要通过明确的主线程提交边界转交。跨线程 payload 应使用 `TrainSnapshot`、`RailObjectSnapshot` 等不持有 live World/Rail/Train 的不可变值。

StationCall 通知在 physics 发布后由主线程 Dispatcher 消费。Phase C3 的 `TrainOperationGateway.execute()`、operation ticket 轮询/取消、`behavior.js` 和模型 action 全部只在主线程执行；operation request 只携带稳定 ID 和语义值，不会交给 physics worker。Phase D1 的 controller transfer、release 和 replan 同样只在主线程执行；`SUSPENDED` 状态只读取完整 physics epoch 后已发布的 CoreTrain head/tail 位置来释放旧资源。

给定时刻表运营闭环继续由 World-local Dispatcher 在主线程拥有 timetable epoch/cycle、Depot occupancy、RollingStock reservation 和 MANUAL_ROUTED 状态。确定性随机选择只能读取预解析 RollingStockRecord。Depot reset 必须先等待捕获的 physics ticket，完成全量预检后才能一次提交列车位置和资源账本；physics worker 不参与 Depot 分配或 Bukkit 配置读取。

## 代码约定

`@MainThread`、`@Heavy`、`@Concurrent` 等注解表达调用约束，但不是自动调度器。关键入口还会使用 `ThreadGuard` 做运行时检查。新增 API 时必须在 KDoc 中说明调用线程、阻塞行为和状态何时可见。
