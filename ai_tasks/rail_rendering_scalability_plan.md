# AI Task C：轨道展示规模测量与最小优化

> 状态：Measurement first
> 类型：performance investigation
> 范围：静态 Rail visual；不包含车厢平台碰撞和车模性能

---

## 1. 目标

先用可重复数据确认静态轨道展示的真实瓶颈，再只实施能被数据支持的最小优化。

本任务默认只允许一个代码优化：**segment-local descriptor cache**。轨道自适应采样、钢轨/枕木独立频率和逐玩家 LOD 只能在本任务产出证据后另开任务。

---

## 2. 当前事实

已经存在的优化不能重复建设：

- `RenderChunkManager` 已使用空间索引和增量 audience。
- `RailExtension.hideFrom()` 在最后一个 viewer 离开时销毁 backend visuals。
- 无 viewer 时不存在静态 Rail Display visual。
- native 和 EntityLib 已共用 `DisplayRenderBackend`。
- `RailArcVisualSegment` 按约 8m 管理空间索引。

可能的规模问题：

- 默认 `repeat_length: 2.0`，模板有 3 个元素。
- 1km 完全可见单线约生成 `(1000 / 2 + 1) × 3 ≈ 1503` 个 Display。
- packet backend 减少服务端真实实体成本，但不消除客户端实体和 spawn/destroy 包。
- audience 从 0 → 1 时，`RailExtension.buildModel()` 会重新执行 `curve.getPointAt()`、位置/四元数计算和 descriptor 创建。
- `RenderChunkManager.dispatchRefreshFor()` 对现有 Rail audience 不做 LOD refresh，说明轨道当前没有逐玩家 LOD。

这些事实只能说明“可能扩展受限”，不能代替客户端/server profiler。

---

## 3. 非目标

- 不修改 render meta 持久化；该工作属于 Task A。
- 不重写 `RenderChunkManager`；它已经是增量实现。
- 不实现全局 descriptor hash registry、LRU、字节预算或跨 world cache。
- 不默认实现 NEAR/MID/FAR 轨道 LOD。
- 不默认改变 2m 外观、枕木间距或模型资源。
- 不尝试在纯服务端插件中复制 Create/Flywheel GPU instance。
- 不把车厢 Display、Seat、Interaction、PlatformModel 指标混入轨道结论。

---

## 4. Phase 1：建立可重复 benchmark

场景至少包含：

1. 1km 直线，默认 3 元素模板。
2. 1km 复合曲线/坡道，默认模板。
3. 10km 路网，但玩家只看到配置视距内部分。
4. 1、10、50 名玩家，受众完全重叠与完全分离。
5. 玩家静止 steady-state。
6. 玩家反复进入/离开同一批 Rail segment。
7. native 与 entitylib 分开记录。

记录：

- registered `RailArcVisualSegment` 数；
- active `DisplayVisual` 数；
- viewer relation 数；
- spawn/destroy/metadata 包数（entitylib 可用后端计数器）；
- `buildModel()` 次数和总耗时；
- curve sample/transform 计算耗时；
- audience reconcile 耗时；
- 服务端 tick impact；
- 客户端 FPS/frame time 和实体数，注明机器与视距。

规则：

- warmup 后至少重复多轮，报告 p50/p95，不只记录一次 wall-clock。
- 客户端和服务端指标分开。
- benchmark 工具不得进入生产 hot path，除非作为可关闭 metrics。

输出：

```text
docs-site/docs/benchmarks/rail-rendering-<date>.md
```

---

## 5. Phase 2：有证据时增加 segment-local descriptor cache

触发条件：反复 audience 0 → 1 场景显示 curve/transform descriptor 重建占 Rail visual 构建的显著比例；若主要成本是客户端实体/spawn，本优化不能冒充解决方案。

### 设计

- 每个 `RailArcVisualSegment` 或其 `RailExtension` 缓存该 segment 的 `List<DisplayVisualDescriptor>`。
- viewer 归零时只销毁 `DisplayVisual` handle，不删除 descriptor。
- 下一 viewer 进入时直接由同一 backend descriptor 重建 visual。
- Rail 删除/world unload 时 descriptor 随 segment 一起释放。
- template、Rail geometry 或 renderer distance-dependent descriptor 参数变化时失效。

不需要：

- descriptor 全局 ID；
- SHA-256；
- 跨 Rail dedup；
- 全局 LRU。

### RED

- viewer 第一次进入会构建 descriptor。
- 全部离开再进入，不再次调用 curve sampling/rotation builder。
- Rail unregister 后缓存可释放，不保留 world 强引用。
- template/geometry replacement 使用新 descriptor。
- native/entitylib 消费同一 descriptor 后 content/transform 等价。
- visual create 失败时销毁已创建 handle，但保留有效 descriptor 供诊断/重试策略处理。

### 验收

用 Phase 1 相同的 audience churn 场景前后对比：

- descriptor build 次数应从“每次 0→1”降为“每个未变化 segment 一次”；
- steady-state active visual 和客户端实体数不变；
- 无残影、重复 visual 或 viewer 泄漏。

---

## 6. Phase 3：只输出后续决策，不在本任务实现

如果数据显示瓶颈是客户端实体数或包量，报告应分别评估：

1. 钢轨长模型/曲率误差自适应 segment。
2. 枕木固定间距、钢轨独立采样。
3. 逐玩家 NEAR/FAR 两档轨道 LOD和 hysteresis。
4. 资源包组合模型减少每重复点元素数。
5. 更小视距或管理员 visual budget。

每项必须写：预期减少的 visual 数、视觉损失、native/entitylib 差异和实机测试要求。随后新建独立实现 AI Task；本任务不自动选择方案。

---

## 7. 验证

代码未改时：

```bash
./gradlew :BukkitPlugin:test
```

实现 local cache 后：

```bash
./gradlew :BukkitPlugin:test
./gradlew :Core:test :Basic:test :BukkitPlugin:test
./gradlew build
```

建议测试：

- `RailVisualDescriptorCacheTest`
- backend descriptor contract tests
- world unload/viewer churn integration test

---

## 8. 完成定义

本任务有两种合法完成结果。

### 结果 A：无需改代码

- [ ] benchmark 可重复并提交报告。
- [ ] 数据显示 descriptor 重建不是重要成本。
- [ ] 报告明确真正瓶颈及是否需要新 LOD/模型任务。

### 结果 B：实施 local cache

- [ ] benchmark 证明 descriptor 重建值得优化。
- [ ] segment-local cache 测试通过。
- [ ] audience churn 不重复采样未变化 Rail。
- [ ] steady-state visual 数和视觉结果不变。
- [ ] world unload 后无缓存/world 引用泄漏。
- [ ] 前后 benchmark 报告已更新。

两种结果都不得在没有新任务的情况下顺带实现完整轨道 LOD。
