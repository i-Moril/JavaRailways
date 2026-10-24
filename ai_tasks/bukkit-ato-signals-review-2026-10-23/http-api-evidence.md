> 分轮证据记录。早期覆盖数量/“未修改仓库”描述对应当时进度；最终问题状态与覆盖以 [review.md](review.md) 为准。本轮仅新增本目录文档。

# 主审续审：HTTP 与公开 API

静态全文补读 TimetableHttpBridge、IsolatedLineSimulator、SimulationControlProfiles、TimetableScriptNormalizer，全部此前未读的 Java API、diagnostics、wand/config 值合同与 ModelAssetYamlCompiler。逐文件增量记录在 /workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/file-coverage.tsv。无构建/测试/仓库写入。

## BUK-10 [P2] HTTP 对路径重复解码，合法 ID 无法定位或定位到其他对象

位置：BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt:129,143,191-202,857。

生产链：WebUI/frontend/src/api.ts 的 encodeURIComponent；WebUI/src/main/java/work/art1st/javarailways/webui/WebUiApplication.java:381-382,615-617 再按单路径段编码发送；插件先读取 HttpExchange.requestURI.path（Java URI.getPath 已完成百分号解码），分段后又用 URLDecoder.decode 做一次表单解码。故合法 line ID `A+B` 到达桥时 `%2B` 已变 `+`，随后变为空格，PUT 与 body.id 不符被拒绝，DELETE/模拟可能选择 `A B`。`A/B` 的 `%2F` 在分段前已还原成 `/`，路由段数错误；`A%20B` 则二次解码变成另一个 ID。

LineId 构造器只禁止空白边界与控制字符，不禁止 +、/、%，因此这些是领域允许的 ID。世界名含 + 也会被改成空格。没有宣称越权：桥使用同一已认证权限，问题是身份丢失/错误对象操作。

最小方向：对 rawPath 先按 / 分段，再仅做一次路径段解码（不要将原始 + 当表单空格）。补真实 HTTP 往返测试，覆盖 +、% 与编码斜杠，并断言操作的准确 line/world。未启动 HTTP 动态复现。

## 排除/边界

- ConfigSnapshot 明确只冻结映射并按引用保存任意 payload，故不把 Vector3d 可变引用重复报告为违反深拷贝合同。
- ModelAssetYamlCompiler 的 inline 分支没有 PartRegistry 的双变换问题；其 reference 变换使用定义与 entry 相加，需按现有配置合同理解。
- HTTP 工作线程状态在取得 world owner 前标为 RUNNING，使排队锁等待可越过 timeout；暂不单列缺陷，因为进入异步处理后的真实写入结果等待是明确设计，需要先明确 timeout 的排队范围合同。
