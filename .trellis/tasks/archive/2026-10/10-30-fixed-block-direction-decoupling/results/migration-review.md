# 一次性迁移工具独立复核

结论：通过，发现的两处机械校验缺口已修正并验证；未发现需要重订迁移设计的阻塞问题。本轮只审查/修改迁移工具并写本报告，没有修改产品运行代码，没有执行 Gradle、Git、远端操作或部署。

## 审查范围与边界

已读取当前 `check.jsonl`、PRD、design、implement、版本前置规范、完整 persistence schema 政策，以及 `research/migration-plan.md`。用户后来明确授权提交并部署，迁移工具实施按 migration-plan 中的停服/恢复点门禁进行；原任务文档中早期“未授权部署”的描述不能用来替代后来授权，也不代表本轮检查已执行部署。

逐行检查 `migrate.py`、`ValidateDefinitions.java`、README、固定旧 fixture 和 Python 测试，并核对真实 current Kotlin codec 的严格解码入口及 current DTO。

- 转换仅删除 `fixedBlocks[*].bidirectional`，并要求该值为 JSON Boolean；schema 保持整数 1。
- 直接按原 UTF-8 文本定位删除目标成员和相邻逗号，不重编码其他字段。其他成员原始文本、数字拼写、ID、revision、遍历顺序、两端入口和 placement 保留；已转换文档字节不变。
- Python 对整份文档递归拒绝重复 JSON key；未知根/闭塞字段、非法 schema/类型、文件名/worldId 不一致均拒绝。嵌套 current 领域字段及模型不变量再由真实 Kotlin codec 验证，未复制完整产品 decoder。
- primary 与 `.bak` 分别纳入计划和真实 codec 验证，任一非法即整批失败；不存在的 backup 不被伪造。
- dry-run 不写源文件；apply 校对审阅报告、候选 hash 和转换前后 source inventory/hash；独立 output 独占创建，已有候选不会被覆盖。
- 单文件 staging 经 fsync 和 rename；最后才写完整报告。IO/并发失败留下的候选没有成功完成标志，不可安装。安装时仍必须核对清单及每文件 hash。

## 本次修正

1. `ValidateDefinitions.java` 原来可对空目录打印成功；现在空 sidecar 集合明确失败，避免对错误候选目录作出成功判断。
2. `migrate.py` 原来只对 JAR 作校验前后 hash 比较，helper 仅在结束时取 hash；现在 helper 同样在校验前捕获 hash、结束后比较，避免实际执行的 helper 与审计记录不一致。
3. 新增候选写入期间源变化、写入 IO 失败测试；README 同步上述严格成功条件和 helper hash guard。

修改文件限于 `scripts/fixed-block-direction-migration/{migrate.py,ValidateDefinitions.java,README.md,tests/test_migrate.py}` 及本报告。

## 执行证据

`python3 -m unittest discover -s scripts/fixed-block-direction-migration/tests -v`：13 项全部通过。包括原 11 项以及新增的写入并发变化/IO 失败测试；确认失败候选没有 `migration-report.json`，工具不修改源。

另使用 Java 25 source launcher 和现有真实构建 JAR 独立执行 CLI dry-run、新临时目录 apply 和直接 helper 验证。输入为在线只读副本 `/tmp/jr-direction-preflight-20261030/plugins/SimpleSignals/worlds`，仅作预检：

- 2 文件、删除 25 个字段，真实 codec 输出 `CURRENT_DEFINITIONS_OK files=2 blocks=25 fixedBlockSignals=16`。
- 所有 source 原始 bytes 保持不变；逐文件删除预期成员后的全部 JSON 值相等。
- 转换结果与 `/tmp/jr-direction-online-candidate-20261030` 的两个已有 sidecar 逐字节相等。
- 新临时独立候选 apply 完成，direct helper 也通过；该临时目录在复核后清理，没有覆盖已有候选。
- 将坏输入放入 backup、primary 保持合法时，未知嵌套 signal 字段、非 Boolean 旧字段、未知 schema、嵌套重复 key、错误 traversal 数值类型全部使 CLI 失败，坏输入原 bytes 未改变。
- 空候选目录被修正后的真实 Java helper 拒绝。
- 使用复制到临时目录的实际 helper 完成真实 codec 调用后改变该副本源码，CLI 明确以 `Validator helper changed during validation` 拒绝；不是 mock codec。

本次实际读取的验证工件 SHA256：

