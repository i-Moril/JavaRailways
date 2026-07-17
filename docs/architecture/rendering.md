---
title: 展示实体渲染架构
slug: /architecture/rendering
---

# 展示实体渲染架构

MineRails 使用 `RenderChunkManager` 维护轨道和列车 renderable 的空间索引，并按玩家位置决定 MineRails audience。列车普通部件 Display 作为 passenger 挂载到 root Display；逐玩家 LOD alternatives 使用世界坐标独立更新；座位、交互命中箱和碰撞平台则是独立的真实 Bukkit 实体。

## 当前实现

`feat/packet-entity` 分支已提供启动时可选的两种视觉后端：

```text
RenderChunkManager
  ├─ RailExtension → selected DisplayRenderBackend
  └─ CarExtension  → CompositeModel
                         ├─ root DisplayVisual
                         ├─ body/bogie DisplayVisual passengers
                         └─ per-player LOD DisplayVisual
```

逐玩家 MineRails 距离由 `RenderChunkManager` 计算。`native` Display 仍受 Paper 原生实体追踪距离、玩家 send view distance 和客户端 `viewRange` 限制；`entitylib` 轨道和列车视觉由 MineRails 显式维护 viewer，可显示在原生实体追踪范围之外。

## 可选后端

管理员可以选择：

| 后端 | 实体来源 | 使用场景 | 约束 |
|---|---|---|---|
| `native` | Bukkit/Paper 真实 Display | 默认兼容模式 | 受原生实体追踪和区块发送距离限制 |
| `entitylib` | EntityLib + PacketEvents 虚拟 Display | 实验性超远逐玩家视觉 | 轨道、列车 root/body/bogie/LOD 为虚拟 Display；座位、交互与碰撞仍为 native |

配置：

```yaml
display-renderer:
  backend: native
  player-distance:
    default: player-view-distance
    max-blocks: 512.0
```

`max-blocks` 必须在 `(0, 4096]` 内。未单独设置玩家时，默认距离为 `min(玩家客户端视距 × 16, max-blocks)`。

后端不支持热切换。管理员修改后必须重启，确保旧后端实体全部销毁后再重建。

:::warning 实验性范围
`entitylib` 已迁移轨道及列车视觉，但座位、Interaction 命中箱和碰撞平台仍按原生路径运行。远距离看到列车不代表可以远距离点击、乘坐或发生碰撞。
:::

## 稳定边界

### 保持真实实体的功能

以下对象影响服务端规则，不会仅为了远距离视觉而虚拟化：

- `InteractionModel` 的点击命中箱；
- `SeatModel` 的座位和载具；
- `PlatformModel` 的碰撞实体。

远距离 entitylib 后端只负责看见模型，不提供超远距离点击、乘坐或碰撞。

### Audience 的唯一来源

`RenderChunkManager` 继续负责：

- renderable 的区块索引；
- 玩家和 renderable 移动后的增量可见性；
- 每玩家展示距离；
- 玩家退出、切世界和世界卸载清理。

EntityLib 的 viewer 集合仅执行 audience 结果，不取代 MineRails 的空间索引。

### 线程边界

模型状态来自 Core 已发布的 render snapshot；Bukkit 与 packet visual 的提交仍在主线程 render commit 阶段执行。PacketEvents 的网络写入可以进入 Netty，但 MineRails 不应从物理 worker 直接读取 Bukkit 玩家或修改 viewer 集合。

## 依赖策略

bootstrap 阶段使用：

```gradle
implementation("com.github.retrooper:packetevents-spigot:2.11.1")
implementation("io.github.tofaa2:spigot:3.0.3-SNAPSHOT")
```

两个库会被 Shadow 重定位，避免与服务器安装的 PacketEvents/EntityLib 冲突。当前组合已通过 Paper 1.21.11 无玩家启停测试，但不是最终兼容承诺。发布前仍必须：

1. 锁定并验证 PacketEvents 与 EntityLib 的兼容版本组合；
2. 实机验证 Display metadata、远距离 viewer、passenger 和移动包；
3. 避免最终发行依赖不可重现的远端 SNAPSHOT；
4. 确认随 fat jar 分发 GPL-3.0 依赖的许可证义务。

具体实施阶段、测试矩阵和回退策略见 `ai_tasks/packet_entity_rendering_plan.md`。
