# Packet Entity 渲染后端改进计划

## 1. 目标

为 MineRails 增加管理员可选的展示实体渲染后端，同时保留现有行为：

- `native`：继续使用真实 Bukkit/Paper `Display` 实体；
- `entitylib`：使用 EntityLib + PacketEvents 创建逐玩家虚拟 `ItemDisplay` / `BlockDisplay`，允许展示距离超过 Paper 原生实体追踪和玩家区块发送距离；
- 每名玩家可拥有独立的 MineRails 展示距离；默认值取该玩家设置的客户端视距上限，并受管理员配置的最大方块距离约束；
- 座位、交互命中箱和碰撞平台仍使用真实 Bukkit 实体，不纳入远距离虚拟化。

本计划只覆盖视觉 Display。它不改变 Core 物理、轨道持久化格式、座位控制或碰撞语义。

## 2. 当前阶段

分支：`feat/packet-entity`

已完成的 bootstrap：

- [x] `packetevents-spigot:2.11.1` 由 `compileOnly` 改为 `implementation`；
- [x] `EntityLib spigot:3.0.3-SNAPSHOT` 继续使用 `implementation`；
- [x] 两个库进入 BukkitPlugin 的 runtime/shadow classpath，并完成 package relocation；
- [x] 增加严格管理员配置和 embedded runtime 生命周期；
- [x] 增加 `DisplayRenderBackend` 抽象与 native/entitylib 实现；
- [x] 静态轨道完成首个 EntityLib 纵向切片；
- [x] 列车 root、body、bogie、passenger、LOD 和 display-state 动画接入后端抽象；
- [x] Paper 1.21.11 无玩家启动/停服冒烟测试通过。

座位、Interaction 命中箱和碰撞平台仍使用原生 Bukkit 实体。EntityLib 列车视觉等待多人实机验证；依赖版本升级继续单独推进。

## 3. 管理员配置草案

```yaml
display-renderer:
  # entitylib | native
  backend: entitylib

  player-distance:
    # player-view-distance：使用玩家客户端视距换算为方块
    default: player-view-distance
    # 管理员允许的 MineRails 最大视觉距离
    max-blocks: 512.0
```

规则：

1. `entitylib` 是 Paper/Folia 默认值，使用 packet-only Display，可超过原生实体追踪范围。
2. `native` 保留为管理员显式兼容回退，受原生追踪限制。
3. 后端只在启动时选择；不支持热切换。
4. 无法初始化所选后端时必须明确阻止插件启用，不能静默回退并造成重复或缺失模型。
5. 玩家距离必须在 `0..max-blocks` 内；未设置玩家使用 `min(clientViewDistance * 16, max-blocks)`。
6. `viewRange` 至少设置为 `max-blocks / 64`，避免客户端 Display 自身的默认裁剪。

## 4. 目标架构

```text
RenderChunkManager
  ├─ 维护 renderable 空间索引
  ├─ 计算每名玩家的 MineRails audience
  └─ 调用 RenderAudience.add/remove

DisplayRenderBackend
  ├─ NativeDisplayRenderBackend
  │    └─ Bukkit ItemDisplay / BlockDisplay
  └─ EntityLibDisplayRenderBackend
       └─ EntityLib WrapperEntity + typed Display metadata
```

模型层不再直接依赖某一种实体实现，而持有后端无关的 visual handle：

```text
DisplayVisual
  ├─ create / destroy
  ├─ showTo / hideFrom
  ├─ teleport
  ├─ setTransformation
  ├─ setItemStack / setBrightness
  └─ addPassenger
```

`RenderChunkManager` 继续作为 MineRails 唯一 audience 真相来源，不采用 EntityLib 的实验性 `ViewerEngine` 替换现有区块索引。

## 5. 分阶段实施

### Phase 0：依赖与可重复构建

- [x] PacketEvents 使用 `implementation`。
- [x] EntityLib 使用 `implementation`。
- [x] 验证 shadow jar 中依赖、relocation 和 service/resource 合并结果。
- [ ] 决定并锁定兼容版本组合；优先验证 PacketEvents 2.13.x 与对应 EntityLib 发布版本。
- [ ] 禁止使用会随远端变化而不可重现的未锁定 SNAPSHOT 作为最终发布依赖。
- [ ] 检查 GPL-3.0 分发义务并更新发行许可证清单。

验证：

```bash
./gradlew :BukkitPlugin:compileKotlin :BukkitPlugin:shadowJar
jar tf BukkitPlugin/build/libs/*.jar | grep -E 'packetevents|entitylib'
```

### Phase 1：配置与后端生命周期（已完成首版）

RED：

- 为 `native`、`entitylib`、未知值、缺失字段和距离边界增加配置测试。
- 为所选 backend 初始化失败增加测试。

GREEN：

- 增加 `DisplayRenderBackendType` 和严格配置解析。
- 在 BukkitPlugin 启用早期初始化 embedded PacketEvents，再初始化 EntityLib。
- 在停服时按相反顺序关闭虚拟实体、EntityLib 和 PacketEvents。
- 仅在 `entitylib` 被选择时启动 packet runtime；`native` 不改变当前生命周期。

约束：

- 不允许 native 与 entitylib 同时持有同一 visual 的客户端可见实体。
- 初始化必须发生在世界恢复和模型创建之前。

### Phase 2：后端无关接口与 native 适配（已完成）

RED：

