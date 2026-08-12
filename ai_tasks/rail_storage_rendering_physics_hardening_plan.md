# 轨道存储、展示与物理改进任务索引（审查后精简版）

> 状态：已完成二次代码审查；本文件不再作为“一次性实现全部内容”的 Epic。
> 基线：`feat/webui-timetable-editor` @ `2069c0c`
> 原则：只把已有代码能够证明的问题列为修复任务；性能猜测先测量；新模拟能力单独决策。

---

## 1. 为什么拆分

原计划混合了三种不同性质的事项：

1. **已由代码证明的缺陷**：应直接写 RED 测试并修复。
2. **可能存在的规模问题**：没有 profiler/benchmark 前不能直接重构。
3. **新能力或拟真增强**：需要产品语义，不应伪装成 bug fix。

原计划中的 generation 目录、manifest/checksum、geometry source/cache、完整 Controller 热恢复、自动三维 conflict zone、轨道全套 LOD 和车钩动力学均不再作为本任务的默认完成条件。

---

## 2. 拆分后的独立 AI Tasks

### Task A：存档完整性与兼容性

文件：[`rail_persistence_integrity_plan.md`](./rail_persistence_integrity_plan.md)

性质：**可以立即执行，优先级最高。**

已确认问题：

- `infrastructureRevision` 使用会在重启后归零的 `serverTickIndex`；加载又选择数值最大的备份 revision，可能在第二次重启后回滚到旧存档。
- `CoreRail.restoreConnections()` 会静默丢弃缺失引用并替换 active branch。
- 扩展数据依赖 JVM 类名和 `Class.forName()`。
- render meta 保存了加载时不用的 `segments/pointArcs`，并且不与 Core infrastructure 同 revision。
- 快照文件体积大，现有样本 gzip 后仅约为原大小 14%。
- `SavedTrainV1` 未保存脱轨状态。

不做：generation store、CBOR/定点量化、删除 canonical curve points、live Controller 自动恢复。

### Task B：物理时序与一致性

文件：[`physics_consistency_hardening_plan.md`](./physics_consistency_hardening_plan.md)

性质：**核心正确性项已实现；occupancy benchmark 与实机验证待执行。**

重新确认的实现方向：

- 不在 physics 热路径加锁；玩家必须先停运受影响区域，编辑入口等待已捕获 ticket、重新验证占用和运营资源后才执行高层 transaction。
- `SpeedSafetyMonitor` 改为针对 published epoch 产生 simulation-owned derail request，不放宽 Physics event bus 的线程契约。
- 墙钟卡顿产生的大 `deltaT` clamp 到独立的 200ms 最大值；现有 `MAX_STEP_MILLIS` 改名为明确的 epoch overload threshold；每次 wake 仍只执行一个完整 physics tick/epoch。
- occupancy 只做 benchmark；没有证据前不改变候选 API 和安全语义。

不做：全世界每 tick 深拷贝、overflow 状态机、自动 PhysicalConflictZone、通用刚体物理。

### Task C：轨道展示性能测量与最小优化

文件：[`rail_rendering_scalability_plan.md`](./rail_rendering_scalability_plan.md)

性质：**测量优先；未越过基线门槛时不得实现 LOD。**

当前事实：

- 轨道已按 audience 构建，无 viewer 时销毁 visual。
- native/entitylib 后端和增量 `RenderChunkManager` 已存在。
- 默认每 2m × 3 元素，长线路客户端实体数可能很高。
- viewer 重入时会重新计算曲线姿态和 backend descriptor。

第一阶段只建立 benchmark；若证据支持，最多先做 segment-local descriptor cache。自适应钢轨、枕木独立采样和逐玩家轨道 LOD 必须另开实现任务。

### Task D：ATO 动力学一致化

文件：[`ato_dynamics_alignment_plan.md`](./ato_dynamics_alignment_plan.md)

性质：**需要先确认产品目标，再执行。**

当前事实：

- 手动驾驶和详细时刻表仿真已经使用同一个 `TrainDynamics`。
- 只有 `SimpleATO` 使用恒加减速 `KinematicTrainActuator`。

因此不重构整个控制栈；若产品确认 ATO 必须与手动驾驶使用相同车辆性能，只新增 dynamics-backed actuator 并做保守制动预测验证。

---

## 3. 明确延期的新能力

以下内容不属于上述四个任务的完成条件：

- 不同 Rail ID 之间的平交/菱形交叉碰撞；
- 自动识别三维净空和生成 conflict zone；
- 曲线阻力、电空混合制动、车钩压缩/拉伸；
- 根据冲击能量决定联挂或脱轨；
- 客户端 Mod/Flywheel 风格 GPU instance 渲染。

其中跨 Rail 碰撞是一个真实的**能力缺口**：`SweptRailSpan.overlaps()` 明确要求相同 `railId`。但仓库当前没有正式的平交基础设施语义，所以应先确定“哪些不同 Rail 共享物理冲突资源、哪些是高架跨越”，再另立任务。

---

## 4. 推荐执行顺序

```text
A1 revision 单调性
  ↓
A2 严格快照验证
  ↓
A3 render meta 纳入现有 revision + gzip
  ↓
A4 稳定扩展 type ID + 脱轨状态

B1 停运维护前置条件 RED 测试
  ↓
B2 无 physics 锁的高层维护 transaction
  ↓
B3 safety 串行化
  ↓
B4 单个完整 physics tick 的可观测 delta clamp

C benchmark ──证据成立──> segment-local descriptor cache

D 产品确认 ──确认一致化──> DynamicsTrainActuator
```

Task A 与 Task B 不应在同一个 PR 中实现。Task C/D 不阻塞 A/B 完成。

---

## 5. 全局工程规则

1. 每个问题先写能在当前代码上失败的 RED 测试。
2. 固定 fixture 优先于只做对象 round-trip。
3. 不静默丢引用、忽略未知类型、截断物理时间或覆盖损坏存档。
4. 不把性能估算当作 profiler 证据。
5. 不修改 canonical curve 表示，除非另有完整创作参数和迁移设计。
6. 每个子任务必须可独立回退。
7. 相关模块测试通过后再运行：

```bash
./gradlew :Core:test :Basic:test :BukkitPlugin:test
./gradlew build
```

---

## 6. 本索引的完成定义

本文件只是任务索引，不以“四项全部实现”作为一个合并条件。每个子任务按自己的完成定义独立关闭。A/B 完成后可称为“存档与物理一致性加固完成”；C/D 由测量和产品决策分别推进。
