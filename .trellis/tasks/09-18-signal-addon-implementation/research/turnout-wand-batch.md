# 独立道岔 Wand：本批实施边界

## 目的与范围

用户批准先实现已经确定的部分并提交，信号规则预设暂时搁置。
本批补上进路录制之前的手动道岔操作入口：主插件提供独立 Wand，选来向、选支路，
显示真实当前连接及成功/拒绝原因。原 RailWand 的道岔模式仍只建造轨道。

不实现闭塞/保护区域/信号机 Wand、完整区域通路录制与高亮、测距、TextDisplay 全局覆盖、
规则预设、失效策略变更或新资源管理器。不修改 Core/Basic 的选择和授权模型。

## 方法与预计修改位置

- `BukkitPlugin/.../tool/TurnoutWand.kt` 及必要的局部纯选择 helper：复用 AbstractWand、已有选轨和端点定位。
  左键选择道岔来向，右键指定合法出口；设置/提示说明操作。选择只保留稳定端点与其世界、拓扑依据，
  不持久化可变轨道对象或授权。当前通路来自真实 active continuation，不把点击意图当成功状态。
- `BukkitPlugin.kt`、`CloudCommandRegistrar.kt`、`JavaRailwaysCommand.kt`、权限/语言资源：
  注册与发放工具，沿用既有命令和本地化，不增加插件或独立调度服务。
- 工具/命令测试：选取、非法连接、拓扑过期、世界变化、拒绝不伪造成功、权限与命令建议。
- 主插件 owner spec 和玩家说明：说明操作与“切换不等于授权”，不宣称区域级录制完成。

切换统一调用已有 `InfrastructureResourceService.selectTurnout`，由它验证版本、合法端口、
临时资源冲突和物理占用，再执行实际选择；不直接写 active track。
只在玩家操作时做局部查询，不引入每 tick 遍历世界或第二套资源锁。
Folia 的玩家 UI 在 player owner 上；若需要异步处理，只跨线程传不可变值并复核世界/选择，
不阻塞等待 Bukkit owner。成功与拒绝之后都显示实际连接，而不是预测连接。

## 验证

使用真实 Core 拓扑验证端点与合法 continuation，复用既有手动选择安全测试。
新增工具/命令回归，运行 `:BukkitPlugin:test`、Java consumer 编译和 `:BukkitPlugin:shadowJar`，检查工具及语言资源实际入包。
沿用现有 JDK25/Gradle9.5 缓存并排除无关 WebUI 前端安装/构建，不改构建环境。
独立检查后提交，只包含本批及此前已确认的计划记录；不推送。
没有真实 Folia/客户端时明确标注未验证，不以替身 UI 测试冒充实际画面。