- 为 visual 生命周期、viewer 幂等性、transform、item/block state 和 passenger 关系定义契约测试。

GREEN：

- 新增 `DisplayRenderBackend` / `DisplayVisual`。
- 用 `NativeDisplayVisual` 包装当前 Bukkit 实体调用。
- 依次迁移：
  - `RailExtension.kt`
  - `ModelTreeBuilder.kt`
  - `DisplayModel.kt`
  - `LodDisplayModel.kt`
  - `CompositeModel.kt`
  - 旧 `render/config/ModelConfig.kt`

验收：选择 `native` 时视觉、动画、LOD、座位和平台行为与迁移前一致。

### Phase 3：静态轨道 EntityLib PoC（代码与无玩家启停验证已完成，等待多人实机）

优先迁移 `RailExtension`，因为静态轨道：

- 没有 passenger 树；
- 没有每 tick teleport；
- 没有交互和碰撞；
- 只有 ItemDisplay / BlockDisplay metadata。

实现：

- 使用 `WrapperEntity(EntityTypes.ITEM_DISPLAY/BLOCK_DISPLAY)`；
- 使用 `ItemDisplayMeta` / `BlockDisplayMeta`；
- audience 进入时 `addViewer`，离开时 `removeViewer`；
- 所有 viewer 离开后允许释放 visual；
- 玩家退出、换世界、世界卸载和轨道删除时发送 destroy 并清理索引。

实机验收：

- 64、256、512 格边界；
- 两名玩家配置不同距离；
- 目标区块未发送时仍可看见虚拟轨道；
- 无 native/virtual 双重模型；
- 重连、切维度和世界卸载后无残留实体。

### Phase 4：列车 Display 与 passenger 树（代码完成，等待多人实机）

- [x] root、body、bogie 和 child Display 均改为 visual handle；
- [x] 创建 root 与 children 后建立 passenger 关系；向玩家显示时先 spawn children，再由 root spawn 发送 passenger packet；
- [x] 根移动使用 teleport，局部部件变化使用 Display transformation metadata；
- [x] item、custom model data、item model、brightness 动画继续走 visual metadata；
- [x] transformation metadata setter 使用批处理，避免四个字段各自发包；
- [ ] 单 tick 的 spawn + metadata + passenger 尽量 bundle。

风险验证：

- 高速列车移动平滑；
- teleport/interpolation 参数与当前 Bukkit 实现一致；
- passenger 重绑、模型重载和列车销毁无客户端残影；
- ViaVersion 客户端不在首个稳定范围内，除非单独完成协议矩阵测试。

### Phase 5：逐玩家 LOD 与视距设置（代码完成，等待实机）

- [x] `LodDisplayModel` 不再直接调用 Bukkit `showEntity/hideEntity`；
- [x] 根据玩家距离只为 lo 或 hi visual 保留 viewer；
- [x] 玩家修改设置后强制刷新当前世界 audience；
- [x] `ViewDistanceManager` 使用 UUID 而非强引用 `Player`；
- [x] 玩家退出时清理设置缓存和全部 virtual viewer。

### Phase 6：性能、观测和发布

增加指标：

- 每 tick virtual spawn / metadata / teleport / destroy 包数；
- 活跃 visual 数与 viewer 关系数；
- audience reconcile 耗时；
- 每名玩家远距离列车/轨道数量。

发布闸门：

- native 全量回归通过；
- entitylib 在 Paper 1.21.11 完成多人实机测试；
- 不支持的服务端/客户端版本给出清晰错误；
- 管理员文档包含依赖、重启要求、距离成本和回退步骤。

## 6. 测试策略

### 单元测试

- 配置严格解析；
- audience 集合差分；
- viewer add/remove 幂等性；
- LOD 选择；
- `max-blocks / 64` viewRange 换算；
- visual snapshot 到 EntityLib metadata 的转换；
- metadata dirty batching。

### 集成/实机测试

- Paper 1.21.11；
- 玩家客户端视距低于/高于 MineRails 距离；
- 未加载远端区块的虚拟 Display；
- 静止轨道、大量静态轨道、移动列车；
- 动画、开关门、LOD；
- 玩家加入/退出/死亡/传送/切世界；
- 世界加载/卸载、插件停服、列车删除和模型重载。

## 7. 回退策略

- 配置改回 `display-renderer.backend: native` 并重启；
- 持久化格式不记录 backend 专属对象，因此切换不迁移世界存档；
- entitylib 初始化失败时不创建任何世界或 visual，避免半初始化状态；
- 在 entitylib 达到发布闸门前，默认始终保持 `native`。

## 8. 预计修改文件

主要现有文件：

- `BukkitPlugin/build.gradle`
- `BukkitPlugin/src/main/resources/config.yml`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/BukkitPlugin.kt`
- `.../config/MineRailsConfig.kt`
- `.../render/chunk/ViewDistanceManager.kt`
- `.../render/chunk/RenderChunkManager.kt`
- `.../extension/rail/RailExtension.kt`
- `.../config/model/ModelTreeBuilder.kt`
- `.../render/model/CompositeModel.kt`
- `.../render/model/DisplayModel.kt`
- `.../render/model/LodDisplayModel.kt`
- `.../render/config/ModelConfig.kt`

预计新增目录：

```text
BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/backend/
BukkitPlugin/src/test/java/work/art1st/mineRails/bukkit/render/backend/
```
