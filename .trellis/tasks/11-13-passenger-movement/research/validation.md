# 实现与验证记录

## 最终实现

- 分支：`feat/passenger-movement`，基线为本地 `main` 的 `5aa13ea2`；本轮没有推送、部署或启动游戏服务器，远端最新性仍未通过认证核验。
- 平台登记复制局部变换；会话每步只捕获一次既有整列 `Train.publishedFrame`，沿用共享模型姿态计算，无需改动 `WorldManager`。
- 视角相对 WASD、固定 4.3 m/s、禁止 Shift 主动下车和跳跃、同 carrier 的连续平台交接、区域外同步释放已实现。
- 复用现有 carrier packet/authority；清理使用 presentation owner，保留已排队的真实清理 completion。固定座位与驾驶输入保持原职责。
- 模块权威机制见 `.trellis/spec/bukkit-plugin/backend/passenger-movement.md`。

## 自动化证据

1. 既有基线：`./gradlew :BukkitPlugin:test --tests '*Seat*'` 成功，12 项通过。
2. 首轮聚焦：Passenger 16 项与 Seat 12 项共 28 项通过；之后新增真实双车厢、后续帧、pending cleanup 和其他生命周期用例。
3. 最终完整命令：`./gradlew --init-script /tmp/jr-passenger-test-heap.gradle :BukkitPlugin:test :BukkitPlugin:shadowJar --continue`。该临时脚本仅把 Test JVM 堆设为 2 GiB，没有修改仓库 build 配置。
4. 最终 XML：956 项，954 通过、1 失败、1 跳过、0 errors。PassengerGeometryTest 8 项、PassengerMovementTest 12 项、既有 Seat 12 项均通过，无跳过。
5. 最终源码 `compileKotlin` / `compileJava` 通过。单独运行 `./gradlew :BukkitPlugin:shadowJar` 成功退出，`git diff --check` 通过。
6. `trellis-check` 子代理完成全部产品源码与测试审查；已确认关闭调度器、首次同来源帧和取消死亡事件相关修复，没有剩余已确认的乘客功能缺陷。

日志位于 `/tmp/jr-passenger-seat-baseline.log`、`/tmp/jr-passenger-focused.log`、`/tmp/jr-passenger-full-2g.log`、`/tmp/jr-passenger-package.log`。最终测试报告位于 `BukkitPlugin/build/reports/tests/test/index.html`。

## 完整测试未通过的实际边界

`CarAnimationPlaybackTest.kt:85` 对 packaged S100 behavior 与解包源码做全文比较。ZIP 中类型引用为 `../../../types/train-behavior.d.ts`，源码为 `../../../schemas/types/train-behavior.d.ts`，断言因此失败。

本轮实际核对以下三份文件与实现前 HEAD 逐字节相同：

- `examples/models/s100.zip`：SHA256 `9d99056fdf4b47954a5bb2ddfea3c071bb86e1c96103cb4eb91fdf6f2c394389`。
- `examples/models/s100/behavior.js`：SHA256 `1396c14bd3141ac362d1579fa740597622f45e53867cd02c888568cfeeb99588`。
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/behavior/CarAnimationPlaybackTest.kt`：SHA256 `68b9933adf848f4ec0842e948f0494216014a89cd48b634496faeea38271800c`。

这项既有失败不在乘客任务范围内，未修改包、源码或测试来使其通过。完整测试门禁不能报告为绿色。

限制沙箱中的首次 Gradle 启动曾因 wildcard IP 不可用失败，在获准的正常执行环境中重跑。默认 Test 堆的完整运行曾在 395 项后发生 `OutOfMemoryError`；2 GiB 验证运行完成全部 956 项，没有再发生 executor/OOM 错误。

## 构建产物

- 路径：`BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`。
- SHA256：`8c666c4a1dc826ded68df53b147aff7a1621073ae0437fa8f38197a738af8d3d`。
- 已检查包含 PassengerMovementManager、PassengerWalking、PassengerPlatformRegistry 类及 `paper-plugin.yml`，不包含旧 `plugin.yml`。

## 尚未完成的验收

真实 Paper/Folia 的挂载高度、第一人称插值、曲线/坡道连接处、多人观感与游戏内生命周期验收尚未执行。单元测试的 native/dispatcher doubles 不证明这些实际表现。没有获得并执行测试服部署或游戏客户端控制；任务保留待实机验收，不归档为已完整通过。
