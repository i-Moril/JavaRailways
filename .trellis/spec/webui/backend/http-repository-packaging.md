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
| bridge/storage 的 `IOException` | 502 |
| bridge token 缺失或状态无效 | 503 |
| 请求被中断 | 504 |

catch `InterruptedException` 时恢复 interrupt flag。不要吞掉 bridge error 后写入“成功”本地状态。

## Repository 与 formal script

`DraftRepository` 只接受 allowlisted world/id，值必须是 JSON object；save 写同目录 unique temp，优先 atomic move，finally 删除 temp。list 固定按 filename 排序。

editor document 在 create/save 前必须通过 `EditorDocumentValidator`，不只依赖 frontend type。formal script save/rename/delete 需要协调本地 document 与 bridge：先保存时保留 previous，bridge IO/status failure 时恢复 previous。新增多资源 mutation 必须保持同类 compensating rollback，并测试失败分支。

Draft/editor document 是独立持久化边界；shape breaking 时升级其最小 `formatVersion`。开发期 local recovery 可丢弃，不为任意旧草稿积累永久 runtime reader。

## 程序库删除

`DELETE /api/v1/worlds/{world}/station-programs/{program}` 使用有效 session 和严格 JSON `{expectedRevision}`；成功真实删除库文件并返回 `{removed:true}`。缺失404、revision冲突409、无效输入400，不能用归档标记替代删除。检查 revision 与文件删除由同步 repository 方法统一拥有。删除不扫描或改写 editor documents／已发布 runtime 的嵌入快照。当前库记录字段为 formatVersion、id、name、revision、definition、contentIdentity；字段集合只描述当前模型；未知字段通过通用校验报错，不建立旧字段黑名单或专项迁移提示。开发期保持 formatVersion1，旧记录必须在停服后用 ai_tasks/migrations/remove_program_library_archived.py 做有 hash 计划和恢复备份的一次性转换，运行时不提供兼容读取。

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

EditorDocumentValidator 校验可选 operationalTrips（唯一 id、受限 defaults、service/run/unitSlots 引用）；共享程序校验 SET_DISPLAY/RESET_DISPLAY 与 UNIT_SLOTS。Bukkit bridge 的单线模拟在使用已存时刻表时接收 runId，要求它属于所选 service/line；没有段且明确 service.lineId 的服务保留原能力。模拟状态投影 currentRunId/currentLineId 与完整 unitDisplays（包括 active/unresolvedRuns），不能把未知后续单元绑定表现为完整停站表。领域定义与原子显示更新见 [SimpleATO 契约](../../javarailways/simpleato/through-running-and-unit-displays.md)。
