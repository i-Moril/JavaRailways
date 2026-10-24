# 第一批修复（2026-10-23）

> 已修复项的触发范围、原示例纠错及测试证明边界，见 [fixed-findings-reaudit.md](fixed-findings-reaudit.md)；本页测试数字为当时运行记录。

用户授权修复前次划定的九项。未创建 Trellis 任务，未提交、推送或部署。原有 SimpleSignals 修改不属于本批。

## 已实施

- BUK-11：显式列车 ID 不存在时拒绝删除，仅无 ID 时找最近列车。`TrainDeletionSelectionTest` 覆盖查找回调不被误调用、指定目标、最近目标和无目标。
- BUK-10：HTTP rawPath 先分段，再对每段百分号解码一次，保留加号；query 原语义保留。`TimetableHttpPathTest` 覆盖加号、编码加号、空格、字面百分号、编码斜杠和中文。
- BUK-12：Cloud 注册 issues 路由及可选参数。`CloudCommandRegistrarTest` 验证注册表。
- BUK-08：内联 YAML 变换仅由 definition 持有，保留 import 的附加变换。`ModelTreeBuilderTest` 使用真实临时 YAML 加载与构建。
- BUK-07：地图 marker 签名加入 railId。`RailMapPreviewServiceTest` 先渲染、仅更换 marker 所属轨道，再验证响应及磁盘文件更新。
- BUK-16：marker 句柄记录 world UUID，重命名只作用于同世界。`RailObjectMarkerRenameTest` 覆盖多玩家、异世界同 ID 和待生成实体。
- BUK-19：动画离散外观只使用已到达关键帧。`AnimationTest` 覆盖到达前、准确到达、延续和反向播放。
- ATO-03：Depot token/state/assignment 校验成功后才删除账本。`DepotStoreTest` 覆盖拒绝取消不破坏有效分配及后续释放。
- ATO-05（扩展 API 合同，未找到内置具体 action provider）：停止前已终态的扩展 ticket 直接清理；pending ticket 仍须取消并确认。`TimetableDispatcherTest` 覆盖三种终态及 pending 成败。

## 验证

- 静态复核：九项生产调用链和新增测试已独立复核，未发现新增确定性逻辑问题。
- WebUI 前端：21 个测试文件、108 项测试通过。
- Gradle：`BUILD SUCCESSFUL`。`:BukkitPlugin:test :SimpleATO:test :Core:test :Basic:test :WebUI:test :BukkitPlugin:compileSignalApiConsumerJava` 通过；Basic 测试任务使用 up-to-date 结果，其余四个测试任务本次执行。报告计数：Core 505、Basic 390、SimpleATO 380、WebUI 12、BukkitPlugin 554；均无失败、错误或跳过。
- 日志：`/tmp/jr-first-batch-tests.log`、`/tmp/jr-first-batch-frontend.log`。首次 Gradle 因沙箱本地锁网络接口限制未启动；使用本机 JDK 25 在获准的沙箱外运行后通过。
- `git diff --check`：通过。

## 验证边界

命令、HTTP 分段及 marker 重命名回归调用生产使用的 helper，不等同于真实 Paper/Folia 命令、HTTP transport 或游戏实体调度验收。未启动服务器或做游戏内验收。本批不包含第二批问题、源码目录迁移或 Wand 架构重构。
