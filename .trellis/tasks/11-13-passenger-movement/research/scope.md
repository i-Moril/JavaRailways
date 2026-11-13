# platform 乘客移动：源码证据与预计改动范围

## 考查基线

- 本地分支：`feat/passenger-movement`，从本地 `main` 的 `5aa13ea26280b5e8292c8c29723bc2eb52f4822a` 创建。
- 本地 `main` 与缓存 `origin/main` 一致；远端访问认证失败，不声称已验证远端最新。
- 上一轮考查分支与 `main` 的差异仅为 SimpleATO 测试及测试构建配置，本页涉及的 Bukkit 源码在两者间相同；本轮已在新分支重新核对接入点。
- 本文件记录预计范围与技术问题，不代表产品决策已获批准，也不构成实施授权。

## 已有实现与复用边界

1. `render/model/PlatformModel.kt`：拥有 platform 局部网格、世界变换、观众预激活及碰撞实体生命周期。`containsPlayer` 的反向变换可复用思路，但现有范围包含横向 3/5 米的预激活/退激活余量，纵向范围也不是脚底支撑面，不能直接作为上车条件。
2. `config/model/PartPlatformConfig.kt`：现有 rows、columns、spacing 足以表达本轮平台外缘；S100 的 `floor_platform.yml` 为 3×18、间隔 1 米，车辆配置将其放在 local y=-1。当前信息不要求扩展 YAML schema。
3. `extension/train/CarExtension.kt`：拥有车厢 platform 列表，通过 `refreshInteractiveActivation` 接入玩家附近的互动对象，并在模型更新中发布部件变换。这里可以提供车厢归属与逐帧 platform 姿态。
4. `render/backend/PacketEntityMotion.kt`：`registerSeatCarrier`、`renderSeatCarrier`、`syncSeatAuthority` 已将真实服务端承载实体与客户端虚拟座位分开处理，已有乘客 audience 刷新。
5. `render/backend/SeatAuthorityMover.kt`：在实体 owner 上移动真实承载实体，并立即更新乘客服务端位置，避免依赖每 tick 传送玩家。
6. `render/backend/PacketVirtualSeat.kt`：为每个承载实体提供稳定客户端挂载实体与乘坐包重定向；其基础能力可复用，无需再建第二套 packet 座位协议。
7. `render/model/SeatModel.kt`：现有点击座位与驾驶席拥有自己的绑定、下车回调及销毁流程。不能把动态行走会话直接冒充驾驶席；当前座位下车后下一 tick 对齐固定位置的行为也不能原样套给越界下车。
8. `control/TrainHandleInputListener.kt`：证明项目当前已支持 `PlayerInputEvent`；乘客输入应独立分派，保留驾驶席按键职责。

上述 Kotlin 路径相对于 `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/`；模型例子位于 `examples/models/s100/`。

## 预计代码范围

- **平台几何与姿态**：调整 `PlatformModel.kt`，提供真实乘客区域及不可变姿态数据，将乘客接管条件与现有碰撞实体预激活区分开。
- **新增乘客会话管理**：集中管理每位玩家的 platform 归属、局部坐标、最新按键、承载实体、每 tick 移动、Shift 主动下车拦截、越界释放与异常结束。
- **列车接入**：在 `CarExtension.kt` 注册/更新/移除列车 platform，沿用同一列车帧的变换；是否需要 `ModelTreeBuilder.kt` 补充平台 owner，待最终设计确定。
- **整列发布边界**：在 `logic/WorldManager.kt` 的既有整列分区渲染完成边界发布同来源、同修订的平台快照集合。原因是 `scheduleTrainFrame` 将车厢按位置分派到不同 region，逐平台发布会使交接把前车的新姿态与后车的旧姿态混用。静止车厢复用最后完整快照，不改 Core 的 stationary mailbox suppression。
- **生命周期接入**：在 `BukkitPlugin.kt` 创建、启动和停止会话管理器，与现有世界/模型清理顺序衔接。
- **承载实体复用**：复用 `PacketEntityMotion`、`PacketVirtualSeat`、`SeatAuthorityMover`；仅在确有共享需要时从 `SeatModel` 提取承载实体配置/创建逻辑，不为行走复制整套座位管理。
- **验证与文档**：增加有意义的局部坐标、持续输入、边界释放和生命周期测试，以及真实客户端验收记录；更新对应 Bukkit spec。

预计集中在 BukkitPlugin。Core、Basic、SimpleATO、Decorations 和 WebUI 暂无必要改动；现有模型包预计无需改字段。若之后发现必须改变持久化格式，应先读取共享 versioning 与 schema evolution 规则并回到规划确认范围。

## 本轮确认与交接边界

- 用户已确认视角相对 WASD、固定移动速度，以及跨 platform 交接。
- 交接沿同一列车相邻且相接/重叠的 platform 区域进行；真实空隙仍按越界下车，不添加未授权的虚拟连接面或防坠落行为。
- S100 平台纵向覆盖 18 米，车体长度 17.8 米且两端连接长度各 0.1 米；直线条件下该配置为车间连接提供了几何基础。弯道的实际覆盖和第一人称连续性仍需游戏内验证，不能由这些配置数值推断为已经通过。
- 新发现的整列帧发布接入点已纳入设计和预计改动范围；不是修改 Core 帧结构或整列渲染调度协议。

## 尚需技术验证，不能误当作用户待答问题

- 实际脚底支撑高度：要依据现有 chicken/shulker 组合的碰撞面和实际客户端乘坐高度确定，不能直接拿部件 origin 当站立高度。
- 输入事件只代表更新后的状态；需保持最新输入并持续积分。原地转头后也应使用当前 yaw 计算按键方向。
- 静止列车仍需响应 WASD，不能只在列车 transformDirty 时更新乘客。列车姿态与乘客行走数据需在明确的 owner 边界汇合。
- Folia 下真实承载实体与玩家操作必须在正确实体/region owner 上执行；新旧渲染帧与会话结束不能互相覆盖。
- 多个 platform 争用同一玩家，以及跨 platform 交接后的旧回调，应由一个乘客会话 owner 统一处理。
- Shift 的目标是阻止主动下坐骑；死亡、外部传送、销毁等正常结束必须能释放。跳跃输入不产生竖直运动。
- 骑乘姿态、挂载高度、第一人称插值、延迟和越界释放连续性必须由真实客户端确认。当前不承诺等同原版站立行走。

## 验证计划边界

本轮规划只记录验证范围，不运行或启动开发服。后续实现应运行受影响的 `:BukkitPlugin:test`、`git diff --check` 与构建检查，保留现有 seat 测试。游戏内需覆盖静止、加速、制动、曲线、坡道、多乘客、Shift/跳跃、越界和生命周期结束；未实际进行的场景应明确标为未验收。
