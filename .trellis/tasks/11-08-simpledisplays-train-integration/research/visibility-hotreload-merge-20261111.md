# 显示与热重载修复合并（2026-11-11）

- 当前集成基线322b2a3b，热重载来源867ed0f2（1718739b/af656dea/867ed0f2），显示恢复来源e373c3e1。
- 用户要求先完成两个合并再统一检查；merge commits f8a54dcb和1afcb288保留两个来源祖先。显示来源最初未提交，已只读保存8文件哈希快照；用户告知提交后逐项确认e373c3e1与该快照相同。原工作区未修改。
- 合并保留当前无领域DI、逐世界owner、逐车行为、metadata/运行线、Decorations、TextDisplay、异步销毁及乘客恢复接口；旧架构实现按当前owner移植。
- packet可见性：live root anchor生成、仅已发送区块观众、发送generation与重发恢复、普通移动不反复spawn；监听器在composition注册，runtime只清理自有资源。
- 热重载：保留入口/旧回调失效、物理和执行器停止、失败快照保护、服务租约及精确注销。PacketEvents改为外部独立插件硬依赖，主包不得内嵌其class；没有部署服务器或改其插件配置。
- 两个分支原来分别实现各自关闭保证；当前destroy将异常放入CompletionStage，合并适配用PlatformCleanup.awaitCompletion接入原清理batch，并非替换旧机制。Folia整服停止的skip-owner分支返回已完成结果而不访问实体。
- 合并后检查发现全局先stopAndAwait再processBetweenFrames恢复控制会必然失败，修正为逐世界先恢复手动控制、再有界停止、最后快照；真实coordinator回归覆盖顺序。
- 旧测试API按当前ThreadGuardHost/World/dispatcher/捕获DialogInputValues适配，保留断言；physics sibling回归分离列车位置以避开真实碰撞预检。

统一验证记录待本批构建与回归完成后补充。未部署/推送；真实Paper/Folia在线玩家与PlugManX矩阵仍独立验收。

## 统一验证结果

生产/测试源码编译、主插件与TBB打包、Decorations普通JAR边界及verifySignalModuleBoundaries通过（/tmp/jr-merged-fixes-compile.log，19s）。主JAR实查不含原始/重定位PacketEvents class，声明独立packetevents硬依赖，WebUI嵌入保留。

- 定向BukkitPlugin：{'tests': 58, 'failures': 0, 'errors': 0, 'skipped': 0}
- 定向Core：{'tests': 51, 'failures': 0, 'errors': 0, 'skipped': 0}
- 定向TunnelBridgeBuilder：{'tests': 3, 'failures': 0, 'errors': 0, 'skipped': 0}

回归日志：/tmp/jr-merged-fixes-regression.log（9s），/tmp/jr-merged-fixes-core-regression.log（11s）。范围仅本次合并，不等于主显示任务延期的完整套件/真实服务器验收。失败迭代修正重复import、构造参数保留、旧ThreadGuard/World/实例字段/dispatcher测试夹具；一次native工厂测试夹具未执行删除回调造成等待，只终止准确定位的该次测试worker并改成立即执行。兄弟worker测试位置改在实际首段100m内的75m，保持两列间距与原断言。最终所有所选测试零失败/错误/跳过。
