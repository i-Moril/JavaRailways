# MineRails WebUI

Standalone Java service for the MineRails timetable editor. It serves the React/Canvas editor, keeps drafts on disk, and proxies validated publication requests to the authenticated Bukkit bridge. The browser never receives the bridge token.

## 1. Enable the Bukkit bridge

In `plugins/MineRails/config.yml`:

```yaml
timetable-http-bridge:
  enabled: true
  bind-address: 127.0.0.1
  port: 8765
  bearer-token-env: MINERAILS_TIMETABLE_HTTP_TOKEN
  body-limit-bytes: 1048576
  timeout-millis: 5000
```

Set a strong shared token before starting the Minecraft server:

```bash
export MINERAILS_TIMETABLE_HTTP_TOKEN='replace-with-a-long-random-token'
```

The bridge intentionally binds only to loopback.

## 2. Build and run WebUI

```bash
./gradlew :WebUI:shadowJar
export MINERAILS_BRIDGE_TOKEN="$MINERAILS_TIMETABLE_HTTP_TOKEN"
java -jar WebUI/build/libs/WebUI-1.0-SNAPSHOT.jar
```

Open <http://127.0.0.1:8080>.

Configuration environment variables:

| Variable | Default |
|---|---|
| `MINERAILS_BRIDGE_URL` | `http://127.0.0.1:8765/` |
| `MINERAILS_BRIDGE_TOKEN` | empty; bridge calls fail until set |
| `MINERAILS_DRAFT_DIR` | `data/drafts` |
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
