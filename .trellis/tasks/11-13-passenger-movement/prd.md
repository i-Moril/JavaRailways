# 验证 platform 区域内的乘客强制移动

## Goal

验证玩家进入列车已有的 platform 区域后，能够通过强制挂载稳定随车移动，并使用 WASD 改变自己在车内的位置；走出边界后恢复普通运动。当前目标是验证该控制方式的实际可行性。

## Confirmed Context

- 用户指定从 `main` 创建 `feat/passenger-movement`。分支已创建，基线是本地 `main` 的 `5aa13ea26280b5e8292c8c29723bc2eb52f4822a`，与缓存的 `origin/main` 相同。规划时远端认证不可用；2026-11-13 部署时使用 `/run/host-ssh-agent/agent.sock` 核对远端 `main`，仍为同一基线；没有推送功能分支。
- 已获得创建 Trellis 任务并规划的同意；用户在最终方案及调度澄清后明确回复“可以。允许修改代码。”，批准进入实施。
- 当前 `PlatformModel` 的区域检测用于提前激活碰撞实体，含额外的横向和纵向范围；不能把它直接作为强制上车条件。证据：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/PlatformModel.kt:152`、`:228`。
- 已有座位后端维护真实服务端乘坐实体与客户端虚拟座位，可作为乘客移动的复用基础。证据：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/PacketEntityMotion.kt:116`。
- 已有驾驶输入监听使用 `PlayerInputEvent`。证据：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainHandleInputListener.kt:34`。

## Requirements

- R1：玩家进入列车实际 platform 区域后，自动挂载到独立的隐藏承载实体；每位乘客能够独立移动。
- R2：不按移动键时，玩家保持相对于 platform 的位置，随列车平移、转弯及升降；WASD 按玩家当前视角的水平朝向改变局部位置，使用固定移动速度，疾跑输入不改变速度。
- R3：受控期间禁止通过 Shift 主动下坐骑，禁止跳跃。该限制只在本功能管理的乘坐会话内生效；正常离开后不保留输入限制。
- R4：支持同列车相邻 platform 连续交接；交接时保持同一承载实体与乘坐关系，只变更所属平台与局部坐标。离开当前平台且不存在连续可达的目标平台时，解除挂载，在对应世界位置恢复普通运动。真实空隙按离开区域处理，本轮不增加跨空隙的连接面。
- R5：本轮不解决乘客从车侧或端部掉出的问题，不加入门控、车墙/座椅碰撞和防坠落行为。
- R6：死亡、退出、被传送、换世界、列车/模型销毁、世界卸载和插件停止时，结束受控会话并释放承载实体与任务；Shift 限制不得阻止这些生命周期动作。

## Acceptance Criteria

- [ ] AC1 / R1：玩家实际进入 platform 才被接管；位于现有显示预激活范围、但不在乘客区域内的玩家不会被吸入。
- [ ] AC2 / R2：静止、启动、制动、转弯和坡道上，不按键的乘客保持局部位置；两名乘客能分别使用 WASD 移动；转头后移动方向跟随视角，斜向与直向速度相同，疾跑不加速。
- [ ] AC3 / R3：受控期间按 Shift 不下车，按跳跃键不跳跃；退出受控状态后两者恢复原版行为。
- [ ] AC4 / R4：相邻 platform 之间往返时不解除/重建挂载、不复位到目标平台中心；走出可交接区域才下车，不被旧局部位置拉回，也不遗留乘坐关系或移动任务。
- [ ] AC5 / R5：能从边缘走出即可，不以车门判断、防坠落或完整车厢碰撞作为通过条件。
- [ ] AC6 / R6：异常结束路径不残留乘客会话、实体、输入限制或旧生命周期回调。
- [ ] 记录真实客户端的第一人称平滑度、骑乘高度、上下车连续性，以及 Paper/Folia 的验证边界；单元测试通过不等于实机可行性已验证。

## Out of Scope

- 车门通行限制、车墙/座椅碰撞、防坠落与完整原版步行物理。
- 客户端模组、Core 列车动力学与运营系统改动。
- 未经单独确认的模型配置字段、世界存档或其他持久化格式修改。
- 跨空隙连接面、跨不同列车的自动交接。
- 本次规划阶段的部署或实机服务启动。

## Planning Status

已根据用户确认的三项产品选择完成需求收敛，并获得实施批准。功能实现、对应 32 项 Passenger/Seat 测试和打包完成；完整测试仍有一项已证明与本轮无关的 S100 ZIP/source 基线失败。真实客户端验收尚未执行，任务保持 `in_progress`，详细证据见 `research/validation.md`。

## Deployment Status

用户明确要求使用 PlugManX 及 `/run` 下的 SSH agent。已完成测试服热部署和依赖插件恢复，JVM PID 保持不变；真实客户端验收仍待执行。首次级联卸载的 SimpleSignals/ATO 错误及后续恢复结果完整记录于 `research/deployment-20261113.md`，不能把整个过程报告为无错误。
