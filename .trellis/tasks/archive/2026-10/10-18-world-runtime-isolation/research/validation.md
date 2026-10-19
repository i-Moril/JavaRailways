# 验证记录：按World隔离运行调度与模拟装配

## 最终候选与范围

2026-10-19用户批准继续补齐O1/O2后，本批实现、规范及独立检查已完成，现已本地提交并整合：Core5d8cdabe、runtime1423116d、docs-sitecb9263b；无push/部署。基线dev/signal-system@8f156900，实现工作区`.worktree/world-runtime-isolation`、分支`codex/world-runtime-isolation`。docs-site独立工作区基线29a59d8。最终产品/测试44文件指纹`702e1db6c9b6e16e8af8cb18cfdec639f9d204d432f7f4441e7383a842bd3c4e`。父会话与检查代理独立核对逐文件SHA、全部changed/new Kotlin/Java清单及本轮全部XML。

本批覆盖World owner/独立runner、必要LIVE管理事务、共同领域装配、每目标扩展创建及O1/O2观察构造隔离。完整SimpleSignals/各LIVE的JS求值隔离归子任务3，逐车JS归子任务4，均未实施；duration-only operation gateway不代表完整Feature。

## O1/O2实现与真实回归

仅新增修改观察服务及同名正式测试，无公开API/caller变更。不可变Core frame的真实fromCore转换在所属World entry串行、共享目录锁外执行；共享锁只做目录/订阅/最终交换，listener在两锁外调用。remove/close撤销entry，迟到转换不能回填或覆盖相同UUID的新entry；卸载保留UUID订阅，subscription/owner注销仍可独立完成。

新增4个正式回归：真实fromCore列车列表读取barrier暂停A时，B完成实际0.25m物理位移、current/publish、共同afterPublication；同World竞争只构造一次且保持单epoch通知；listener跨线程查询同World和owner注销不会死锁；remove/close与真实构造交错拒绝旧结果并保留正确UUID订阅。原6个正式回归继续覆盖same-frame引用复用、sameepoch摆位/拓扑变化、early current不吞通知、epoch倒退、异常listener隔离及exact ticket拒绝。

focused56项通过：observation10、infrastructure31、signal epoch source15；日志`/tmp/world-runtime-observation-focused.log`。定向结果不与完整结果累加，完整门禁包含这些用例。

## 最终完整门禁

- JDK25，共享Gradle缓存，offline/no-build-cache/no-parallel、最多2 workers、Kotlin in-process。八模块test均带task-local --rerun，无--tests筛选。session63573退出0，BUILD SUCCESSFUL in1m51s。
- Core61 suites/447项，Basic60/413，SimpleATO49/385，SimpleATOBukkit5/8，Bukkit110/470，SimpleSignals59/629，Tunnel8/31，WebUI backend4/12；共356 suites/**2395项，零失败/错误/跳过**。
- 父会话与checker分别重算44源码指纹、当前356份XML SHA和计数；构建后源码无漂移，所有test task实际执行。完整逐XML清单`/tmp/world-runtime-observation-final-candidate.json`；仓库[候选清单](final-candidate.json)保存逐源码SHA、模块XML摘要、命令及日志SHA。
- Java signal API consumer、verifySignalModuleBoundaries、主插件shadowJar、Core/Basic/SimpleATO Dokka通过。既有Graal POM作为zip、deprecated/native/Unsafe及旧文档警告不作本次新增失败，不关闭检查或改测试规避。
- 本轮日志`/tmp/world-runtime-observation-full-gate.log`，SHA和完整命令见候选清单。

## 文档、规范和工作区检查

Basic publication与Bukkit lifecycle规范已更新观察服务计算/目录锁范围、同World顺序、通知快照和退休identity边界；docs threading-model同步用户可见行为。

修改后重新运行docs完整check退出0：TS、58份模型YAML、schema rejection fixtures、reference copy、Docusaurus及Pagefind27页通过。日志`/tmp/world-runtime-observation-docs-check.log`；两份docs源与日志SHA见候选清单。之前模型目录误识别的最小修正仍保留：裸rails/resourcepack不当列车包，有列车标记而无manifest仍拒绝，未改资产/schema。

原前端Vitest21文件108项通过，前端源码/package/lock未改，本轮不重复宣称执行108项；完整Gradle门禁复用当前依赖完成前端构建。两处本批临时node_modules symlink已核对目标后删除，未安装/升级依赖，未将生成build/static文件纳入提交。

根仓与docs whitespace检查通过；子任务implement/check各18项manifest有效。Bukkit lifecycle与route-resource-admission超过注入大小警告保留，代理实际读取相关原始章节，不依赖截断注入。

## 历史证据与未验证范围

原42源码9f1a685d/2391项是O1/O2补充前的成功候选，保存在`/tmp/world-runtime-pre-observation`，不替代本轮2395项。原全门禁首次Wand纯平台入口误取World权限回归已修正产品、原测试未改；首次失败日志保留，不混入当前通过计数。旧assembly27项和前端108项只作为对应源码历史定向证据。

未启动Paper/Folia或客户端，离线回归不证明现场跨region、classloader或热卸载已验证。Core具备held-owner暂停恢复，但没有生产同步区域等待caller，不声称迁移完全部平台等待。同JVM第三方callback永久不退出时保留依赖并低频诊断，不强杀或超时释放。docs独立checkout CI主仓输入缺口仍为既有开放项。

本批[独立检查](check-result.md)与[最终提交计划](commit-plan.md)均限定上述范围。父任务AC4/5/7仍待后续信号/行为及联合验收，不提前结案。
