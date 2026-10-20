# 非空 LIVE 状态在两次隔离模拟前后的保持验收

## 补齐的证据边界

此前真实 Bukkit 联合测试覆盖信号、逐车行为、时刻表、碰撞与两个目标独立推进，但源 LIVE 的 Dispatcher/claim 为空，不能据此证明已有运营状态在 capture/create/run/close 全周期不变。

本次仅修改 `SimpleSignalsWorldRuntimeManagerTest.kt` 的测试及可选 fixture 参数，增加实际执行的单例：

`capture create run and close preserve nonempty live claims dispatcher frames and formal signals`

没有修改生产接口、规则、生命周期、持久化、锁或调度；没有增加反射入口。复用原 `actualSimulation` fixture 的既有 feature-owned runtime 观察方式。

## 真实源与目标

- 源为 `WorldExecutionMode.LIVE` 的实际 CoreWorld，automaticFallback=false。加载分发 `jr-four-light` 正式 JS，经 manager 安装 source。
- LIVE 通过 `OperationalWorldRuntime`、`RouteResourceAdmissionRuntime`、所属 World 唯一 `infrastructureCoordination`、正式 `FixedBlockRouteResourcePolicy` 组合；没有平行 registry，也没有直接伪造 claim。
- `TimetableDispatcher.startService` 取得实际列车，并由真实 `SimpleATO`/`HandleTrainActuator` 与现有 TrainControlConfig/CarControlConfig 建立未来发车等待 run；非空状态必须是 `WAITING_TIMETABLE_TIME`。
- LIVE 发布真实 epoch 后，Basic 实际申请并持有 rail interval 与正式 `test:actual-block-3` token，四盏正式信号为 proceed/slow/caution/stop。随后不再推进该 LIVE；不是复制它的 runtime 成为 SIM。
- 一次 `prepareSimulation` 复用到两个独立 target。两个目标分别新建同 ID `actual` 列车、独立运行规则、取得同名 block 的各自 claim。A 改方向而收紧，关闭并释放自身 claim；B 继续完成下一实际 epoch，仍持有自身 claim 和正确灯色。

## 每阶段精确对照

capture 后、每次 create 后、每个 SIM 运行/申请后、A 方向修改后、A close 后、B 继续后及两个 close 完成后，核对：

- 源完整 World frame、每辆 train frame 保持同一已发布对象；列车成员集合不变。
- 源 `registry.claimSnapshots()` 与实际非空基线完全相同。
- 源非空 `Dispatcher.statuses()` 完全相同。
- 源正式 `sectionSignalSnapshot()` 保持同一不可变快照。初次真实 epoch 校验其 stamp 精确匹配该次 physics epoch；后续无关物理推进不改变信号输入，按响应式合同保持同一 baseline 快照，而 completed observation stamp 精确推进到冻结的最新 physics epoch。
- 源 train restriction snapshots、completed observation stamp 和运营方向不变。

这项验证补父任务 R2/最终联合验收的源运行状态保持证据；不替代已有 Bukkit 行为联合测试，也不宣称 Paper/Folia 现场验收。

## 验证

最终 **PASS**。父会话持唯一构建令牌执行当前候选 `c1d805b99ba0bff1ed7cd2c18b49b6bf6152b6785a31431c93ac26a959fdabda` 的完整门禁：8 模块 2507 项 / 364 suites，零 failure/error/skip；SimpleSignals 648 项、Bukkit 544 项。`/tmp/simulation-parent-complete-gate.log` 记录 `BUILD SUCCESSFUL in 1m 53s`。

本代理直接读取最终 `SimpleSignals/build/test-results/test/TEST-work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManagerTest.xml`：manager suite 160 项零失败，新增确切方法存在，执行 0.356s，无 failure/error，非跳过。因此真实执行已经覆盖源非空状态建立、两个 target 的运行、A 关闭后 B 继续、全部 source 精确对照及最终关闭。最终 XML 已由父会话保存在 `../simulation-parent-acceptance-baseline-20261020/evidence/final`。

编辑后 `git diff --check` 通过。本轮只有测试和研究记录变化，生产字节保持此前已复核的实现；没有提交、合并或现场部署。下面两次初始 fixture 失败均已修正，并由上述最终通过结果覆盖，不是尚未解决的问题。

首轮 focused 确认测试真实执行并暴露测试断言错误：源物理 epoch 已到 3，输入未变化的正式信号正确保持初次 epoch 1 baseline；原断言错误要求每个物理 epoch 重算信号。依据 simulation-runtime 既有「未变输入继续 IDLE」合同及 TrainRestrictionSnapshotStore.completeEpoch 的独立 completed boundary，改为校验初次 baseline 的真实 epoch、保持完全相同的 baseline 对象及最新 completed stamp == 冻结 physics epoch。未修改产品或正式灯色/资源对照；最终重跑已通过。

第二轮 focused 进入真实 SIM 规则编译，发现新测试的 LIVE composition 替换 BasicWorldData 时丢失 actualSignalWorld 原有 railMetadata，因而静态快照缺少正式规则所需有限限速。测试装配改为保留同一个源 World 原来的 railMetadata store，再由既有 capture/restore 复制到各 target；方向仍创建带真实 signal 通知的 store。不绕过编译、没有放宽规则；最终重跑已通过。
