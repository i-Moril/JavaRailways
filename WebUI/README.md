# MineRails WebUI

Standalone Java service for the MineRails timetable editor. It serves the React/Canvas editor, keeps drafts on disk, and proxies validated publication requests to the authenticated Bukkit bridge. The browser never receives the bridge token.

## Managed mode（默认）

`BukkitPlugin` 内含独立的 WebUI sidecar JAR。插件启动时会把它提取到
`plugins/MineRails/webui/MineRails-WebUI.jar`，生成临时 Bridge Token，并启动独立 JVM；
插件关闭时先终止 sidecar，再关闭 Bridge。`runServer` 和 `runFolia` 因而会自动提供
<http://127.0.0.1:8080>，无需手动启动第二个 Gradle 任务。

```yaml
timetable-webui:
  mode: managed
  bind-address: 127.0.0.1
  port: 8080
  shutdown-timeout-millis: 5000
```

## External mode

如果要自行部署 WebUI，把模式改为 `external`，并为 Minecraft 与 WebUI 进程设置相同的强 Token：

```yaml
timetable-webui:
  mode: external
```

```bash
export MINERAILS_TIMETABLE_HTTP_TOKEN='replace-with-a-long-random-token'
export MINERAILS_BRIDGE_TOKEN="$MINERAILS_TIMETABLE_HTTP_TOKEN"
```

Bridge 与 WebUI 首版都强制绑定 loopback；远程访问应通过带 TLS 和身份认证的反向代理。

## 独立构建和运行 WebUI

```bash
./gradlew :WebUI:shadowJar
export MINERAILS_BRIDGE_TOKEN="$MINERAILS_TIMETABLE_HTTP_TOKEN"
java -jar WebUI/build/libs/WebUI-1.0-SNAPSHOT-all.jar
```

Open <http://127.0.0.1:8080>.

Configuration environment variables:

| Variable | Default |
|---|---|
| `MINERAILS_BRIDGE_URL` | `http://127.0.0.1:8765/` |
| `MINERAILS_BRIDGE_TOKEN` | empty; bridge calls fail until set |
| `MINERAILS_DRAFT_DIR` | `data/drafts` |
| `MINERAILS_WEB_BIND` | `127.0.0.1`（首版只允许 loopback） |
| `MINERAILS_WEB_PORT` | `8080` |
| `MINERAILS_MAX_REQUEST_BYTES` | `1048576` |
| `MINERAILS_REQUEST_TIMEOUT_MS` | `10000` |

For remote access, keep both processes on loopback and put TLS/authentication in a reverse proxy in front of WebUI.

## Development

```bash
cd WebUI/frontend
npm ci
npm test
npm run dev
```

The Vite development server expects the same `/api/v1` API. Use a development proxy or run the packaged service for end-to-end testing.
