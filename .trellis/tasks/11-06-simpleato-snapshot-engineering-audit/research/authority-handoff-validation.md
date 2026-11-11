# F6–F8 修复验证

## 范围与基线

工作区基于 `b89b0d5d`，保留此前F1–F5和无关修改。用户批准修复Mission/保留授权交接及精确边界；未批准提交、部署或其余审查项目。没有远程修改、存档变更、容差扩大、授权旁路或新申请器。

## 实现与独立复核

- controller使用同一Core帧定位实际授权进度；删除Mission不一致时返回路线总长的分支。同Mission运营进度与inspection/外部限制/净空共用定位结果。
- 最小occurrence提示只保存距离、帧revision、原轴朝向，避免重复经过同一轨道时丢失位置；不是物理快照副本。
- Dispatcher成功换向rebase或编组adopt后立即安装同一已授予车体路线。绑定安装与inspection readiness分离，不额外申请资源，不跳过后续移动门禁。
- SimpleSignals固定闭塞与保护区域匹配共享精确端点转换；已知端点直接复用，内部点先局部相减。
- 独立复核见 `authority-boundary-check.md`；原故障来源与数值见 `authority-handoff-repair.md`。

## 回归过程

1. SimpleATO首轮聚焦49项通过。只临时恢复旧假终点分支，5项回归失败；删除精确hunk后49项再次通过，临时分支不留在源码。
2. SimpleSignals聚焦40项通过，含5项新测试。
3. 首轮完整SimpleATO805项中4项失败：三个实际折返交接、一个连挂后继续。它们暴露controller绑定遗漏，不能以放宽定位处理。
4. 补齐owner交接后六个相关类220项通过。新增真实环线cursor（同帧重复、原route刷新、前缀追加）并强化编组/换向立即失败断言后，追加38项通过。
5. 最终联合门禁通过，2m52s。XML汇总3321项：3320通过、0失败/错误、1个原有opt-in跳过。Core541、Basic467、SimpleATO806、SimpleSignals682、Bukkit728（含跳过1）、TunnelBridgeBuilder31、WebUI后端66。Core/Basic/Tunnel/WebUI本轮为UP-TO-DATE，沿用前轮已验证且输入未变的结果；SimpleATO/Signals/Bukkit本轮实际执行。
6. SimpleSignals插件产物、宿主shadowJar、Java API消费者编译及verifySignalModuleBoundaries通过；仍有既有JDK探测、弃用API、native-access与非空断言警告，不称零警告。定向控制测试使用真实PhysicsCoordinator但部分使用既有运动学测试执行器，不能代替设备/游戏验收。

## 最终命令

```bash
./gradlew :Core:test :Basic:test :SimpleATO:test :SimpleSignals:check \
  :BukkitPlugin:test :TunnelBridgeBuilder:test :WebUI:test \
  :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries \
  --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g \
  -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle

./gradlew :BukkitPlugin:authorityFixedReplay --offline --console=plain --max-workers=1 \
  -Pkotlin.daemon.jvmargs=-Xmx2g \
  -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle \
  -I /tmp/jr-authority-fixed-mcUBF5/replay.gradle
```

测试CPU/heap设置只在临时init文件，不改仓库默认构建。Gradle全部串行。

## 原始回放约束

独立目录 `/tmp/jr-authority-fixed-mcUBF5`；沿用前次正常探针，不启用counterfactual边界替换或2100Tick上限。延误阈值1200、真实S100动力学、Basic准入、ATP保持。请求为完整时刻表周期。观察代理调用原服务，只记录结果。

输入沿用 `implementation-validation.md` 中三份SHA256：基础设施 `ab1d2e6b6d8c961974633ce031c2fb27241bb6ac72f2d3cb0b4da57936fc5d63`，信号 `615c00ceeb3938f59de32f00350269066abe34b9c90962d56a850d36f1fba77a`，full.js `3e33046c997116d933e35a381cc201e94a281cd2e06ee1cf55746cda151c76a9`。模型和规则包不变。沿用原回放的禁用脱轨monitor设置，不宣称完整安全/游戏验收。必须检查REPLAY_RESULT，不能以JavaExec返回0代替模拟成功。

## 正常回放结果：原阻塞消失，后续车门作业阻塞

日志 `/tmp/jr-authority-fixed-mcUBF5/replay.log`，JavaExec38s正常结束，但模拟返回Failed而非Completed。

- 输入验证success=true。102P/2号交路在原Tick2002之后继续，下一站arrivedTick3668；原假位置ATP故障不再出现。
- Tick14000，5号车请求边界2029.9628503954348获Granted，inspection length=89.22000000000001（实际bodyTraversal=89.20009465736888），frameEpoch和inspection stamp均14000。ATO head精确等于当前Core head、不等于上一帧；Tick14800再次请求2861.2154729167855获Granted，同样有有效车长和当前帧。不再是原车长null→全路线3763.125728请求；不同运行轨迹不能将这两次边界差直接解释为精确延误贡献。
- Tick14219不再DelayExceeded：5号车APPROACHING kasaipark/1，速度25.463231404841515m/s，lateness=1；3号车APPROACHING etchujima/1，速度5.02999755575389m/s，lateness=646，小于保持不变的1200阈值。原日志同期3号车delay=1219导致整体停止。仍有正常资源等待，未声称所有晚点消失。
- 新阻塞在Tick18402：4号交路/115N，kasaipark/1，state=FAILED，reasonCode=StationProgramFailure，`doorless stop cannot clear previously open-door safety evidence`。当前触发点为TimetableDispatcher.beginStopProgram中 `effectiveSide == DoorSide.NONE && stationDoorsSecured == false`；该检查本批未改。尚未调查为何进入该组合，也不将其称为已证明正确或已修复；单独留待下一批，未绕过车门检查以跑完模拟。
- 结果返回后的本地资源关闭另记录onReverse回调`car behavior destroyed`警告；发生在REPLAY_RESULT之后，不能当作Tick18402首因，本批未修关闭流程。

A5/A7/A8所要求的原故障对照与限定修复验证完成；整份时刻表尚未跑通，不能报告全局模拟全部通过。规范按Trellis检查同步，无提交、推送、部署或自动归档。
