# 实现与验证记录

## 最终实现

- 分支：`feat/passenger-movement`，基线为本地 `main` 的 `5aa13ea2`；实现提交时未推送、部署或启动游戏服务器，远端认证尚未核验。后续已按用户授权完成部署，并通过指定 SSH agent 核对远端 `main` 仍为该基线；见 `deployment-20261113.md`。
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

用户已做部分实机体验并报告随车 jerk 和边界停顿；挂载高度、第一人称连续性、曲线/坡道连接处、多人观感与游戏内生命周期的完整复验仍未通过。单元测试的 native/dispatcher doubles 不证明这些实际表现。后续已获得并执行测试服 PlugManX 部署，命令和服务检查通过；没有执行游戏客户端控制。任务仍保留待实机验收，不归档为已完整通过。

## 2026-11-13 实机反馈后的回归基线

用户已执行部分实机体验并报告：不按 WASD 仍相对车厢 jerk；到边界短暂卡住、继续移动才能下车（用户已纠正为并非拉回车内，静止时也发生）。AC2/AC4 有明确未通过反馈，不能继续笼统写成完全未尝试客户端验收。

修复前执行 `./gradlew --offline --init-script /tmp/jr-passenger-test-heap.gradle :BukkitPlugin:test --tests '*Passenger*' --tests '*Seat*'`，现有 32 项全部通过。日志 `/tmp/jr-passenger-feedback-baseline.log`，这说明现有测试没有覆盖显示帧相位和原生解除骑乘/重挂行为，不能用基线绿色否定用户反馈。修复与新增回归结果如下。

## 本轮反馈修复与最终验证

- 完整帧沿 WorldManager/CarExtension 的已接受显示路径进入乘客场景；虚拟 carrier 与本车模型进入同一 bundle。会话锁保留到该帧发送入队，旧 authority 受 revision/ending fence 约束。静止 WASD 复用场景，不独立追赶更新的物理帧。
- 边界交接先同步 rider/carrier，再原生 removeVehicle/resetPosition，保留取消事件；已有原生 teleport 未确认时只读查询其真实 pending 状态并保存越界意图，等待后释放。没有接管、伪造客户端确认或改变平台边界。
- 为打破已证实的 session→audience→CarExtension 与 car→session 锁环，乘客 native authority 仍立即移动，仅观众刷新合并并延后一 tick。spawn 与 close 的分配完成、旧回调和取消下车也纳入回归。
- 最终聚焦：54 项，0 failures/errors/skips；其中 PassengerMovementTest 22、PassengerGeometryTest 8。日志 `/tmp/jr-passenger-feedback-final-focused.log`。
- 独立 trellis-check 执行一次完整 `./gradlew --offline --init-script /tmp/jr-passenger-test-heap.gradle :BukkitPlugin:test :BukkitPlugin:shadowJar --continue`：968 项，966 通过、1 失败、1 跳过、0 errors。唯一失败仍为 `CarAnimationPlaybackTest.kt:85` 的 S100 ZIP/source 类型引用差异；相关包/源码/测试与 HEAD 均未改。日志 `/tmp/jr-passenger-feedback-full.log`，没有将完整门禁记为绿色。
- `shadowJar`、源码编译、`git diff --check` 及任务 JSON/JSONL 解析通过。JAR SHA256：`29411cc6b2dafcd1c29beab6b503555f479e35299c68655fd43c5588da9cd3dc`。
- checker 报告见 `check-passenger-feedback-20261113.md`，native 研究见 `passenger-feedback-20261113.md`。精确 26.1.2 类从实服只读提取，未改服务器；编译后 javap 确认 Kotlin VarHandle 调用使用实际连接参数类型，未生成 Object[] 错误签名。

此轮尚未部署或重载测试服。单元中的 pending 布尔与 entity/dispatcher doubles 只验证生产状态机和发送顺序；没有执行真实 Craft/NMS 交接或客户端画面，不能把它们报告为无卡顿/平滑的实机验收。

## 2026-11-13 玩家 owner 重新实现（历史方案与最终验证）

用户在实际源码比较后批准重新实现，并允许回到既有 commit。当前保留 `feat/passenger-movement` 的 `60201085` HEAD，把失败的 WorldManager 单一计算草稿及全部修改文件备份到 `/tmp/jr-passenger-world-draft-qujrqfhm`，只将其 8 个 Kotlin 文件恢复为 HEAD 后重做；没有重写分支历史。远端通过 `/run/host-ssh-agent/agent.sock` 只读核对仍为 `main=5aa13ea2`，没有远端功能分支，该 main 提交已包含于当前 HEAD。

