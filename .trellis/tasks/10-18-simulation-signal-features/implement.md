# 子任务执行计划

## 前置条件

- [x] 父任务规划已确认，2026-10-19用户在子任务2完成后批准继续第三批S1-S3及完整信号模拟。
- [x] 依赖10-18-world-runtime-isolation；使用其已验证的捕获/安装和本地资源接线。
- [x] 独立实现工作区.worktree/simulation-signal-features，分支codex/simulation-signal-features，基线9ed27818，前批依赖已合入；产品ownership在研究后限定。
- [x] 读取父/子prd、design、manifest及phase 2.1，再启动本子任务；不启动父任务代替子任务。

## 实现与检查

- [x] **S1**：将manager的共享串行SignalLogicRuntime改为各LIVE/SIM目标自有执行/关闭归属；保留同World必要互斥和只读输入共享。
- [x] **S3**：核对manager/global catalog锁范围，单World规则编译/求值/等待不得占跨World目录锁。
- [x] 复用完整SimpleSignals编译/安装及规则，接通模拟资源/inspection/epoch/admission链。
- [x] **S2**：真实四灯规则及SignalLogicRuntime阻塞/关闭/失败矩阵覆盖LIVE-LIVE、LIVE-SIM、SIM-SIM，同World有序且其他实例继续正确发布。
- [x] 添加其余prd中的真实规则业务对照，不以空source、对象不同或空任务代替运行隔离。
- [x] 派发Trellis检查代理，处理本子任务确定问题；设计范围变化交回父会话。
- [x] 运行受影响定向检查后执行以下模块门禁，并记录实际候选与限制。

```bash
./gradlew :SimpleSignals:check :Basic:test :SimpleATO:test :BukkitPlugin:test
./gradlew :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries
python3 .trellis/scripts/task.py validate .trellis/tasks/10-18-simulation-signal-features
git diff --check
```

- [x] 更新本子任务实际owner规范、父验收及原任务对应开放项；不把未执行检查标为完成。
- [x] 只提交本批拥有的修改，向父任务提供commit、验证结果和未验证范围。

## 最终集成

跨子任务并发/业务对照/平台副作用隔离由父任务最终复核，不以局部绿测试替代。


2026-10-19计划更新：明确各LIVE World之间的JS求值隔离，与每SIM独立实例共同归本任务；O1 observation构造由依赖子任务2先完成。必要Live管理/注册短锁继续保留。2026-10-19用户批准继续后进入in_progress。

2026-10-19实现及门禁完成：两份研究、公开API与真实安装链已落地；9项具体检查问题已修复。最终27源a5095090对应8模块2411项全通过，16新增case全部真实执行，API/模块边界/jar/三模块Dokka及docs完整check通过。上下文18+18条有效；最终独立复核及AC证据见research/check-result.md、acceptance-evidence.md、validation.md和final-candidate.json。提交前保留本任务in_progress，车厢行为及父最终联合验收仍开放。

2026-10-20用户明确授权提交：实现/测试/规范与docs gitlink已本地提交207807ebcd974f37d921a93937f76aef9fa65393，docs独立提交3bdd5626d35e7706a3fa7a7e167b4d35b104d9d5；53源c1d805b9与2507/108已测候选字节一致并经独立staged复核。验收记录另提交；不合并、不推送、不部署。
