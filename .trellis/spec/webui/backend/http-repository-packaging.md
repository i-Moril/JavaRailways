# HTTP、认证、Repository 与打包

## Route 与认证

`WebUiApplication.createApp()` 集中注册 route/filter/exception。新增 route 时：

1. 先写 method/path/request/response/status contract；
2. 放入正确 auth boundary；
3. path param 经过 URL segment encoding，filesystem ID 经过 allowlist；
4. 加 Java integration test，再同步 `frontend/src/api.ts`/`types.ts`；
5. `/api/**` 未知 route 返回 JSON 404，不能落到 SPA index。

登录 token 从 URL fragment 由 frontend `POST /auth/exchange` 交换；backend 转发到 bridge，创建 30 分钟 session 并设置 `HttpOnly; SameSite=Strict`，HTTPS mode 追加 `Secure`。`Referrer-Policy: no-referrer` 保持全局。不要把 bridge Bearer token、one-time token 或 cookie ID写进响应 body。

public allowlist 由 `isPublicMapRequest` 精确约束：`GET /api/v1/worlds`、rail map 和 train stream。任何 editor/metadata mutation 默认需要 `requireSession`。证据：`WebUiApplication.java`、`WebUiApplicationTest.java`、`WebUiSessions.java`。

## Bridge 与错误

`BridgeClient` 使用 configured absolute http(s) base、request timeout 和 server-only Bearer。保留 bridge status/body，不把所有 downstream 失败改成 200。local exception mapping：

| 场景 | status |
| --- | ---: |
| 无效 JSON/path/document | 400 |
| 缺失或过期的 WebUI session | 401 |
| 本地资源缺失或 API 未知 | 404 |
| bridge连接失败/无效响应、storage 的 `IOException`（按code区分） | 502 |
| bridge token 缺失或状态无效 | 503 |
| bridge HTTP超时、模拟超时、领域等待过期或请求被中断（按code区分） | 504 |

catch `InterruptedException` 时恢复 interrupt flag。不要吞掉 bridge error 后写入“成功”本地状态。

错误响应由sidecar归一为`{error:string,code:string,requestId:string}`。`BridgeClient.Failure`保留原始IO异常作为cause，以`BRIDGE_TIMEOUT/BRIDGE_UNAVAILABLE/BRIDGE_INVALID_RESPONSE`区分传输原因；本地repository IO为`STORAGE_UNAVAILABLE`。requestId在请求入口生成，同时进入响应与sidecar失败日志；HTTP错误转发保留真实error/code，不把领域拒绝改写为存储故障。编号目前不透传Bukkit原始日志。响应不含异常栈、Bearer或cookie。

普通bridge请求继续使用configured timeout；program/timetable/line模拟请求必填整数`executionTimeoutMillis`（1..3600000），由界面每次模拟的现实时间秒数换算。三个worker读同一请求字段，独立`BridgeClient.simulate`验证后以该预算+普通request timeout作为HTTP响应余量；不设独立simulation配置文件项/环境变量，不提供旧请求默认。不得为长模拟扩大普通读写超时。该预算不是捕获/编译/所有请求阶段的统一总deadline。后端队列满返回`SIMULATION_QUEUE_FULL`，worker超时返回`SIMULATION_TIMEOUT`；进入领域上下文前过期为`REQUEST_TIMEOUT`，不能误报桥接无法连接。

## Repository 与 formal script

`DraftRepository` 只接受 allowlisted world/id，值必须是 JSON object；save 写同目录 unique temp，优先 atomic move，finally 删除 temp。list 固定按 filename 排序。

editor document 在 create/save 前必须通过 `EditorDocumentValidator`，不只依赖 frontend type。formal script save/rename/delete 需要协调本地 document 与 bridge：先保存时保留 previous，bridge IO/status failure 时恢复 previous。新增多资源 mutation 必须保持同类 compensating rollback，并测试失败分支。

Draft/editor document 是独立持久化边界；shape breaking 时升级其最小 `formatVersion`。开发期 local recovery 可丢弃，不为任意旧草稿积累永久 runtime reader。

## 每时刻表程序库、导入与编辑锁

程序库属于 editor document，不再有 world 级共享库。路径为 `timetable-programs/<world>/<documentId>/<programId>.json`，记录字段只含 `formatVersion:1`、`id`、`name`、`definition` 和可选 `estimatedDurationTicks`；没有 revision、contentIdentity 或 bound snapshot。`TimetableProgramRepository` 是唯一 owner，负责 world/document/program ID allowlist、原子写入、目录删除和导入复制。

路由：

