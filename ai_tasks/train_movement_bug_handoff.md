# 列车创建不显示/不移动 Bug — 诊断与修复记录

**日期**: 2026-06-27
**分支**: `feature/continuous-curve-api`

---

## 症状

1. `/mr train create s100_4car` 后火车不显示 【已经解决】
2. `/mr train start` + `/mr train throttle 0.5` 后火车不移动

---

## 根本原因汇总

| # | 根因 | 影响 |
|---|------|------|
| A | **实体 spawn 在世界原点 (0,0,0)** — Paper 实体追踪不向远端玩家发送 spawn packet | 火车不可见 |
| B | **TickManager CyclicBarrier 死锁** — 主线程在提交 task 前调用 `barrier.await()`，调度线程被阻塞 | 火车不移动（Controller 注册后服务器卡死）|
| C | **嵌套二分搜索 O(n²)** — `updateByPrevCar` 外层搜索的每个候选调用 `calculatePositionByTailBogie`，内部又触发 `bogiePairingSearch` | `setLocation` 极慢（多车厢时卡几分钟），spark 超时告警 |
| D | **Paper 插件 remap 缓存永不过期** — `hashes: {}` 为空，代码改了但缓存不刷新 | 服务器一直跑旧代码 |

---

## 已应用的修复

### 修复 A: Entity spawn 位置

**文件**: `BukkitPlugin/.../config/model/ModelTreeBuilder.kt`

**问题**: `buildPart()` 中所有 display entity spawn 在 `Location(bukkitWorld, 0.0, 0.0, 0.0)`，离玩家 400+ 格。Paper 在实体 spawn 时按 chunk 判断追踪——chunk (0,0) 没有玩家追踪，spawn packet 不发。后续 `DisplayModel.updateState()` 的 `entity.teleport()` 移动到正确位置，但 Paper 可能不重新触发追踪。

**修复**: `ModelTreeBuilder` 增加 `rootLocation` 字段，`buildCar()` 时记录 car 位置。`buildPart()` 改为 `rootLocation.clone()` 作为 spawn 位置，实体从靠近玩家的 chunk 开始。

```kotlin
// 新增字段
private var rootLocation: Location = Location(null, 0.0, 0.0, 0.0)

// buildCar 中记录
this.rootLocation = rootLocation

// buildPart 中使用
val spawnLoc = rootLocation.clone()  // 之前: Location(bukkitWorld, 0.0, 0.0, 0.0)
```

### 修复 B: CyclicBarrier 死锁

**文件**: `Core/.../util/TickManagerImpl.kt`

**问题**: 原始代码 `barrier.await()` 在主线程先执行，然后才 `scheduler.submit` task。主线程阻塞等待 barrier，但 task 还没提交。N=1 时两个线程不同所以不卡死；N≥2 时 task1 的 `barrier.await()` 堵塞单线程调度器，task2 无法运行 → 死锁。

**修复**: 重构 `preTick()`——先提交所有 task，再主线程 join barrier。保持 CyclicBarrier 的 commit 同步语义（所有 synchronizable 对象 tick 完成后统一 commit）：

```kotlin
override fun preTick(currentTimeMs: Long) {
    val deltaT = ...; currentTick = currentTimeMs
    val tickLatch = CountDownLatch(tickingObjects.size)
    if (tickingObjects.isEmpty()) { waitForPreTick = Runnable{}; return }

    val syncCount = tickingSynchronizableObjects.size
    val barrier = if (syncCount > 0) CyclicBarrier(syncCount + 1) else null

    // 先提交 task
    tickingObjects.forEach {
        scheduler.submit {
            it.tick(deltaT)
            if (it is Synchronizable && barrier != null) {
                barrier.await()   // 所有 sync 对象在此汇合
                it.commit()
            }
            tickLatch.countDown()
        }
    }

    // 主线程再 join barrier
    if (barrier != null) barrier.await()

    waitForPreTick = Runnable { tickLatch.await() }
}
```

