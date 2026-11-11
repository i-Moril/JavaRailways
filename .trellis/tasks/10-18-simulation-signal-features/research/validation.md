# 本批最终候选与验证

2026-10-19；实现分支 `codex/simulation-signal-features`，基线 `9ed27818`，产品改动仍待提交。逐文件 SHA、357份 XML SHA 和新增用例名称见 [final-candidate.json](final-candidate.json)。

## 最终源码

27个Java/Kotlin源码及测试文件，canonical SHA-256：`a50950903a2a5a1273e2d006e546249b8a3dd08116324ac92e74c4e7f7377820`。算法为按相对路径排序依次哈希 path UTF-8、NUL、文件原字节、NUL。完整门禁前后逐文件校验一致。

宿主16文件指纹 `b20edb3c6e472971f561c2b24ccd881e8bec5b0e199410503116df5e668eaaaa`；Signals11文件最终指纹 `cf39eab28e56907d6f7a19c429b74fc7ff5fef79d52b32f9804a6db6321ef58b`。子清单使用各实现者记录的算法，仅合并候选采用上面的canonical算法。

## 完整门禁

精确命令见 [full-gate.sh](full-gate.sh)，JDK25、共享离线Gradle缓存、无build cache，各模块test均task-local `--rerun`，退出0，`BUILD SUCCESSFUL in 2m 3s`。

- Core447，Basic413，SimpleATO386，SimpleATOBukkit8。
- BukkitPlugin473，SimpleSignals641，TunnelBridgeBuilder31，WebUI12。
- 合计2411项，357个suite，0failures / 0errors / 0skipped。
- 新增16case各在最终XML中出现且仅出现一次：Signals12（含LIVE-LIVE、LIVE-SIM、SIM-SIM三组参数），宿主Feature登记3，inspection退休1。
- 此前表达式返回非Unit的service生命周期case已明确Unit，最终XML真实执行；不再沿用此前host focused22当作23。
- `:SimpleSignals:check`、Java公共API consumer编译检查、`verifySignalModuleBoundaries`、宿主shadowJar、Core/Basic/SimpleATO三模块Dokka均通过。已有UP-TO-DATE编译输出与当前源码一致；8个test实际重新执行。
- 生成过程保留现有deprecated/Undocumented/第三方native-access警告；没有以警告作为新功能验收。

原始日志 `/tmp/simulation-signal-full-gate.log`，哈希见manifest；XML位于本worktree各模块 `build/test-results/test/`。日志和XML保留当前执行身份，上一批2395项不计入本批证明。

## 前端桥接门禁

Bukkit规范要求WebUI bridge变更连同frontend tests。当前候选独立执行 `npm test` 退出0：21个测试文件、108项全部通过，日志 `/tmp/simulation-signal-frontend-test.log`，SHA见manifest.frontendGate。本批frontend源码无改动，未沿用前批108项结果。临时node_modules链接仅使用已有依赖，执行后按exact target移除。

## 定向检查与发现

- 早期host focused实际22项；最后Unit修复后的新case由最终全门禁补齐。
- Signals第五轮focused184项（manager154、logic12、policy16、hostcap2）通过并完整保留；最终再删除旧拓扑worker冗余诊断clear后重跑manager154通过。最终全门禁覆盖最终字节。
- 九项具体发现及修复见 [check-findings.md](check-findings.md)。最后全范围静态/证据复核见 [check-result.md](check-result.md)，范围包含全部27源/测试、公共API caller、manager锁/生命周期和CURRENT规范。
- JSONL上下文验证18+18条通过；超32KiB旧规范的注入截断警告保留，实现者/检查者已直接读取相关原文；不为清警告扩张范围。
- 产品及独立docs仓 `git diff --check` 通过。

## 文档门禁

文档站 `npm run check` 退出0：TypeScript、模型包校验、schema fixtures、引用文件复制、Docusaurus构建及Pagefind搜索索引均通过，索引27页。仅threading-model一份源改变，文档源和日志SHA见final-candidate.json的docsGate。原始日志 `/tmp/simulation-signal-docs-check.log`。没有安装/升级依赖。

## 证据边界

这是本地源码、真实领域链集成及构建证据。未部署，未执行Paper/Folia服务端或客户端验收；不把模型/玩家平台效果纳入模拟。不更改持久化格式或schema版本，不运行迁移。逐车脚本行为仍归子任务4，父任务AC6及最终联合AC7开放；本批不声称完整全Feature交付。
