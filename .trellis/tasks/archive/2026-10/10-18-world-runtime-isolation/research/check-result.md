# 独立全范围检查记录

## O1/O2扩充后的最终复核（2026-10-19）

- 新候选44份产品/测试源，独立重算指纹`702e1db6c9b6e16e8af8cb18cfdec639f9d204d432f7f4441e7383a842bd3c4e`，逐路径与`/tmp/world-runtime-observation-final-candidate.json`一致。对照归档原42份源，原文件字节全部未变，只增加本次观察服务及同名正式测试；当前全部变更Kotlin/Java路径均在新清单内。
- 已重新读取更新后的18项check manifest、父/子PRD/design/implement、remaining-cross-world-locks、Basic threading-and-publication及Bukkit观察服务规范。Bukkit lifecycle超出注入大小，但已直接读取完整相关原文；没有为注入警告重构规范。
- **O1实际路径通过审查**：真实`RailwayObservation.fromCore`位于目标UUID entry的局部锁内、共享目录锁外。先在短目录锁取entry，再等待局部锁；嵌套取锁只按entry→目录，目录路径不等待entry，不在目录锁内等待ticket、转换或调用listener。测试用真实frame列车列表读取barrier确认已经进入fromCore；A暂停时B完成实际physics位移、current/publish及共同领域阶段。
- **O2语义与生命周期通过审查**：同frame引用复用、同epoch摆位/拓扑当前值更新、不重复通知、提前current不吞正常通知、epoch不回退及listener失败隔离均保留。removeWorld仅撤目录记录、保留UUID订阅；close清目录与订阅。提交和通知取快照前复查原entry身份，旧转换不能回填或覆盖同UUID替换记录，无永久tombstone。listener在两锁外运行，已取订阅快照不被后续注销回收，保持原合同。
- **实际caller归属**：WorldManager在所属owner→processBetweenFrames中发布；准入Bound API在withWorld(owner)→withStructuralMutation中调用latestObservation supplier→current；卸载在所属owner内确认worker终止后remove。没有把线程分类当作互斥，也没有新增逐次公开World归属校验。SIM仍使用原本地观察/admission链，不复制投影算法。
- 本轮没有发现需要修改的新产品缺陷；没有编辑产品或启动竞争Gradle。原42份全范围静态审查适用于字节相同的源，但原2391项测试没有用来替代本轮组合验证。
- 新聚焦证据：observation10 + infrastructure31 + signal15 =56项通过，日志`/tmp/world-runtime-observation-focused.log`。新增4项正式测试覆盖真实转换隔离、同World单次转换/通知、两锁外listener重入和remove/close交错。
- **新完整门禁**：session`63573`，`BUILD SUCCESSFUL in 1m 51s`。检查代理独立解析并逐SHA核对356份本轮XML：Core447、Basic413、SimpleATO385、SimpleATOBukkit8、BukkitPlugin470、SimpleSignals629、TunnelBridgeBuilder31、WebUI12，合计**2395项，零失败/错误/跳过**。8个test task均实际执行；API consumer、模块边界、shadowJar、Core/Basic/SimpleATO Dokka通过。
- 新完整日志`/tmp/world-runtime-observation-full-gate.log`，独立SHA256=`f17f99c0af06d2a8e033f1ceea224adf4807cbecd4977855c7f81fce5f816a85`；命令与下方原完整门禁命令一致，完整逐源/逐XML清单在上述新manifest。构建后44源指纹未变。
- **新文档完整门禁**：父会话session`54799`执行`npm --prefix docs-site run check`并退出0。独立复读日志确认TS typecheck、58份YAML、schema rejection fixtures、reference copy、Docusaurus及Pagefind27页通过。日志`/tmp/world-runtime-observation-docs-check.log`独立SHA256=`1d43c1fab89e38db3778815dde22206ab154489b7a12d3d9a3bdefe84e446b9b`；新threading-model源码SHA256=`6fa7c8b6af6bd056222e7a97383e492c831a446c50ed10b245899e99856465f1`，scanner仍为`7b1981af53d9f08248a6026032b543225468f0d9002bc4d67b0bd1af58ca6cc0`，均与父会话记录吻合。
- **本轮结论**：包括O1/O2的全批次静态审查、编译/类型检查、2395项JVM测试、API/模块/打包门禁、更新后的Basic/Bukkit规范及docs完整构建通过，未发现剩余本批具体缺陷。完整Signals JS/LIVE执行隔离、逐车行为及Paper/Folia现场验证依旧不在本轮完成范围。LIVE信号JS串行归子任务3的S1-S3，见[剩余锁清单](../../../../10-18-isolated-simulation-runtime/research/remaining-cross-world-locks.md)。

