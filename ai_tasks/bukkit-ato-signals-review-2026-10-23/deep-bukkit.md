# Bukkit API 专项复审

基线：dev/signal-system / a0206a9ca9698e0bc2cbd3f7dc149f02463feeb2。审查当前模块实现而非diff；本轮未修改仓库，未构建/执行测试，未建任务。既有脏文件集中SimpleSignals及spec，保持原样。按repo review skill专项检查完成权、准确注册身份、close失败/重试；逐文件范围见同名coverage.tsv。

## BUK-20 P2 旧资源provider注册handle可以删除复用同一provider对象的新注册

位置：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultInfrastructureResourceService.kt:294-300,366-400`。

公开API可构造的顺序，无竞态要求：注册(owner,type,provider)取得handle A；待活动资源清空后正常`unregisterOwner(owner)`；同一个仍enabled的owner用同一provider实例重注册type，取得B；延迟清理调用A.close。unregisterOwner748-750只删除目录条目，没有更新A捕获的registrationClosed；A.close通过owner/provider身份检查，删除新B。若B此时有活动资源，A会错误检查B的活动而抛错；若无活动则静默撤回B，后续资源存在/贡献校验失败。

合同：InfrastructureResourceProviderRegistration写明“Removes this provider registration”；slot身份不等于一次registration身份。当前ProviderRegistration对象确实存在，但返回handle未捕获它，只捕获其owner/provider字段。正确对照：ContributorRegistration的close使用registration实例，DefaultSimulationFeatureService也remove(id,registration)。

反证：A若已经成功close则AtomicBoolean挡住重试；因此必要前提是通过服务级unregisterOwner退出旧注册后A未close，而不是“重复close任意旧handle都破坏新注册”。现有provider测试覆盖close拒绝后重试、validation中注销，未覆盖此合法owner注销/重新注册/旧handle迟到关闭顺序。不声称当前SimpleSignals一定复用同provider对象或现场发生。

最小方向：句柄捕获准确ProviderRegistration，并按相同registration实例认证/删除；拒绝时保留该句柄可重试语义。

## BUK-21 P2 selectTurnout泄漏内部队列操作Future完成权

位置：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultInfrastructureResourceService.kt:436-442`；直接原因边界`Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt:66-75,300-315`。

链：公开selectTurnout→submitBetweenFrames→返回operation.result原对象；BetweenFramesOperation.execute先检查result.isDone，已完成就跳过action。附属API消费者对该返回future调用complete(SELECTED)、completeOnTimeout(fallback,...)或orTimeout等会直接完成内部执行门禁。若在drain前发生，真正道岔校验/修改/发布永远不会执行；若在action执行中发生，则结果会早于实际完成发布。伪造成功可被任何共享该future的正常whenComplete消费者观察到。不是只有外部能够改变自己观察副本的结果。

合同：InfrastructureResourceService.java:99-109明确future在claim检查、occupancy检查、selection及一致世界发布后完成；没有定义cancel作为撤销请求合同。不把用户明确cancel后的“可以不执行”本身列为bug；关注complete、超时装饰器对内部队列的完成权以及观测与真实操作的脱钩。当前TurnoutWand226-238仅注册whenComplete，不修改future；真实UI正常路径未证明触发，本项范围是公开API外部消费者。现有service测试745-825验证未drain时pending、实际选择、owner失效、world重载，未覆盖消费者完成/超时。

最小方向：公开边界返回与内部完成权隔离的观察future；若支持请求撤销，另行明确撤销合同并保持success意味着真实操作完成。Core submitBetweenFrames也是根源，可在准确边界修正，但本复审不扩大为Core全模块结论。

## 已审方面与排除结果

- Model/Editor closeOwner：均allOf(...).minimalCompletionStage，内部cleanup没有经公开API泄漏。调用方toCompletableFuture拿到可写观察副本不能完成内部cleanup。closeOwner只观察快照，合同明确调用前先停止创建/提交，因此并发新建未被旧closeOwner覆盖不是缺陷。
- Model/Editor准确身份：插件/world/viewer使用引用身份；active.remove(identity,this)保护同ID新handle；旧handle停止提交，world注销绑定精确实例。Model跨位置先删除再新建，保存最新pending。未确认新缺陷。
- Model/Editor清理异常：outstanding仅成功清理删除，失败后续closeOwner仍异常。模型/文字独立资源分别尝试；spec明确要求后续仍能看到失败，没有承诺重试实际删除。不可将“不重试已失败destroy”自行改写成违反合同。测试分别覆盖root删除失败不跳过children、多个文字清理、host disabled、scheduler拒绝与retired fallback。均只读未运行。
- Infrastructure close/removeWorld异常短路候选已排除：串行语法确实没有aggregate，但实际Basic ManualTurnout.close仅置布尔；RouteResourceAdmissionRuntime.close仅关闭Core topology监听注册；CoreWorld注册返回AtomicBoolean+集合删除，resourceValidator注册返回map删除，不调用任意外部清理。没有找到当前生产可达抛错触发，不报告为确认bug。任意测试注入AutoCloseable抛错不等于实际生产故障。
- PlayerOperationService返回可写resultFuture，但该future不控制内部history/inFlight执行，内部继续等operationStage，和BUK-21影响不同。没有同等内部状态完成权证据，不重复报高优先级。
- Observation注销后的已捕获listener批次仍可能调用旧listener，但public文档没有“注销等待/取消在途回调”保证；暂无足够契约证据，未升格。
- 异步undo owner停用后若外部stage永不完成，inFlight可保留并阻塞后续；公开接口未定义在途取消/完成责任，且贸然释放会允许实际未停止的操作重叠。保留合同缺口，未声称现有生产触发。
- 父审BUK-22独立反证：PartRegistry.load仅parse；ModelManager.buildResourceVersion用SavedCarModelTree收集，putIfAbsent避免重复遍历却接受引用环；专用buildPart无环门禁。普通模型compiler的环校验不覆盖车体模型，旧resolveTree无生产caller。支持父审发现，未另编号。

证据等级：全部当前源码/合同与测试阅读，未执行任何动态探针、构建、测试或游戏场景。重点不意味着重新全模块审完；DefaultSignalEpochSourceService本轮仅定位/部分阅读，非本专项完整覆盖。前轮覆盖ledger继续有效。