```text
SimpleSignals/build/libs/SimpleSignals-1.0-SNAPSHOT.jar
4951ea121c979b42213b108de2d9d1df4d810ffa43e2d6f78a234ebe1903ef99
BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar
023e9a193ea23d441d723771d03ce856afcc5cf822a99b86d97bcc88e61ca8c4
scripts/fixed-block-direction-migration/ValidateDefinitions.java
1fc32924f8fdb47e00e2a8fa8cf866799d902ac9bc421a84022358c181cf6500
```

## 部署前仍需执行

上述在线副本不是停服恢复点，本报告不证明部署完成。最终集成树的新 JAR 构建后，由主会话正常停服并确认保存退出，保留匹配旧 JAR/数据的不可覆盖外部恢复档；对新鲜停服副本重新 dry-run/审阅/apply，记录最终 JAR/helper/source/candidate hash，安装时再次核对并保留 ownership/permissions。旧预检报告的工件 hash 只证明当时检查所用工件，不能冒充最终部署产物。

真实 codec 接受只证明当前定义严格解码及模型不变量，不证明 topology/catalog 编译、游戏视觉或实际行车行为；这些仍由正常启动检查与游戏验收分别验证。回退恢复原匹配 JAR 和原 sidecar，不增加 runtime compatibility reader。

## 部署 sidecar 安装操作脚本追加复核

独立检查主会话后来新增的 `results/deployment-install-sidecars.py`，并读取本地已有 `/tmp/jr-deploy-20261030-4c19f7f4/deployment.py`，确认前置脚本先保存外部完整相关插件 JAR/数据归档和清单、验证归档可读及前后数据 inventory，再仅替换 manifest 中的 JAR。未运行该已有部署脚本，没有连接远端。

发现并机械修正：

1. 安装脚本把两个预写 temp 放在受检 worlds 目录里，随后拿未排除 temp 的 inventory 与恢复点比较，原实现必然拒绝合法安装。现在仅在预写后那一次比较中排除本次准确记录的 temp 路径；其他任何新增或变化文件仍被检测，最终检查不排除任何文件。
2. 关键停止/归档/来源/候选/清单/JAR 保护由 `assert` 改为显式 `require`，避免 Python 优化模式静默关闭保护。
3. 明确限定恰好一组 primary 与同名 `.bak`，提前拒绝已有完成审计文件，并在全部 temp 已准备、实际 rename 前再次检查 JVM 已停止与 JAR hash 未变化。

保留两份文件全部预写并检查后才逐个 rename 的实现，复制原 UID/GID/mode，每文件 fsync 后替换，并 fsync worlds 目录。最终 data inventory 除清单两文件 hash 外必须完全相同，全部插件 JAR hash 也必须相同；审计文件独占写入，不能覆盖原完成审计。

在本地临时伪 ROOT/BASE 和真实文件树中执行 11 项正反向操作自测，全部通过。仅将操作脚本文本的 ROOT/BASE 常量替换为临时路径；没有修改正式常量。正向测试额外用调用真实 `os.replace` 的观察包装确认：第一次替换之前两份 temp 均已生成且两份原文件仍为旧 bytes。自测脚本留在 `/tmp/jr-sidecar-installer-review-test.py`，每个场景的临时文件树均已清理。

- 正常安装：只改变报告中两个 sidecar，原 UID/GID/mode 分别保留，其他全部文件 bytes、现有 symlink、文件清单和三份测试 JAR 不变，审计权限为 0600，temp 无残留。
- `python -O` 正向安装仍通过；同模式下归档 hash 不匹配仍明确拒绝。
- live source/inventory 变化、第二份 candidate hash 错误、无关插件文件变化、未替换插件 JAR hash 变化：均在 rename 前拒绝，预写 temp 被清理，原目标不变。
- 已知 oldPid 仍存在：拒绝。
- 在伪 ROOT 下启动真实本地 Java 25 `HoldJvm` 进程、但 oldPid 指向不存在的旧进程：额外 JVM 扫描拒绝，随后测试进程正常终止；没有伪造 `/proc`。
- manifest 只含一个 sidecar，或外部恢复目录已有完成审计：拒绝，不覆盖任何目标或原审计。

最终检查过的 installer SHA256 为 `1cf2ade8f092fcfcdcb21304b819abd5d0963bbfdbca823c366afaec5c5c2c41`，Python compile 语法检查通过。

操作脚本复核结论：修正后无未处理的部署阻塞问题，可纳入主会话既定停服部署流程。仍须保持管理器/JVM 停止直到全部步骤完成；两文件 rename 本身不是跨文件事务，第二次 rename 或审计写入发生 IO 故障时必须保持停服、核查失败现场并使用完整外部恢复点回退，不能把失败输出当作安装成功。此追加结论是本地操作脚本验证，不是服务器已部署、世界已成功加载或游戏行为验收。