## 原42份候选的历史审查与验证

以下保留O1/O2补充前的记录；原2391项、旧指纹及当时结论不代表扩充后的最终候选，新结论以上节为准。

## 候选与检查方式

- 工作区 `.worktree/world-runtime-isolation`，基线 `8f156900413e38e45e24f064c2b9dba889c7bf8c`。
- 最终产品和测试共42份 Kotlin/Java 文件；独立核对变更路径无遗漏，并按 `sha256(sorted relative path UTF-8 + NUL + bytes + NUL)` 重算为 `9f1a685d4da7edaca5a767ac072f67c9aa054cfacf727524968da243b484f851`。逐文件与 `/tmp/world-runtime-final-candidate.json` 一致。
- 本检查代理直接完成审查，没有递归派发；遵守冻结和构建串行协调，没有启动竞争 Gradle，也没有编辑产品源码。下列修复由父会话协调对应实现者完成，检查代理复核实际改动。
- 已读父/子 PRD、design、implement、check manifest、两份原始 research，相关 Core/Basic/SimpleATO/Bukkit 包索引及质量要求、完整相关规范章节、docs-site 新增脚本边界和 threading-model 变更。

## Findings (fixed)

- `WorldExecutionOwner`：暂停恢复原先替换 held-owner 列表，词法 scope 退出后会留下已释放的内层 owner；现在恢复原列表及实际重入次数。普通 owner 不取得进程级 RW 门禁，低频管理按稳定 owner 顺序取锁。混合嵌套和异常恢复有实际回归。
- `PhysicsCoordinator`、`WorldManager.registerRailWorld`、`TrainControlManager.registerManualControl`：原线程/帧间 guard 保持在新增阻塞锁之前；错误 physics 线程在 owner 被占用时仍立即拒绝。
- `WorldTickRunnerRegistry`：短目录锁协调 offer/retire/stop，旧 offer 无法重建已撤销 runner。退役键改为弱引用，不永久保留已卸载 CoreWorld；生产键使用未覆盖 equals 的 CoreWorld 实例身份。竞争撤销和卸载 GC 都有回归。
- `WorldManager`：latest-only 不再依赖恰好命中全局 tick 的 modulo 保存。真实周期常量为1200；跨过周期 bucket 捕获一次，不重放积压，capture/enqueue 成功后才确认该 bucket，失败后下次 wake 可重试。周期状态随 runner 生命周期释放。
- `WorldManager`：失败卸载也在 finally 删除目标 World 指标并重算汇总，保留其他 World。
- `DefaultSignalEpochSourceService`：atomic callback 的已有禁止重入检查前置到目标 owner 取得前，避免 A callback 等 B owner 才拒绝；不同 World 正常替换仍可并行，已开始 epoch 继续使用旧 source。
- `JavaRailwaysCommand`：JS compile 完成后的 global callback 仅向确切目标 World 的帧间队列提交，不同步等 owner；完成消息交 entity scheduler。平台发放 Wand 不再进入领域 World lookup，恢复权限拒绝原行为；原测试断言未放宽。
- 模拟领域 stamp 从固定 `isolated` 改为目标 CoreWorld 自带 UUID `runtimeId`，同 capture 两目标身份各自独立；后续真实信号定义可以针对实际目标 root/ID 准备。
- LIVE/SIM 拆卸：原非阻塞 `close()` 保持原语义；新增明确终止确认在 state/topology 锁外等待 physics coordinator/workers/fallback 和 generic worker 真正退出。取消/失败 ticket 或 CLOSED 状态不作为 callback 已退出的证据。SIM 在目标 owner 内先终止后逆序关闭 feature/domain；LIVE 在 capture 尝试后、domain/service 释放前确认终止。清理继续逐项尝试并保留原异常/suppressed，取消 interrupt 最终恢复，feature cleanup 失败不导致二次释放。
- 准备器公开 `prepareSimulation`/`create(target)` KDoc 补齐 source 稳定边界、脱离 live 状态及目标新实例合同，没有扩大 API 文档 suppression。

