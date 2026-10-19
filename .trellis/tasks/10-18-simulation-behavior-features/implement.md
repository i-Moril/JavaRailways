# 子任务执行计划

## 前置条件

- [ ] 用户批准父任务最终规划摘要。
- [ ] 依赖10-18-world-runtime-isolation；本子任务最终联合验证也依赖10-18-simulation-signal-features。
- [ ] 当前独立实现工作区、基线、依赖提交和文件ownership已核对。
- [ ] 读取父/子prd、design、manifest及phase 2.1，再启动本子任务；不启动父任务代替子任务。

## 实现与检查

- [ ] 按design复用当前实现，补齐本子任务行为差距；不扩展相邻Feature。
- [ ] 添加prd列出的真实路径回归及必要的barrier/失败矩阵。
- [ ] 派发Trellis检查代理，处理本子任务确定问题；设计范围变化交回父会话。
- [ ] 运行受影响定向检查后执行以下模块门禁，并记录实际候选与限制。

```bash
./gradlew :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:test :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries
python3 .trellis/scripts/task.py validate .trellis/tasks/10-18-simulation-behavior-features
git diff --check
```

- [ ] 更新本子任务实际owner规范、父验收及原任务对应开放项；不把未执行检查标为完成。
- [ ] 只提交本批拥有的修改，向父任务提供commit、验证结果和未验证范围。

## 最终集成

跨子任务并发/业务对照/平台副作用隔离由父任务最终复核，不以局部绿测试替代。
