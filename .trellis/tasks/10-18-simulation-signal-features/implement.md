# 子任务执行计划

## 前置条件

- [ ] 用户批准父任务最终规划摘要。
- [ ] 依赖10-18-world-runtime-isolation；使用其已验证的捕获/安装和本地资源接线。
- [ ] 当前独立实现工作区、基线、依赖提交和文件ownership已核对。
- [ ] 读取父/子prd、design、manifest及phase 2.1，再启动本子任务；不启动父任务代替子任务。

## 实现与检查

- [ ] **S1**：将manager的共享串行SignalLogicRuntime改为各LIVE/SIM目标自有执行/关闭归属；保留同World必要互斥和只读输入共享。
- [ ] **S3**：核对manager/global catalog锁范围，单World规则编译/求值/等待不得占跨World目录锁。
- [ ] 复用完整SimpleSignals编译/安装及规则，接通模拟资源/inspection/epoch/admission链。
- [ ] **S2**：真实四灯规则及SignalLogicRuntime阻塞/关闭/失败矩阵覆盖LIVE-LIVE、LIVE-SIM、SIM-SIM，同World有序且其他实例继续正确发布。
- [ ] 添加其余prd中的真实规则业务对照，不以空source、对象不同或空任务代替运行隔离。
- [ ] 派发Trellis检查代理，处理本子任务确定问题；设计范围变化交回父会话。
- [ ] 运行受影响定向检查后执行以下模块门禁，并记录实际候选与限制。

```bash
./gradlew :SimpleSignals:check :Basic:test :SimpleATO:test :BukkitPlugin:test
./gradlew :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries
python3 .trellis/scripts/task.py validate .trellis/tasks/10-18-simulation-signal-features
git diff --check
```

- [ ] 更新本子任务实际owner规范、父验收及原任务对应开放项；不把未执行检查标为完成。
- [ ] 只提交本批拥有的修改，向父任务提供commit、验证结果和未验证范围。

## 最终集成

跨子任务并发/业务对照/平台副作用隔离由父任务最终复核，不以局部绿测试替代。


2026-10-19计划更新：明确各LIVE World之间的JS求值隔离，与每SIM独立实例共同归本任务；O1 observation构造由依赖子任务2先完成。必要Live管理/注册短锁继续保留，任务状态仍planning。
