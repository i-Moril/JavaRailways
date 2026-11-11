# Folia 停服临时资源审计（2026-11-10）

## 范围与证据等级

审计当前工作区全部生产 Java/Kotlin 原生实体插入路径、虚拟实体、玩家持久化与工具状态；没有启动服务器或检查真实 Anvil/playerdata。源码结论不能替代实服存档验收。本轮接受单 tick worker 热禁用可能超时，添加诊断，不改变关闭线程架构。

## 原生实体

共 10 个 World.spawn 调用点：NativeDisplayRenderBackend 的 ItemDisplay/BlockDisplay；SeatModel 的 Interaction/ArmorStand；InteractionModel 的 Interaction；PlatformModel 的 Chicken/Shulker；RailWand 的 ItemDisplay/TextDisplay；RailObjectWand 的 ItemDisplay。全部设置 isPersistent=false。本轮将 Seat hitbox、InteractionModel、RailObjectWand 三处标记从 spawn 返回后移到 spawn consumer 中，并将 RailWand 标记放在 initializer 首句。NativeDisplay.configureCommon 本来就在 consumer 内，无需修改。

Folia 的碰撞平台使用 PacketPlatformCarrier；EntityLibDisplayRenderBackend、PacketPlatformCarrier、PacketVirtualSeat 只创建客户端数据包实体，不插入 Bukkit 世界，不存在 Minecraft entity save 路径。nms/PlatformEntity.kt 是注释代码；Core/Basic/TBB 没有其他原生实体插入路径。

## 玩家与非临时数据

缓存 Paper 1.21.11 devbundle output.jar 的完整源码确认：CraftEntity.isPersistent 直接返回 entity.persist；PlayerList.save 仅检查玩家自己的 persistent 并调用 playerIo.save；ServerPlayer.saveParentVehicle 遍历载具链，非持久化祖先只使 RootVehicle 子节点不写入，不影响其余玩家数据。JR 不修改玩家 persistent，所以玩家正常保存，临时座椅不会由 RootVehicle 再次写入。

匹配上游：https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/patches/sources/net/minecraft/server/level/ServerPlayer.java.patch 。缓存证据为 /home/user/.gradle/caches/paperweight-userdev/v2/work/applyDevBundlePatches_c9a53f01acfb65c4a1dc2a7800c5c82b06fd8bca489383ff45ca9abd7808383a/output.jar。

有意持久化的边界仍保留：JR split world snapshots、Wand item PDC、列车钥匙 world/train 绑定，以及 TBB 用户明确执行的方块修改。没有发现运行时修改玩家 gamemode/flight/collision/visibility/effect/health 等需要关闭恢复的状态，也没有临时真实方块修改；工具预览为非持久化实体或客户端效果。

AbstractWand 在交互结束、模式切换时立即 persistState；RailWand 设置确认 syncConfigToState、装饰选择回调、RailObjectWand 类型设置以及已检查异步操作回调均立即写 PDC。未发现当前持久化字段变更缺失写回的具体路径。热禁用保留 close 最后 flush；Folia 真正停服时不再扫描在线玩家背包，依靠运行中的写回以及 Minecraft 随后的玩家保存。新增状态变更必须在正确玩家 owner 上立即持久化，不能依赖停服时扫描。

## 停服策略

仅 platformCapabilities.folia && server.isStopping 为真时，PlatformCleanup batch 跳过 runAt/runForEntity/runForEntityLater 的 owner 动作，不尝试错误线程操作原生实体，也不等待已停止的区域 tick。保留服务注销、producer/physics 停止、JR 最终快照和 writer 完成、模型本地引用释放、自己的数据包 listener、DI、HTTP/执行器清理。普通热禁用和 Paper 保留 owner cleanup 完成屏障。

## 单线程诊断

Folia 26.1.x TickRegions.getScheduler().getTotalThreadCount() 返回实际存活 worker 数。初次 onEnable 可能在 scheduler start 之前，因此放在启用后首个 global callback，沿用 dispatcher lifecycle gate。仅计数 1 给出单线程清理超时警告；未知/反射失败明确记录诊断不可用，不从 CPU 或 threads=-1 猜测。