## 全范围行为与测试审查

- A owner 暂停时 B LIVE 和 SIM 均真实执行 physics 和共同发布后阶段，真实列车位移/epoch 可验证；A 的1000次 wake 仅执行在途项及最新 pending，同 World callback 串行。
- 管理检查至提交期间新请求阻在所属 owner；RUNNING World 的真实 SignalEpochRuntime callback 未结束时，planned removal 等 exact ticket 完成才检查限制；planned 拒绝保留两侧注册，forced/stopped 清理沿原 fail-close 合同。
- 两个 World 的同玩家竞争只产生一个有效会话；handoff 仍先释放旧 World Future，再提交目标，不持玩家锁等待另一 World。
- HTTP helper 真实走 async，等待 A owner 时 global 仍能给 B offer 并完成 B epoch。命令/Wand/重载的实际目标、必要管理例外和帧间准入均已审查。
- LIVE/SIM 复用真实限速 materializer、SpeedSafetyMonitor、脱轨消费与 observation/admission/Dispatcher/postTick 顺序。stale request 不作用于新 frame，失败 epoch 不继续该领域消费阶段。
- 同 capture 两目标分别创建 registry/provider/Dispatcher/Basic metadata/local admission/runtimeId；真实 provider/contributor/preparation 非空路径、目标 SignalEpochRuntime admission 更新及缺 required feature capture 失败都有回归。partial install 逆序 close、suppressed 清理亦覆盖。
- SIM 取消测试同时暂停真实信号 callback 和 generic callback，各自退出前 feature 不关闭；LIVE 测试在 ticket 已完成但 Post listener 未退出且 capture 失败时同样保留资源。断言在外部测试线程核对结果/异常，未依赖事件总线吞掉 assertion。
- 当前 MainRailEventBus 两个生产消费者已审查：TrainDebugLog writer已有共享同步，锁内含写入/flush，本轮未拆此I/O锁；onReverse 捕获实际 train/car 并交目标 region，每车行为实例独有。未发现新增跨 World 全局可变状态缺少同步。
- docs-site 包候选扫描按现有 manifest/control/actions/cars/parts 标记识别，裸 rails/resourcepack 不当列车包，有列车内容缺 manifest 仍验证失败；与规范变更一致，没有修改模型/schema。

## Findings (not fixed)

该轮检查结束时未发现当时已识别的剩余具体产品缺陷；随后补入的O1/O2未被该历史轮覆盖，其实现与最终验收已另外记入本文件首节。以下其他证据边界继续保留：

- 完整 SimpleSignals 目标安装及逐车 JS 行为仍属后续子任务；当前 duration-only operation gateway 不能宣称完整 Feature 行为。
- `runOutsideSimulationContext` 当前没有生产同步区域等待 caller，本批证明 held-owner 暂停恢复能力，不宣称已经改造所有平台等待。
- 同 JVM 第三方 callback 永久不退出时保留依赖并低频报告等待，不能强杀后安全卸载。
- 未启动 Paper/Folia、客户端或真实插件 classloader，不用离线回归替代跨 region 效果/现场卸载验证。docs standalone CI 主仓输入缺口仍是既有开放项。