- `GET/POST /api/v1/worlds/{world}/editor-documents/{document}/programs`
- `PUT/DELETE .../programs/{program}`
- `POST .../programs/import`，body `{sourceDocumentId, programIds?}`，只复制源时刻表程序；目标 ID 冲突时重命名副本，不建立跨时刻表引用。
- `POST .../lock`、`POST .../lock/heartbeat`、`DELETE .../lock`。

所有程序 mutation 与 editor document create/save/delete/formal-script mutation 必须持有该 document 的锁；无锁或他人持锁返回 409 `DOCUMENT_LOCKED`。锁以当前 HttpOnly session cookie 为 owner，内存 lease 默认 2 分钟，前端每 30 秒 heartbeat；同 session 重入幂等，server 重启丢失锁。GET 仍可读，但前端打开/编辑前必须 acquire。删除时刻表时级联删除程序目录并释放锁；删除仍被事件或交路 PROGRAM 引用的程序返回 409 `PROGRAM_IN_USE`。current program file 与 editor document 均保持 `formatVersion:1`；这是一次性开发数据迁移，不增加 runtime 旧格式 reader。

## 独立函数耗时模拟

`POST /api/v1/worlds/{world}/programs/simulate` 需要 session，sidecar 验证请求后转发 bridge；复用既有模拟队列和超时，不进入 public map allowlist。请求包含 `programId/definition/arguments/startTick/profile/initialPosition/maximumDurationTicks/executionTimeoutMillis`，可选真实 `plannedContext`；`initialPosition={wayPointId,reverseTrain:boolean}` 必须明确布尔值，无默认或旧 facing 兼容。metadata 的 `programSimulationWayPoints` 只列路径点自身或轨道已声明方向的点，含 direction/directionSource；bridge 捕获时重新验证。

独立函数模拟不要求周期输入。`startTick+maximumDurationTicks`决定推进截止，后端仅为原dispatcher编译派生内部periodDays，覆盖截止及所有分支的绝对WAIT_UNTIL目标（含参数），保证超出推进预算的等待可以返回LIMIT_REACHED，不被人工周期拒绝。事件引用在前端按真实tick解析，不默默取模或复制其他周期。最大tick限制为可安全表示的完整天数边界，起点、预算之和、等待目标和内部周期均须保持JS安全整数。该临时wire不改变持久化schema。

开始tick也不是函数模拟的用户输入：独立程序库调用内部使用0，图/交路调用保留真实计划调用tick供绝对等待求值。该内部字段仍在模拟wire中，不为去掉输入而另建执行器或改变绝对时间语义。

响应 `status` 为 COMPLETED/FAILED/LIMIT_REACHED，含 simulatedTicks 和实际初始点/方向/是否反转；只有完成才有 durationTicks/startTick/endTick。失败保留 reason/failedProgramStepId，不以最大tick或下一任务开始伪造完成。该请求/响应是临时运行边界，不改变 editor document、程序库或持久化版本。后端不得重新按 programId 读取库内容替换请求的 exact definition：来源可以是未保存的有效画布或当前时刻表程序记录。

非完成报告另含`reasonCode`，按真实result类型输出`SIMULATION_FAILED/SIMULATION_LIMIT_REACHED/SIMULATION_TIMING_UNAVAILABLE`，供前端翻译摘要；原reason保留为诊断细节，不按英文子串猜故障类型。

路径点 descriptor 的展示身份统一为 `id`：`programSimulationWayPoints` 返回 `id/railId/direction/directionSource`；`destinations` 返回实际 `id`、空间位置、方向与角色。地图 `markers` 返回 `id/kind/railId/arcLengthMeters/x/y/z`，网页直接用 `id` 绘制文字。车站、车辆段、线路、模拟车辆等对象自身的名称由各自 descriptor 提供。

## SSE 与 backpressure

train stream 每 500 ms poll；per-world poll 同时最多一个，busy 时 skip 而不排 stale request。每 client 只有 latest payload slot，delivery worker 拒绝时关闭 client。保持 global/per-world caps、bounded poll queue 与 app stop shutdown。共享 stream worker 不因单次 bridge/runtime failure 退出。

## Gradle 打包

`WebUI/build.gradle` 定义：

```text
installFrontend (npm ci)
  → buildFrontend (npm run build)
  → processResources copies dist/ to classpath /public
  → shadowJar
```

改 frontend build input/output 时同步 Gradle task inputs/outputs；不要手工提交 `dist` 代替 build。`BukkitPlugin:processResources` 再嵌入 WebUI shadow JAR，因此 packaging 变更需验证两级产物。

