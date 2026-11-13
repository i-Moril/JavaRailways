# platform 乘客移动：执行与验证计划

## 开始条件

- 当前分支 `feat/passenger-movement`，基于本地 `main` 的 `5aa13ea2`；远端认证尚不可用，不能声明远端已验证最新。
- 用户确认视角相对方向、固定速度和跨平台交接。
- 用户在最终规划摘要及调度澄清后明确回复“可以。允许修改代码。”，实施已获批准。开始前已修正跨 chunk 不等于跨 region 的规划表述。
- 不启动或部署游戏服务器，除非后续获得对应授权。

## 有序执行清单

- [x] 1. 最终规划获批后激活任务；按 Trellis 子代理模式派发 `trellis-implement`，提示以 `Active task: .trellis/tasks/11-13-passenger-movement` 开头并加载已整理的上下文。`lifecycle-and-threading.md` / `interaction-render-persistence.md` 超过原生注入的单文件 32768 字节上限，implement/check 子代理须从磁盘补读完整相关章节，尤其关闭与 GentleUnload 部分，不能把截断注入当作完整规范。
- [x] 2. 将 `PlatformModel` 的预激活与真实乘客支撑区域分开；发布复制后的几何/姿态与模型生命周期身份，不跨线程暴露 `ModelState`。
- [x] 3. 优先复用已有整列不可变快照与 `CarExtension` 平台登记建立同来源场景，验证静止、来源失效及模型重建；仅在实际集成需要时最小接入 `WorldManager`，保持既有 Core 渲染通知/分区调度合同。
- [x] 4. 实现视角相对、固定 `4.3 m/s` 的归一化行走与沿运动路径的连续平台交接；先写有意义的坐标、斜向、坡度、重叠/空隙和剩余步长测试。
- [x] 5. 接入每位玩家单独的 carrier 与 session，复用 `PacketEntityMotion` / `PacketVirtualSeat` / `SeatAuthorityMover`。需要时最小提取现有 carrier 配置，避免复制 seat registry。
- [x] 6. 接入 Shift 主动 dismount 拦截、忽略跳跃、区域外释放及外部传送/死亡/退出/销毁/世界卸载/插件关闭。旧回调不能拉回玩家或重新创建资源。
- [x] 7. 将 manager 放入 `BukkitPlugin` 的启动与清理顺序；检查现有点击座位、驾驶输入和 audience 未发生职责冲突。
- [x] 8. 完成聚焦测试与模块构建，再派发 `trellis-check` 审查整个改动范围；主会话核对修复、证据及未通过边界。
- [ ] 9. 在明确获准的真实开发服执行下列客户端验收；若尚未获准或未执行，保留为未验收，不宣称功能实机可行性已证明。
- [x] 10. 按实际实现更新 Bukkit spec，精确暂存本任务文件并提交；不推送或部署。

## 预期改动文件

源码根目录为 `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/`：

- `render/model/PlatformModel.kt`：乘客支撑区域与值快照。
- 新增内部乘客会话、几何和场景管理文件：单一 owner，避免平台分散控制玩家。
- `extension/train/CarExtension.kt`：车厢平台登记、姿态复制、生命周期失效。
- `logic/WorldManager.kt`：不是预定必改文件；仅在无法直接复用现有整列帧时接入已有发布/完成边界。
- `BukkitPlugin.kt`：manager composition/start/stop。
- `render/model/SeatModel.kt`：仅在需要共用 carrier 配置时最小提取；现有座位行为不变。
- `render/backend/PacketEntityMotion.kt` 及关联 helper：优先直接复用；只有实际集成所需才调整内部接口。
- 对应 `BukkitPlugin/src/test/`、Bukkit spec 与当前任务文档。

若必须扩展 Core、持久化或模型配置字段，应暂停扩大实现范围，回到规划说明原因并读取对应版本规则。

## 验证命令

新增测试的类名由实现命名，先聚焦运行新几何、场景发布、会话生命周期与既有 Seat 测试，再运行：

```bash
./gradlew :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar
git diff --check
```

若只因环境问题无法完成，记录真实失败命令和边界，不把静态检查或 mock 当作实际客户端验收。通过后不无理由重复完整构建。

## 游戏验收

- 静止、启动、制动、弯道、坡道中不按键随车移动。
- 转头后 WASD 跟随水平视角，斜向不加速，疾跑输入不加速。
- 两名玩家各自移动，Shift 不下车、跳跃不生效；离开后恢复普通输入。
- A→B→A 往返保持相同 carrier/虚拟挂载，不居中、不在重叠处反复切换；曲线与坡道连接单独观察。
- 离开整片连续区域恢复普通运动；真实空隙明确记录为本轮不桥接。
- 外部传送、死亡、退出、列车/模型删除、世界卸载和插件停止不残留会话或实体。
- Paper 与 Folia 分别记录；未测试的平台不得被另一平台测试结果覆盖。

## 回退点

几何/场景发布与实体会话保持清楚的提交边界；所有数据为临时运行态。回退前先结束会话并完成 carrier 清理，再回退本功能提交；不涉及存档迁移。最敏感的现有路径为 `SeatModel` 的驾驶席绑定/下车和 `WorldManager` 的逐列完成回调，必须保留已有测试并在审查中重点核对。

## 当前验证结果

实现与独立代码审查完成，20 项 Passenger 和 12 项 Seat 测试通过，插件打包成功。完整测试为 956 项、1 项既有 S100 ZIP/source 基线失败、1 跳过；临时增加 Test 堆到 2 GiB 后无 OOM。第 9 项游戏内验收仍未执行，任务不归档为已完整验收。证据见 `research/validation.md`。
