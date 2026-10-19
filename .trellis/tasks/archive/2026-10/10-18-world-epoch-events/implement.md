# 子任务执行计划

## 前置条件

- [x] 用户于2026-10-18批准父任务最终规划摘要。
- [x] 无前置子任务；本批已获批为首个实施子任务。
- [x] 独立工作区`.worktree/world-epoch-events`、分支`codex/world-epoch-events`、基线`a76b076c`，未复制原工作区运行时代码WIP；产品文件由实现子代理拥有。
- [x] 读取父/子prd、design、manifest及phase 2.1，再启动本子任务；不启动父任务代替子任务。

## 实现与检查

- [x] 按design复用当前实现，补齐本子任务行为差距；不扩展相邻Feature。
- [x] 添加prd列出的真实路径回归及必要的barrier/失败矩阵。
- [x] 派发Trellis检查代理，处理本子任务确定问题；设计范围变化交回父会话。
- [x] 运行受影响定向检查后执行以下模块门禁，并记录实际候选与限制。

```bash
./gradlew :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test
./gradlew :BukkitPlugin:compileSignalApiConsumerJava
python3 .trellis/scripts/task.py validate .trellis/tasks/10-18-world-epoch-events
git diff --check
```

- [x] 更新本子任务实际owner规范、父验收及原任务对应开放项；不把未执行检查标为完成。
- [x] 只提交本批拥有的修改，向父任务提供commit、验证结果和未验证范围。

## 最终集成

跨子任务并发/业务对照/平台副作用隔离由父任务最终复核，不以局部绿测试替代。

2026-10-18：本批实现与独立检查通过，1711项模块测试通过，Core/Basic强制重新执行855项通过；详见[验证记录](research/validation.md)。用户于2026-10-19确认本地提交方案；主工作区运行时代码WIP未复制或覆盖，本批提交后曾保持in_progress，等待主分支整合。

2026-10-19：积压改动提交后本分支已无冲突整合至dev/signal-system，组合2343项测试和边界/文档门禁通过。本子任务完成，可归档；不将此完成状态扩大为父任务全Feature验收。