根因和取舍：初版把乘客位置独立追随最新物理帧，无法保证和较慢的车厢显示相位一致；上轮把输入与车厢回调的更新用持锁连接，扩大了锁顺序和发包生命周期问题；未完成草稿又提交部分增量，因而需要拼补积压几何和其他乘客。它们分别是跨入口职责不清、调度合同与测试覆盖缺口，不是异步本身要求的复杂度。

本轮采用已有玩家 entity tick 唯一累计局部位置、不可变整份状态发布，车厢渲染唯一输出连续位置。列车分区域前采样一次，活动乘客列车使用可完整替换的显示依据，保留原有通用 latest-only 分发。停车走动复用已选完整姿态，未变化模型不重跑；旧 native 目标只由实际呈现顺序判旧，不能仅因玩家又走一步而取消已显示位置的真实同步。

本节自动化与最终实现证据待源码冻结及独立检查后补充。原有测试结果只适用于上方明确记录的历史版本；本轮尚未提交、部署或通过客户端复验。

### 停车简化路径的用户决定

用户明确要求停车时仅处理玩家移动，不进行整列车渲染。上方“活动乘客列车使用完整候选与 retained 帧”的方案已撤回，本轮不新增列车帧缓存、停车整列任务或通用队列合并。行驶走正常车厢批次，停车复用平台已接受场景，仅更新本人的 carrier。此前首次上车 B/C 推导未复现，不能记为已确认故障。多批次清理记录覆盖的推导同样没有实际故障证据，其组合等待建议已撤回；保留既有 ending/revision 与实际实体清理。用户随后明确“那你继续进行修复”，授权在这些边界内继续。

### 最终实现与本地验证

- 最终机制：玩家 owner 唯一累计 WalkingState；正常列车候选只增加一次采样的乘客清单，在匹配车厢 bundle 呈现。已接受场景速度为零时，renderStationary 只输出本人的 carrier，不进入列车分发器、不遍历模型、不新增 retained 缓存。common render 保留既有 native pending/veto、ending/revision 与最后批次清理；删除旧会话/包批次持锁和乘客 audience 延迟。
- 实现冻结时相对 HEAD 6e77496c：生产 6 文件 +233/-234，测试 2 文件 +302/-401。几何、SeatAuthorityMover、Core、通用 dispatcher 和构建配置均未改。外部追加的 Via 安装文档提交完整保留。
- 聚焦命令使用项目默认 Kotlin daemon，并临时设置 -Pkotlin.daemon.jvmargs=-Xmx4g，未修改 target/build 配置；目标 JVM25 编译通过。42 tests，0 failures/errors/skips：Movement18、Geometry8、PendingTrainFrames7、Gate1、LatestDispatcher4、Bundle2、SeatMovePlan2。日志 /tmp/jr-passenger-stationary-focused.log。
- 独立 trellis-check 未修改源码，未发现任务内待修项。完整命令：`./gradlew --offline --init-script /tmp/jr-passenger-test-heap.gradle -Pkotlin.daemon.jvmargs=-Xmx4g :BukkitPlugin:test :BukkitPlugin:shadowJar --continue`。日志 /tmp/jr-passenger-stationary-full.log。
- 最终 XML：963 tests，961 passed，1 failure，1 skipped，0 errors。唯一失败仍是 CarAnimationPlaybackTest.kt:85 的 S100 ZIP/source 类型引用路径差异；上方三份基线输入 SHA256 均保持不变。完整 test 命令退出1，不是全绿门禁。
- shadowJar 成功；产物 BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar，62,883,923 bytes，SHA256 `95460a583e99977e9a2000fe171c79227e6a807e020359b18743436ff46cdb6e`。已含新 WalkingState/WorldManager 接线、paper-plugin.yml 与 webui/JavaRailways-WebUI.jar。
- 所有8份候选源码与 /tmp/jr-passenger-stationary-sources/sha256.txt 在编译、完整测试、打包前后均一致；本轮未再出现 WorldManager 意外恢复。diff-check 通过。独立报告见 check-stationary-passenger-20261113.md。

本轮未部署、未推送。测试使用生产入口、真实 Core fixture 与 dispatcher/carrier doubles，不执行真实 Craft/NMS、Folia迁移或客户端插值，不构成第一人称平滑度、下车连续性或 tick 性能的实机验收。原客户端反馈仍需部署后复验，任务不归档。
