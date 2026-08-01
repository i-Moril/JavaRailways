# JavaRailways WebUI

Standalone Java service for the JavaRailways timetable editor. It serves the React/Canvas editor, keeps drafts on disk, and proxies validated publication requests to the authenticated Bukkit bridge. The browser never receives the bridge token.

## Managed mode（默认）

`BukkitPlugin` 内含独立的 WebUI sidecar JAR。插件启动时会把它提取到
`plugins/JavaRailways/webui/JavaRailways-WebUI.jar`，生成临时 Bridge Token，并启动独立 JVM；
插件关闭时先终止 sidecar，再关闭 Bridge。`runServer` 和 `runFolia` 因而会自动提供
WebUI，无需手动启动第二个 Gradle 任务。浏览器 API 默认拒绝匿名访问；管理员需在游戏内执行：

```text
/jr timetable edit
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
export JAVARAILWAYS_TIMETABLE_HTTP_TOKEN='replace-with-a-long-random-token'
export JAVARAILWAYS_BRIDGE_TOKEN="$JAVARAILWAYS_TIMETABLE_HTTP_TOKEN"
```

Bridge 仍强制绑定 loopback。WebUI 可监听 `0.0.0.0` 并通过 HTTP 直接访问，但服务器会输出明文传输警告；需要传输保密性时使用 HTTPS 反向代理。

## 独立构建和运行 WebUI

```bash
./gradlew :WebUI:shadowJar
export JAVARAILWAYS_BRIDGE_TOKEN="$JAVARAILWAYS_TIMETABLE_HTTP_TOKEN"
java -jar WebUI/build/libs/WebUI-1.0-SNAPSHOT-all.jar
```

Open <http://127.0.0.1:8080>.

Configuration environment variables:

| Variable | Default |
|---|---|
| `JAVARAILWAYS_BRIDGE_URL` | `http://127.0.0.1:8765/` |
| `JAVARAILWAYS_BRIDGE_TOKEN` | empty; bridge calls fail until set |
| `JAVARAILWAYS_DRAFT_DIR` | `data/drafts` |
| `JAVARAILWAYS_WEB_BIND` | `127.0.0.1`；可设为 `0.0.0.0` |
| `JAVARAILWAYS_WEB_PORT` | `8080` |
| `JAVARAILWAYS_WEB_SECURE_COOKIE` | `false`；HTTPS 外部部署必须设为 `true`，managed 模式自动设置 |
| `JAVARAILWAYS_MAX_REQUEST_BYTES` | `1048576` |
| `JAVARAILWAYS_REQUEST_TIMEOUT_MS` | `10000` |

Remote plaintext HTTP is supported when explicitly configured. It preserves short-token authentication but does not protect tokens or session traffic from network interception.

## 世界轨道地图

轨道地图由 Bukkit 的专用、有限队列 worker 生成。simulation context 只快速复制轨道曲线和标记引用；曲线采样、JSON 序列化和原子文件写入全部在线程外完成。地图文件保存在 `plugins/JavaRailways/webui-maps/`，世界卸载时会立即清除引用、缓存和文件。

列车位置通过共享的认证 SSE 流以 2 Hz 同步。Bukkit 只读取原子发布的不可变 train frame，不会在 HTTP/SSE 线程访问 Bukkit 实体或执行列车物理。同步采用每世界 latest-only 请求，并限制全局/单世界订阅数、worker 队列、轨道数、标记数、总采样点数和序列化大小。浏览器地图最大缩放比例为 8×。

地图按 X/Z 平面绘制并保留 Y 高程。交叉处按局部轨道段高程从低到高绘制；较高轨道先以背景色宽线擦除较低轨道，再绘制自身，从视觉上明确表示两条轨道没有连接。

## JS 时刻表只读预览

服务器管理员可把脚本放入 `plugins/JavaRailways/timetables/*.js`，并执行：

```text
/jr timetable load <file.js> [id]
```

脚本不会在 Bukkit 或浏览器进程内执行。插件为每次编译启动内存受限、3 秒超时的独立
`ScriptWorker` JVM；GraalJS Context 禁止 Host class lookup、IO/网络、线程、native 和 polyglot
访问，Worker 进程也不会继承 Bukkit 环境变量。普通 JS `if/else` 在构建期运行，DSL 的
`predicate/all/any/not`、conditional activation 和 `whenCondition` 生成结构化运行期条件。

WebUI 的“JS 时刻表”页面只能列出服务器文件、显示只读源码，并把沙盒输出渲染为运行图；
它不提供上传、编辑、发布或加载按钮。

## 线路与隔离模拟

WebUI 会从 Bukkit 运营基础设施自动读取车站、站台、车辆段和列车。线路保存为有 revision
保护、相互独立的上行/下行有序 `stationId` 列表；保存前由 `RoutePlanner` 尝试所有可用站台组合，并选择每一区间
最短的合法有向路径。时刻表车次可以绑定线路，运行图据此分线路显示车站顺序。

“模拟”页面只能选择世界中已有列车或已加载车辆模板，不接受手工填写动力参数。Bukkit/Folia
simulation context 会从所选对象捕获真实 `TrainControlConfig`、逐车 `CarControlConfig`、线路长度、
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
