# 路径点仅保留 ID：实现与验证

## 实际变更

删除 Basic `WayPoint` / `SavedWayPointV1` 与 SimpleATO `OperationalDestination` / `SavedOperationalDestinationV1` 的独立显示名。`WayPointStore.rename` 仅接收 oldId/newId。全部领域、平台和测试调用同步移除该实参；领域 worker 比较了原有 300 个构造调用，其他实参未改变。

Bukkit 创建/编辑、命令、模型标记、模拟起点 DTO 和目标 metadata 不再携带路径点名称。地图 MarkerSource/JSON/TS 同步删除独立 label，签名、大小估算和绘制均按实际 ID；地图当前生产链不从旧磁盘地图回读。WebUI 候选统一显示/过滤/提交实际 ID，删除站/库名称拼接、无消费者的名称 metadata 类型和传播。站内后缀输入及手输、顺序、撤销、查询清理保持。

其他实体的站名、车库名、线路名、车辆/程序/动作名称保留。Basic 基础设施格式2、SimpleATO格式1、outer格式2保持；没有版本改动、替代 getter、旧字段读写、别名、专项拒绝或相关运行时拒绝测试。保留既有通用 codec 行为。

## 本地门禁

- 完整前端 57 文件 / 335 项测试、TypeScript/Vite production build 通过；独立 reviewer 再次运行 335 项与 tsc 通过。
- 串行 JDK25/offline/单 worker/2g test heap 门禁 7m22s 成功：Core538（未变更且 Gradle up-to-date）、Basic436、SimpleATO657、BukkitPlugin673、SimpleSignals675、SimpleATOBukkit2、TunnelBridgeBuilder31、WebUI49，共3061项，0失败/错误/跳过。BukkitPluginDev test task 也完成，无测试源。测试结果按每模块 XML 复核。
- `:BukkitPlugin:shadowJar :WebUI:shadowJar :BukkitPlugin:compileSignalApiConsumerJava` 16s 成功。打包使用本轮已验证的 dist，未重复安装依赖/前端构建；主 JAR 内嵌 sidecar 与 WebUI JAR 字节一致，其17项静态资源与当前 dist 一致。
- 主 JAR SHA256 `9ddb673eb444d7a55f9334939fb25512eec6a30075c448d837a000fe6746cb16`；WebUI `410a87220a53744d29a007afc1f872ee09eca702843485a8bd4aab93f19e71ab`；JS `assets/index-CZpHHYPb.js`。
- 独立完整差异审查未发现需修复项；`git diff --check` 和 implement/check manifests 校验通过。

详细本地日志 `/tmp/jr-waypoint-identity-jvm.log`、`/tmp/jr-waypoint-identity-jvm-counts.json`、`/tmp/jr-waypoint-identity-packaging.log`。

## 真实浏览器

使用 agent-browser 隔离 Chromium 会话，访问实际 production dist；只用本地显式 UI fixture 提供身份/metadata/文档，未连接线上认证。模拟 endpoint 固定422“no simulation engine attached”，不能据此宣称执行成功。

实际点击程序和模拟入口，候选为 `etchujima@U`、`shiomi@D`、`shinkiba@3`、`kasaidpt@1`；输入 `SHI` 仅剩 `shiomi@D` / `shinkiba@3`，选中后失焦值为 `shiomi@D`；再输入 etch 仅剩 `etchujima@U`，Esc恢复原 ID。选择真实参考 profile/方向并填写 DESTINATION 参数后，实际发出 `initialPosition={wayPointId:'shiomi@D',reverseTrain:false}`、参数 target=`kasaidpt@1`。这是 UI/wire 验证，不是游戏或服务器模拟验收。

证据 `/tmp/jr-waypoint-identity-browser/interaction-check.json`、`request.json`、`id-only-selector.png`。首次浏览器检查脚本把 CLI eval 的字符串再包一层 JSON 导致断言类型错误；修正检查脚本后通过，未修改产品代码以迎合该错误。

## 一次性数据工具

`scripts/waypoint-identity-migration/` 仅用于离线副本，不属于插件 runtime。删除两个准确数组内对象的直接字段，通过原文 span 保留所有其他数值、转义、空白与字段顺序；无变化文件保持原始字节，含 gzip header。主/备独立转换，source inventory/hash 守卫，候选只写独立新目录，带实际 current codec 结果的完成报告最后落盘。

12项 Python tests 及独立重跑通过。本轮实际新 shadowJAR 的 CLI 验证也通过：本地固定格式 fixture 含真实格式直线轨道、standalone point、station point、depot point、站/库/线路名称，主文件与 gzip backup 各转换3字段；dry-run→按审核 hash 输出候选，`SplitWorldSnapshotCodec.decodeInfrastructure` 及 core/basic/simpleAto 返回 `CURRENT_INFRASTRUCTURE_OK files=2`，源 hash 不变且其他名称保留。此为真实 codec 校验，不把结构 doubles 当 actual codec，也不等同于世界 materialization。证据 `/tmp/jr-waypoint-identity-codec-check/`。

## 服务器只读预演与交付边界

完整服务器基础设施及备份复制到本地的方案被自动审批拒绝，理由是完整载荷及目的地未明确授权。未重试其他数据复制方式、未取得完整存档；改用审批所建议的服务器内存只读预演。通过 SSH stdin 执行本批实际 converter，仅返回文件名/hash/数量，不写服务器数据或导出内容。

当前12份基础设施文件转换成功：world主文件31字段、对应backup31字段，共62；其余10份 byte no-op。二次转换幂等，所有源 hash 复核不变。该运行显式 `currentCodecValidated=false`，只证明本工具在当前保存数据上的精确转换，不宣称新 JAR 已解码该真实存档。摘要见 waypoint-identity-server-audit-2026-10-31.json。

本轮没有新增提交/部署指令，因此未提交、未替换在线插件、未改活动存档、未停服。后续配套部署需正常停服保存、外部恢复点，对最终主/备运行完整 current-JAR CLI 校验，再一起切换新 JAR 和候选。不可让旧插件写回被清理字段，也不可把本次在线只读样本当停服后的恢复点。父任务继续 in_progress、completedAt=null，用户游戏验收及其余父任务事项仍待后续，不归档。

以上为实现阶段检查点。用户随后明确要求提交并部署：产品daabfa4e已于2026-10-31配套转换主/备存档并上线，最终12文件真实服务器codec、8文档/6程序、恢复点/安装、实际ATO/信号/地图/17资源与公开位置流通过；旧实例停服清理错误与安装后0插件错误分别记录。当前交付事实以 deployment-2026-10-31-waypoint-identity.md 为准，任务保持进行中。
