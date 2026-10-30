# 一次性闭塞旧字段转换：实施边界与校验入口

用户在代码交付后明确授权“提交并部署”。本文件落实原设计中的条件性开发数据转换；不改变产品运行代码、版本政策或 Basic/Core。工具仅准备本地独立候选，实际停服、完整恢复点、安装和启服由主会话执行。

## 当前存档与最小变换

边界为 SimpleSignals `worlds/<canonical world UUID>-definitions.json` 及 `.bak`，未发布 current schema 保持 1。当前 Kotlin codec 的 `SavedFixedBlockV1` 仅有 `blockId`、`revision`、`traversals`；严格 reader 已不接受 `bidirectional`。一次性工具只删除 `fixedBlocks[*].bidirectional`，只接受 Boolean；已转换当前文档保持原字节。未知根/闭塞布局、陌生字段类型、重复 JSON key、非法 schema、文件名与 worldId 不符以及损坏输入拒绝，不尝试映射其他旧字段。

新增目录 `scripts/fixed-block-direction-migration/`：

- `migrate.py`：纯结构规划、原文本定位删除、source hash guard、真实 current codec 校验和独立候选原子写；Python 标准库，保留其余所有原始 UTF-8 字节。
- `ValidateDefinitions.java`：Java 25 source launcher，通过反射调用实际构建产物中的 `SimpleSignalsWorldDefinitionsCodec.decode`，没有 Bukkit/Paper bootstrap 或文件写入。
- `tests/fixtures/old-definitions.json` 和 `tests/test_migrate.py`：固定非空旧文档及有意义的迁移/IO拒绝测试。
- `README.md`：明确 dry-run、独立候选、恢复点、安装门禁、范围和移除条件。

已有 `scripts/model-coordinate-migration/CoordinateValidate.java` 确实调用同一 codec，但同时初始化 Paper registry 并读取模型/渲染文件，不能作为本次 sidecar-only 最小入口直接复用。本工具没有复制 Kotlin 持久化完整 validator；Python 仅检查本次转换所需形状，正式 CLI 在成功报告/输出前强制使用 current codec 验证所有候选，包括未改动的其他字段和领域不变量。

## 不可绕过的应用顺序

1. 正常停服并确认保存/退出；完整匹配旧 JAR 和数据外部归档、清单、hash 验证，由主会话负责。
2. 对新鲜提取副本执行 CLI dry-run，报告逐文件源/候选 hash 与精确旧字段删除清单；源不写入。
3. 审阅报告后加 `--expected-report` 和不存在的独立 `--output`。apply 重跑 current codec，检查 source inventory/hash 和候选 hash，拒绝源/输出包含关系和符号链接。
4. 候选目录独占创建，每文件 staging fsync 后 atomic rename，最后写 `complete:true` 报告；失败可能留下未完成候选，但不改变原始副本。完成报告缺失的候选不可安装。
5. 实际安装仍须比对停服源 hash、候选/安装后 hash 并保留 ownership/permissions；只安装清单中的 sidecar，不安装审计报告。启服后验证实际 topology/catalog 编译、定义 revision、日志与健康入口。

工具不创建恢复归档，不连接远端，不运行 Gradle，不启动插件。回退恢复停服前匹配的旧 JAR 和 sidecar，不新增 runtime compatibility 或 down-writer。

## 已完成的本地证据

- 11 项 Python 标准库测试通过：`results/migration-tests.log`。
- 真实当前 JAR 的正向和拒绝路径：`results/migration-helper-smoke.json`、`results/migration-reader-rejections.json`。拒绝旧原文、未知嵌套信号字段、非 canonical rail UUID；不是 mock codec。
- 在线只读副本预检：`results/migration-online-preflight-dry-run.json` 与 `results/migration-online-preflight-apply.json`。2 个文件共删除 25 个 Boolean 字段；primary revision63/13 blocks、backup revision62/12 blocks；两者原有各8个 fixed-block signals，其他 JSON 值及全部其他文本字节保留；实际 current codec 显示 `files=2 blocks=25 fixedBlockSignals=16`。

该在线副本只是预检输入，不是停服恢复点，也不代表部署已完成。主会话发现部署分支领先原工作区基线，最终须使用集成工作树的新产物以及停服新鲜副本重新执行转换与 codec 校验；当前预检 JAR hash 不代表最终部署产物。
