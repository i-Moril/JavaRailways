# WebUI Backend 规范

## 适用范围

适用于 `WebUI/src/main/java/` 的独立 Java 17/Javalin sidecar：browser routes、session/auth、Bukkit bridge proxy、world-scoped draft/editor document repository、SSE 与 frontend resource packaging。React/TypeScript 规则见 sibling frontend layer。

## 开发前检查

- [ ] 同时读取 `WebUiApplication.java` route、`frontend/src/api.ts` 与 `frontend/src/types.ts`，列出 method/path/request/response/status。
- [ ] 判断 route 是 public rail-map read，还是必须经 session；不得因便捷扩大 public allowlist。
- [ ] 明确 bridge token 只存在 server process，browser 只持 HttpOnly session cookie。
- [ ] repository 改动确定 world/id/path validation、atomic write、恢复/回滚和 schema owner。
- [ ] SSE/worker 改动确认总量、per-world、queue、latest-only、timeout 与 shutdown boundary。

## 子文档索引

| 文档 | 用途 |
| --- | --- |
| [HTTP、认证、Repository 与打包](./http-repository-packaging.md) | route/auth/status、bridge、atomic drafts、SSE、Gradle frontend integration |

跨 backend/frontend 数据流还应读取[跨层检查 guide](../../guides/cross-layer.md)。

## 核心规则

- `WebUiApplication` 是唯一 Javalin composition/route owner；handler 分清 local repository、bridge proxy 与 merged response。
- `BridgeClient` 每次请求带 server-side Bearer token、JSON Accept/Content-Type 和 timeout；空 token fail-fast，token 不出现在 browser response/log。
- public API 当前仅 world list 与 rail map/read stream；metadata、lines、drafts、editor documents、validate/publish 等需有效 session。
- error JSON 统一 `{error:string,code:string,requestId:string}`；稳定code区分bridge timeout/unavailable/invalid response与storage IO，保持400 input、401 session、404 missing、502 IO、503 configuration/state、504 timeout/interruption。requestId关联WebUI错误日志，不返回凭据或异常栈。
- path segment、world/id/script name 均在 boundary 验证/编码，禁止把 URL/path 参数直接拼为 filesystem traversal。

## 质量检查

- [ ] 运行 `./gradlew :WebUI:test`。
- [ ] route contract 改动运行 `npm --prefix WebUI/frontend test` 和 `npm --prefix WebUI/frontend run build`。
- [ ] packaging 改动运行 `./gradlew :WebUI:shadowJar :BukkitPlugin:processResources`。
- [ ] auth 改动覆盖 guest allowlist、one-time exchange、cookie flags、expiry、401 revocation 和 token non-disclosure。
- [ ] repository 改动覆盖 traversal、non-object/invalid document、world isolation、atomic replacement 和 bridge failure rollback。
- [ ] SSE 改动覆盖 caps、latest-only、slow/closed client、session expiry、rejected worker 与 shutdown。
- [ ] 运行 `git diff --check`。

证据：`WebUI/README.md`、`WebUI/build.gradle`、`WebUiApplication.java`、`DraftRepository.java`、`WebUiApplicationTest.java`。