## Verification

- 静态/空白检查：根仓及 docs-site `git diff --check` 通过。
- 聚焦证据：assembly 最终27项通过，见 `/tmp/world-assembly-focused-status.json`；未用聚焦结果替代完整门禁。
- 完整门禁由主实现代理协调，最终轮 session `27251` 退出0，`BUILD SUCCESSFUL in 2m 27s`。检查代理独立解析本轮356份 XML，并核对全部 XML SHA，得到 Core447、Basic413、SimpleATO385、SimpleATOBukkit8、BukkitPlugin466、SimpleSignals629、TunnelBridgeBuilder31、WebUI12，共2391项，失败/错误/跳过均0。日志确认8个 test task 本轮全部实际执行，未混入首轮失败的旧统计。
- Kotlin/Java 编译检查通过；Signal API consumer、模块边界、shadowJar 通过；Core/Basic/SimpleATO Dokka 通过。新准备器公开 API 没有 undocumented 警告；既有其他 API 的未文档化警告和 baseline 原有 `[seed]` 链接警告保留，未新增 suppression 或把 task 成功说成零警告。
- 原始命令及逐文件/356份 XML 指纹：`/tmp/world-runtime-final-candidate.json`；日志 `/tmp/world-runtime-full-gate.log`，SHA256=`538a4ac265068709c79eb935febfddc142797700f79efff0f96b7ceb6c79816e`。检查后源码指纹仍为上述 `9f1a685…`。首轮 Wand 回归失败日志独立保留为 `/tmp/world-runtime-full-gate-first-attempt.log`，测试断言未修改。

```bash
GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test --rerun :Basic:test --rerun :SimpleATO:test --rerun :SimpleATOBukkit:test --rerun :BukkitPlugin:test --rerun :SimpleSignals:test --rerun :TunnelBridgeBuilder:test --rerun :WebUI:test --rerun :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :BukkitPlugin:shadowJar :Core:dokkaGeneratePublicationHtml :Basic:dokkaGeneratePublicationHtml :SimpleATO:dokkaGeneratePublicationHtml -x :WebUI:installFrontend --offline --no-daemon --no-build-cache --no-parallel --max-workers=2 -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx1400m -XX:MaxMetaspaceSize=640m'
```

- docs 最终轮由父会话 session `58399` 执行并退出0：`npm --prefix docs-site run check` 包含 TypeScript typecheck、58份列车 YAML、schema rejection fixtures、reference copy、Docusaurus build、Pagefind27页均通过；检查代理复读 `/tmp/world-runtime-docs-check.log` 核对输出，日志 SHA256=`e26fdecc598f198447b6d5be5e5cc040d0f7ccf119fda3be6a5a0422730a27b2`。前端 Vitest 另由父会话执行21文件108项通过。依赖沿用既有安装，未改 package/lock，临时依赖 symlink 由父会话移除，不提交生成产物。
- docs-site 本批两份手写源指纹：threading-model=`cab1bb25c1e3d59941a2ae7e3b52af86aa86bf98e5e52d785e252726cf5b6b9d`；validate-model-packages=`7b1981af53d9f08248a6026032b543225468f0d9002bc4d67b0bd1af58ca6cc0`。
- 原42份候选结论：当时的静态检查、编译/类型检查、2391项JVM测试、API/模块/打包门禁与文档构建通过；该历史轮没有已识别的剩余具体缺陷。O1/O2之后单独实现并按首节重新核对44源/2395项完整门禁，上述现场验证及下一批Feature能力边界继续开放。原候选完整源清单与日志另归档于`/tmp/world-runtime-pre-observation/`。