**已知限制**: N≥2 个 synchronizable 对象时单线程调度器仍会死锁。目前仅 `ManualController` 是同步对象（`CoreCar`/`CoreBogie` 不是 `TickingObject`），N=1 安全。

### 修复 C: 消除嵌套二分搜索

**文件**: `Core/.../physics/train/CoreCar.kt`

**问题**: `findTailBogieByHead` 和 `findHeadBogieByTail` 内部调用 `bogiePairingSearch`（一次完整的 `searchRailLocation`），但该搜索在弯道上必然失败（弦长 < 弧长，`distance - bogieDistance` 始终 < 0，两端点同号无零点），实际生效的始终是 fallback 的 `forward()`。`updateByPrevCar` 外层搜索每个候选都触发一次内层搜索，O(n²)。

**几何推导**:

```
弦长 chord = 2R·sin(s/2R) = s - s³/(24R²) + ...（泰勒展开）

s = bogieDistance = 1.2m:

| R    | 弦长      | 误差    |
|------|-----------|---------|
| 80m  | 1.199989  | 1.1e-5  |
| 10m  | 1.19928   | 7.2e-4  |
| 5m   | 1.19712   | 2.9e-3  |

最大误差 0.003m < Curve.TOLERANCE (0.005m)
```

**误差不积累原因**: 外层 `searchRailLocation` 不断调整 tailBogie 位置直到 `connectorDistance == couplerLength` 满足——内层微小误差被外层搜索消化补偿。

**修复**: 简化为直接 `forward(bogieDistance)`，删除 `bogiePairingSearch` 和 `bogiePairingTolerance` 方法。

```kotlin
private fun findTailBogieByHead(headBogieNewLocation): RailLocationWithFacing {
    return headBogieNewLocation.forward(-bogieDistance) ?: throw BogieException(tailBogie)
}

private fun findHeadBogieByTail(tailBogieNewLocation): RailLocationWithFacing {
    return tailBogieNewLocation.forward(bogieDistance) ?: throw BogieException(headBogie)
}
```

### 修复 D: Paper 缓存

每次启动前需清除 `.paper-remapped/` 目录，Paper 的 `hashes: {}` 导致插件 jar 改了也不会重 remap。

---

## 其他改动

| 文件 | 改动 | 说明 |
|------|------|------|
| `Annotation/.../PersistentByProcessor.kt:122-124` | 删除残留代码 | 编译错误：孤立的 `startType.arguments[0].type` 和未完成表达式 |
| P4 transformDirty 优化 | 各 model 类 | `CompositeModel` 缓存 `lastRotation`，旋转未变时跳过 `entity.transformation` 设置。首帧 `lastRotation=null` → `transformDirty=true` → 正常设置。 |
| P3 ObjectPools | `PrimeCurveImpl.kt` + `ObjectPools.kt` | 池化临时 `Vector3d`，减少 GC 压力 |

---

## 待验证 / 未解决

1. **服务器重启后实际测试** — 修复 A-D 均已编译部署（16:20 重启），但等待用户实际进服验证
2. **N≥2 synchronizable 对象死锁** — 当前 N=1 安全，未来如多个 controller 或动画系统需改用多线程调度器
3. **s100_5car 载入失败** — `s100/connector_toilet` car 定义缺失，不影响当前测试（s100_4car）

---

## 调试记录

- **jstack 线程 dump**: Server thread 卡在 `findHeadBogieByTail → bogiePairingSearch → searchRailLocation` 嵌套调用链
- **spark 超时**: 每 60s 一次 `Timed out waiting for world statistics`，确认 server thread 长时间不可用（根源是嵌套搜索而非死锁）
- **Paper 缓存**: `.paper-remapped/index.json` 中 `"hashes": {}` 空对象，缓存在 `rm -rf` 后重建才生效
