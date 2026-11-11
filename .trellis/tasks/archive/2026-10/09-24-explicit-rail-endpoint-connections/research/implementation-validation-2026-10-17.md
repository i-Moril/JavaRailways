# Implementation and offline validation — 2026-10-17

## Scope and lineage

用户批准收窄后的端点方案，任务进入in_progress。源码基线`dev/signal-system` / `3fc32374`加本任务改动，工作区包含其他任务未提交内容；本轮不改动或纳入其提交。
唯一远端源为`user@192.168.1.230`已记录的MCSManager实例，详见[源核对](endpoint-save-audit-2026-10-17.md)。
本批未停服、写入远端或部署。在线副本不是持久停服恢复点，实际应用须另行批准并重新停写/备份/核对。

## Focused tests

- Core首次定向72项最终通过：SavedRailConnectionsTest35、RailConnectTest2、PreparedRailWorldSnapshotPlanTest11、CoreWorldRailTopologyTransactionTest24，零failure/error。日志`/tmp/endpoint-compile.log`。
- 首轮新增断言两项失败为curve point对象身份比较与既有duplicate错误文本预期，修正后通过；不称为旧实现红灯证据。随后补充prepared四组合断言由最终门禁覆盖。
- 离线测试9项通过：DevelopmentWorldMigrationTest3、EndpointWorldMigrationTest6，零failure/error/skip；日志`/tmp/endpoint-offline-tests.log`。
- 静态审查修复旧UUID字符串大小写等价时active映射错误，增加固定回归；未放宽current validator。

## Actual remote-copy conversion

输入24文件为`/tmp/javarailways-explicit-endpoints-20261017/source/worlds`，source-files.tar.gz SHA256 `623f0d00bc72af2b62bb8b0888c47bee2e1b527c89604cf3225d91de9276bab9`。
只读dry-run与全新本地输出均通过新current codec及真实Core→Basic→SimpleATO restore，共12对，未mock业务恢复、未运行physics或服务器。
输出为`/tmp/javarailways-explicit-endpoints-20261017/converted`（24世界文件+报告），不是完整可覆盖的JavaRailways配置目录。
主世界current revision/epoch=68400/68430，backup=67200/67230；每份106 rails/234有向连接，AA28/AB85/BA85/BB36。
日志`/tmp/endpoint-offline-dry-run.log`、`/tmp/endpoint-offline-output.log`，摘要`/tmp/javarailways-explicit-endpoints-20261017/offline-validation-summary.json`。

主会话另行用独立比对检查12对原/新文件：
- 全部24份源文件SHA256与读取时manifest相同。
- 12份runtime原始字节一致，包含transport。
- 每条连接按旧source端及目标curve唯一候选独立计算，与输出railId+endpoint逐项相同；active及列表顺序准确，共468条有向记录。
- 所有curve、rail列表顺序、Core非rail字段及outer其他字段（含Basic/SimpleATO嵌套JSON字符串）原样相等；revision/epoch保持。
独立比对脚本`/tmp/javarailways-explicit-endpoints-20261017/verify_conversion.py`只用于该副本核验，不是插件或受支持转换入口。

## Final full-scope gate

独立trellis-check首轮完整强制执行已完成Core421、Basic410、SimpleATO377，均零failure/error/skip。
命令为`GRADLE_USER_HOME=/workspace/.gradle ./gradlew --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :SimpleSignals:test :TunnelBridgeBuilder:test :BukkitPlugin:shadowJar`。
此进程随后在Bukkit/Tunnel测试编译期间因默认Gradle heap512m/metaspace384m发生GC thrashing而退出1，不能把该整体构建记为成功。
在源码不变的前提下，保留已经实际完成的三模块XML，剩余模块另以`--max-workers=1 --no-daemon -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8'`继续，
保持offline/no-build-cache/in-process，并对剩余test任务使用task-local rerun；不修改产品或仓库构建设置。首轮日志`/tmp/endpoint-final-check.log`，剩余日志`/tmp/endpoint-final-check-remaining.log`，最终结果后补。
打包已检查`SavedRailEndpointRef`及serializer存在，JAR无`work/art1st/javarailways/offline/`条目；最终产物hash待剩余门禁完成复核。


## Final result

最终独立门禁通过，采用前述相同源码的分段执行证据：Core58 suites/421 tests、Basic60/410、SimpleATO49/377、
BukkitPlugin100/425、SimpleSignals56/595、TunnelBridgeBuilder8/31，共331 suites/2259 tests，failure/error/skip全部0。
剩余门禁1m48s退出0，`BUILD SUCCESSFUL`；不将前一次GC中断的整体命令误记为通过。
测试汇总`/tmp/endpoint-final-counts.json`。无独立ktlint/detekt/checkstyle配置，lint由diff-check及实际JVM编译验证覆盖。

独立checker检查1014份JVM/build输入前后完全相同；`/tmp/endpoint-final-source-before.json`与`endpoint-final-source-after.json`
均SHA256 `60feac451a775d889341ed1c04e1c2934e564adbf2a8da16b79766f2258b0d50`。门禁后只更新本任务记录，不改产品源码。
最终完整产物`BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar` SHA256
`e840e71d0a0d92a848bece231256d78f70ada260c2c9564eb30221cccfe67a65`，新SavedRailEndpointRef及serializer存在，
无`work/art1st/javarailways/offline/`类。独立审查没有剩余本任务缺陷，没有checker产品代码自修。

git diff --check、task context manifests及当前规划/spec相对文件链接检查通过。
用户已确认[本地提交计划](commit-plan.md)，执行本批本地提交及任务收尾；其他43项既有dirty入口排除，不部署。
没有在192.168.1.230应用转换文件或新JAR；未做真实Folia启用/客户端验收，这些不在本批实现授权内。
