# 显示修复测试服部署（2026-11-12）

用户在解释旧编译数据与必填字段后明确批准更改，包含此前提出的备份、字段补齐、JAR部署、正常重启及资源重载。
未新建Trellis任务；继续本实现任务的已列出激活步骤。当前分支 `codex/train-display-in-service-refresh`，代码提交 `61963c90`。

## 安装与恢复点

- 测试服实例 `836082a48c4f48fcb1fea4e83c57dc96`；旧PID1077471，新PID1081210。
- 通过MCSManager正常stop，确认世界/玩家数据保存与JVM退出，才捕获1916文件完整归档。
- 恢复目录 `/opt/mcsmanager/backups/javarailways/display-is-in-service-61963c90-20261112-2g1sa4zv`，目录0700、归档及清单0600。
- 归档SHA256 `c47464a0a91a420a5d65d1a90d19f86388f0244d78b54a8d89690c964080bcf9`，逐条ZIP内容与停服清单核验一致。
- 一次性工具及安装/健康回执也保存在恢复目录；完整原数据不提交Git。

只原子替换三个目标：`plugins/JavaRailways.jar`、`plugins/JavaRailways/timetables/new-timetable.js`、
`plugins/JavaRailways/worlds/2952ad2c-41ce-40bc-b83b-1b578ae7af12-runtime.json`。
JAR SHA256 `16d735cd55e453169db30233cc4569a09d4497bde5a25a9d447394c3bf56b001`；与上轮已测试构建一致。
对比旧安装JAR只24个条目变化，无新增/删除条目，集中于本次字段/刷新/前端及受构造签名影响的编译调用点。
安装结束、启动之前复核全清单：除这三文件外1913文件不变；异常路径支持回滚三文件。其余11个插件JAR在启动后再核验不变。

## 字段处理

从已核验的停服归档读取权威作者文档，原文哈希 `4fd124c8838d2d4c2cd51fcb6196987b4987c024b5c234465eb7c1237fe75259`。
16个runId均准确匹配13个PASSENGER/3个DEADHEAD。脚本JSON.parse参数、runtime的嵌套runSegments各插入16个Boolean。
脚本按插入逆变换验证原文逐字恢复；runtime内层也逐字逆变换，外层只替换该JSON字符串。
未重排运行线、改时刻/时钟、列车位置、编组、认领或作者文档；版本不变，没有运行时旧格式reader/default。

## 严格验证与离线工具问题

三个迁移回归覆盖中文/引号、嵌套JSON、外层高精度数字原文保持、缺映射及已有字段拒绝。
全部通过并经独立trellis-check只读审查。

最初复用 `DevelopmentWorldMigration.materialize` 出现 `fixed unit claim does not match Core formation`。
代码确认旧工具直接创建 `SimpleATOWorldData`、漏传depots，默认 `DepotStore()` 的Core单元provider为空。
没有修改世界数据以绕过该检查，也没有修范围外的仓库工具。
临时验证改用服务器RailWorldFactory同一 `OperationalWorldRuntime.create`：
提供真实Core composition，完整执行严格codec、Core/Basic/SimpleATO restore、claims、movement holds和dispatcher checkpoint恢复。
6世界、5列车、5单元、5认领、16段（13true/3false）均通过。无tick、无移动。独立审查再次核对不是放宽验证。
通过后按同一候选ZIP哈希生成门禁才安装；完整控制台日志复制在同目录。

## 启动与激活

- 正常启动完成；健康端口8765/29002均HTTP200，ATO world为RUNNING，`jr help`真实命令响应正常。
- 用现有 `/jr resources reload` 事务重载：1列车包、3轨道包，无类型失败。
- 25节既有车厢的自动保存引用均转到新包快照 `63397f53aeb1cb9b74c213f85d7520af4e0787890e57b6836a581eccc2f7228b`。
  其front/side XML逐字哈希等于磁盘新XML；除hash/extension资源外，其他包快照字段与原包一致。
- 自动保存再次确认5列车、16段13/3字段正确。安装时其余文件不变与启动后正常autosave/资源快照生成是两个时点，不能混为“服务运行中所有文件都不变”。
- 提取的新WebUI sidecar与嵌入JAR完全匹配；实际服务index及JS/CSS字节与新资源匹配。
- 启动日志无恢复失败、插件启用失败、显示渲染失败或资源重载失败。保留既有HTTP部署提示、Folia单worker、启动短暂物理耗时等警告，未改环境配置。
- 控制台 `jr timetable status` 受既有Player-only限制，不能把请求回执当作实际班次状态验证；未代玩家执行、reset/reload-all或自动重规划。
- 另一次可选公开train API探索命中SPA HTML，未作为API或列车状态验收证据。

实际游戏客户端的默认字体/光影可见性、内外屏周期、回送规则及无黑屏仍需用户验收。服务健康、离线恢复与资源引用不替代画面验收。
