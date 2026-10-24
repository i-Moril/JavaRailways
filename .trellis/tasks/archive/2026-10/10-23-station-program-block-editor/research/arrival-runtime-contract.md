# 到站执行接入证据

只读研究基线 cee24662。ScriptWorker 位于 BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker，而非独立 ScriptWorker module。

- Dsl.kt 的 appendProgram 经 expandStationProgram 编译为现有 AT 动作；destinationAt 当前只有 DESTINATION_OPTIONS 与 DESTINATION。arrivalTick 不是实际触发回调。
- TimetableDispatcher.enterStationDwelling / enterDestinationDwelling 在完成门操作后开始最小停站。普通 dwell 会等待 nextActionTick 并关门后才继续 advanceService，因此普通线性追加 program 不满足到站触发语义。
- TimetableProgramCursor/ServiceTraversal 已统一条件分支游标，可扩展 ARRIVAL 子树地址复用同一动作执行、异步 extension ticket 和条件缓存。
- restoreCheckpoint 不恢复 live cursor/ticket，而禁用受影响服务并建立 recoveryRequiredRuns。本次持久化到站定义不改变既有人工恢复要求。

确定合同：destination(target,{arrivalProgram:{parameters:[],steps:...}}) 在编译期展开到 DestinationOptions.arrivalSteps，起始tick=0。WAIT_UNTIL 是绝对时刻，WAIT_DURATION 从运行时实际执行时计时。主程序保存 continuation；到站子树结束后回到原停站 DWELLING，不能提前进入下一主步骤。停站最小时间与程序并行，程序完成和正常发车门禁均满足后才允许关门。

到站子集：SET_DISPLAY、RESET_DISPLAY、WAIT_DURATION、WAIT_UNTIL、BRANCH、EXTENSION。库其余动作继续保留；REVERSE会调整mission revision，MOVE/SELECT_STOP/STORE/FORMATION会改变停站拥有的任务或编组，不作为此内联到站上下文的叶子。

测试不得仅检查编译输出：需要实际 Dispatcher 到站顺序、零门/有门、等待、终点和条件/extension失效路径。地址/定义字段要同步 scriptworker Model、SimpleATO SavedTimetableAction 与前端路径，保留全路径错误来源。
