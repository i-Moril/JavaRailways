# 父任务最终验收记录（2026-10-20）

约定范围内的 World 隔离与完整领域 Feature 模拟已通过代码和自动化验收。当前实现已提交在独立分支：`207807eb`（docs `3bdd5626`）。尚未合并；没有推送、部署或迁移。

## 当前候选

- 分支 `codex/simulation-world-alignment`，基于并重新核对 `dev/signal-system@87fe3e4567c4b9fddbcee3c0b5975e460a7e05c7`。
- 53 源路径 SHA 见 [parent-candidate.json](parent-candidate.json)，当前指纹 `c1d805b99ba0bff1ed7cd2c18b49b6bf6152b6785a31431c93ac26a959fdabda`；门禁后逐文件零漂移。与已修三个P2的c781候选相比仅两份测试改变，产品源码逐字相同。
- 原候选、P2修复前快照和本轮前置字节备份均保留。当前完整恢复补丁保存于同级 `simulation-parent-acceptance-baseline-20261020/`。

## 本轮补齐

- 真实 `RailwayObservation.fromCore` 内部阻塞覆盖 LIVE–LIVE、LIVE–SIM、SIM–SIM；每组都让另一方完成真实物理位移、领域阶段与 observation 发布，同时保留同World缓存、通知和退休合同。
- 正式四灯 LIVE 首先发布有效信号，并持有真实 rail/fixed-block claim、非空 Dispatcher run。随后捕获并创建两个同ID的SIM，分别真实申请同资源、推进、改方向和关闭；逐阶段断言源World/列车帧、claim、Dispatcher状态、正式信号、限制与完成stamp不变。
- 新fixture最初错误要求信号snapshot每epoch重建，后修正为既有响应式IDLE合同；另保留源原有真实静态限速store供capture。两次初始fixture失败已解决，不改变产品规则或放宽源状态前后对照。
- 三个既有P2修复及两项旧实现RED回归证据继续有效，见 [review-fixes-validation.md](review-fixes-validation.md)。本轮不重复其产品实现。

## 验收与验证

父R1–R6、AC1–AC7及最终联合清单逐项证据见 [独立覆盖复核](parent-acceptance-check.md)。逐车行为、信号、碰撞、时刻表联合及运行隔离均已有实际领域fixture；不将单个用例扩大为全部流水线证明。

- 最终完整门禁成功：8模块、364 suites、2507实际JUnit用例，0失败、错误或跳过。Core505、Basic390、SimpleATO375、SimpleATOBukkit2、Bukkit544、SimpleSignals648、TunnelBridgeBuilder31、WebUI12；Bukkit及Signals包括本轮全部新增用例。未变化任务由当前候选的Gradle输入/输出检查复用，未强制重跑全部未变化任务。
- 当前前端108用例及生产构建通过；Java API消费者、信号模块/JAR边界、Bukkit shadowJar、SimpleSignals check、三模块Dokka门禁通过。
- docs-site threading-model源SHA与已通过P2 `npm run check` 的文件完全相同，本轮未再次运行docs-site check；不冒充新执行。
- XML、日志、源hash和门禁状态见 [机器证据](parent-final-evidence.json)，完整复制在同级恢复目录 `evidence/final`。父/子context validate及包含untracked文件的临时index whitespace检查全部通过，实际Git index保持空、无冲突；见 [收尾证据](parent-wrap-evidence.json)。

## 交付边界

全Feature按用户约定不含Bukkit实体、显示、音响和玩家交互。当前自动化通过不代表Paper/Folia服务端、客户端或浏览器现场验收；本轮均未执行。

正常业务、guest脚本错误与关闭资源按目标World隔离；保留上游RailwaySimulationHalt对未知宿主fatal故障的全进程停止政策，不承诺进程资源/崩溃隔离。平台显示状态及既有curve-offset结构继承矩阵的历史边界见前序独立报告。

原坐标/UI提交、主分支源码、旧worktree和无关WIP均保留。功能验收已回填；用户现已授权并完成本地实现提交，合并仍暂缓。当前行为任务按Trellis收尾归档，其他任务保持原整合状态。
