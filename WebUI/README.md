# MineRails WebUI

Standalone Java service for the MineRails timetable editor. It serves the React/Canvas editor, keeps drafts on disk, and proxies validated publication requests to the authenticated Bukkit bridge. The browser never receives the bridge token.

## Managed mode（默认）

`BukkitPlugin` 内含独立的 WebUI sidecar JAR。插件启动时会把它提取到
`plugins/MineRails/webui/MineRails-WebUI.jar`，生成临时 Bridge Token，并启动独立 JVM；
插件关闭时先终止 sidecar，再关闭 Bridge。`runServer` 和 `runFolia` 因而会自动提供
WebUI，无需手动启动第二个 Gradle 任务。浏览器 API 默认拒绝匿名访问；管理员需在游戏内执行：

```text
/mr timetable edit
```

命令会返回一个可点击、约 60 秒有效且只能使用一次的登录链接。Token 放在 URL fragment
中，不会发送到 HTTP 服务或 Referer；React 随即以同源 POST 交换并清除 fragment。登录后使用
HttpOnly、SameSite=Strict 的短期会话 Cookie。

```yaml
timetable-webui:
  mode: managed
  bind-address: 127.0.0.1
  port: 8080
  public-url: http://127.0.0.1:8080
  shutdown-timeout-millis: 5000
```

## External mode

如果要自行部署 WebUI，把模式改为 `external`，并为 Minecraft 与 WebUI 进程设置相同的强 Token：

```yaml
timetable-webui:
  mode: external
  public-url: https://rail.example.com
```

```bash
export MINERAILS_TIMETABLE_HTTP_TOKEN='replace-with-a-long-random-token'
export MINERAILS_BRIDGE_TOKEN="$MINERAILS_TIMETABLE_HTTP_TOKEN"
```

Bridge 仍强制绑定 loopback。WebUI 可监听 `0.0.0.0` 并通过 HTTP 直接访问，但服务器会输出明文传输警告；需要传输保密性时使用 HTTPS 反向代理。

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
| `MINERAILS_WEB_BIND` | `127.0.0.1`；可设为 `0.0.0.0` |
| `MINERAILS_WEB_PORT` | `8080` |
| `MINERAILS_WEB_SECURE_COOKIE` | `false`；HTTPS 外部部署必须设为 `true`，managed 模式自动设置 |
| `MINERAILS_MAX_REQUEST_BYTES` | `1048576` |
| `MINERAILS_REQUEST_TIMEOUT_MS` | `10000` |

Remote plaintext HTTP is supported when explicitly configured. It preserves short-token authentication but does not protect tokens or session traffic from network interception.

## 线路与隔离模拟

WebUI 会从 Bukkit 运营基础设施自动读取车站、站台、车辆段和列车。线路保存为有 revision
保护的有序 `stationId` 列表；保存前由 `RoutePlanner` 尝试所有可用站台组合，并选择每一区间
最短的合法有向路径。时刻表车次可以绑定线路，运行图据此分线路显示车站顺序。

“模拟”页面创建的列车只是一组不可变动力参数。Bukkit/Folia simulation context 负责捕获线路长度、
坡度采样和基础设施 revision；随后专用线程调用与真实列车相同的 `TrainDynamics`，不会创建实体、
注册控制器或修改世界。若计算期间线路 revision 变化，结果会被拒绝。

车站停靠编辑器可配置停站时间、停车偏移、开门侧，并可把换向、停车或关机动作插入到到站关门后、
下一次发车前的现有结构化时刻表步骤中。

## Development

```bash
cd WebUI/frontend
npm ci
npm test
npm run dev
```

The Vite development server expects the same `/api/v1` API. Use a development proxy or run the packaged service for end-to-end testing.