部署产物不得因 frontend 源码未变而直接跳过 `buildFrontend`：干净候选可能没有 `dist`，Java 编译和 shadow JAR 仍能成功，但首页会因缺少 `/public/index.html` 返回404。离线复用与 lockfile 匹配的依赖时可以跳过 `installFrontend`，仍须执行真实前端构建。交付前检查 WebUI JAR 的 `public/index.html` 及其中引用的每个 JS/CSS，确认主 JAR 的 `webui/JavaRailways-WebUI.jar` 与该产物哈希一致；启动后分别验证首页与所引用静态资源返回200。不要用不明来源的旧缓存 JAR 补资源，也不要通过修改路由掩盖资源缺失。

## 服务器日志查询

`GET /api/v1/logs/sources`和`GET /api/v1/logs?source=...&query=...&cursor=...`必须通过现有session认证，不能加入public map allowlist，也不依赖某个已加载世界。来源返回`{sources:[{id,label,available}],warning?:string}`；本地来源`webui-errors`由独立进程直接读取，其余来源代理带Bearer的Bukkit bridge `logs`/`logs/sources`。桥接失败时仍返回健康的本地来源，同时显式给出warning，不能伪装成完整来源清单。

读取返回`source/entries/nextCursor/reset/scannedBytes/retention`；每条为`text/module/level/time/partial`。来源只接受`[a-z-]{1,64}`，query最多200字符，cursor最多512字符；路径由服务端白名单登记，禁止请求任意文件。未知来源由reader拒绝。读取在HTTP worker执行，不能走world await，也不在物理/region线程读文件。分页及轮转详细合同唯一归属[Diagnostics](../../diagnostics/backend/disk-logs.md)。

WebUI IO与未预期Exception出口将原Throwable及method/path写入本地PluginErrorLog；不记录query/body/cookie/Bearer。正常输入错误和会话拒绝保留既有状态映射。server stop关闭writer并停止原有流工作线程；启动失败报告原异常并关闭已创建writer。Tests需验证实际System.Logger→JUL→磁盘→authenticated HTTP，而非仅手写JSON fixture。

## 跨线与单元显示 wire

EditorDocumentValidator 校验可选 operationalTrips（唯一 id、受限 defaults、service/run/unitSlots 引用）；共享程序的 SET_DISPLAY/RESET_DISPLAY 统一作用于整列车，不带 unitSlots；程序参数不含编组单元类型。Bukkit bridge 的单线模拟在使用已存时刻表时接收 runId，要求它属于所选 service/line；没有段且明确 service.lineId 的服务保留原能力。模拟状态投影 currentRunId/currentLineId 与完整 unitDisplays（包括 active/unresolvedRuns），不能把未知后续单元绑定表现为完整停站表。领域定义与原子显示更新见 [SimpleATO 契约](../../javarailways/simpleato/through-running-and-unit-displays.md)。

## 统一定时作业 editor-document 边界

events与交路PROGRAM共用时刻、可选stationId/label/duration/program结构，program为{programId,arguments}；交路PROGRAM的program必需，EVENT元素仅{id,type,eventId}引用图所属作业。Java复用同一当前结构验证，不读取旧PROGRAM.call，未发布格式继续1。持久化层允许保存缺失事件引用，由authoring内容校验显示问题；不把内容issue升级为无法保存草稿。程序参数继续按已绑定快照验证。旧开发数据只由一次性dry-run/hash校验/恢复点迁移工具转换，不在HTTP或业务层upcast。

作业duration的PROGRAM类型表示采用当前时刻表程序记录的估计，MANUAL采用显式ticks，SIMULATED只采用完整真实测量，省略仍是既有POINT。PROGRAM无估计则未知，不能伪造成0或测量值。前端图/交路共用operationDuration解析；首次图事件选择程序直接使用本地程序记录，估计跨周期在确定前拒绝。该计划类型不扩展编组事件的耗时能力。


## 数字与字符串表达式

程序值支持递归纯运算和逐项对象构造；类型、格式、求值与预算遵守[表达式合同](../program-expressions.md)。浏览器、Java保存校验和Kotlin编译必须保持一致，原trainInfo动态绑定范围不扩张。


编辑文档运行线允许可选`color`，仅接受完整`#RRGGBB`且大小写均可，省略为自动配色；null、CSS名称/函数、缩写与alpha均非该字段形状。Java保存端验证并原样持久化完整authoring JSON；此显示字段不进入运行时DTO，无schema升版或兼容分支。详见[Studio颜色合同](../frontend/studio-authoring.md)。
