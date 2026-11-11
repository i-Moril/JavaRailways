# 子任务执行计划

## 前置条件

- [x] 用户批准父任务最终规划摘要，并于2026-10-19明确继续逐车行为批次，无需先合并。
- [x] 依赖10-18-world-runtime-isolation；本子任务最终联合验证也依赖10-18-simulation-signal-features。
- [x] 当前独立实现工作区、基线、依赖提交和文件ownership已核对。
- [x] 读取父/子prd、design、manifest及phase 2.1，再启动本子任务；不启动父任务代替子任务。

## 实现与检查

- [x] 按design复用当前实现，补齐本子任务行为差距；不扩展相邻Feature。
- [x] 添加prd列出的真实路径回归及必要的barrier/失败矩阵。
- [x] 派发Trellis检查代理，处理本子任务确定问题；设计范围变化交回父会话。
- [x] 运行受影响定向检查后执行以下模块门禁，并记录实际候选与限制。

```bash
./gradlew :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:test :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries
python3 .trellis/scripts/task.py validate .trellis/tasks/10-18-simulation-behavior-features
git diff --check
```

- [x] 更新本子任务实际owner规范、父验收及原任务对应开放项；不把未执行检查标为完成。
- [x] 只提交本批拥有的修改，向父任务提供commit、验证结果和未验证范围。

## 最终集成

跨子任务并发/业务对照/平台副作用隔离由父任务最终复核，不以局部绿测试替代。

2026-10-19继续授权：保留第三批未提交信号候选a5095090，在同一.worktree/simulation-signal-features上推进第四批；不急于提交或合并。所有产品src已快照到/tmp/simulation-behavior-source-baseline并记录逐文件SHA，后续检查同时核本批delta和整体候选，不把继承WIP误认他人更改或覆盖。最小实现边界由两项只读研究确认后写入design。

研究完成并审阅：car-behavior-runtime、simulation-behavior-capture、feature-coverage三份。实施分A行为adapter、B捕获/装配与结构profile接线、C独立真实联合fixture；明确ownership见design。先冻结CapturedCarBehavior及无显示car extension签名，由A发给B/C；单个构建令牌由root调度，不能并发争用共享Gradle/cache。检查必须按1124源快照记录本批delta，并复核继承信号候选，最终完整门禁重新执行，不把a509测试沿用成联合证明。

执行记录：native子代理A/B已完成源与首轮60项focused，A随后仅加强2个tests待重跑；C因native线程上限改用项目Trellis channel implement卡，Codex CLI连接失败且无源码写入，已结束该worker，按项目卡默认Claude provider继续（未覆盖模型配置）。独立全范围check也用项目check卡，只读当前源并通过channel回报，构建始终只有root授予的单一令牌。原用户的暂缓提交/合并约束保持。

2026-10-20：用户要求对齐最新dev。原工作区完整保留，整合迁至codex/simulation-world-alignment（基线87fe3e45），详见行为子任务research/upstream-alignment.md。上游未知宿主故障为全进程停止；普通业务/guest失败隔离和fatal故障边界分别验证，不能把旧基线测试或消除文本冲突当作完成证明。暂不提交或合回dev。

2026-10-20对齐收尾：最新dev87fe3e45上的48源ae862ac8已经独立检查，2491 JVM/364 suites零失败及108前端、API/JAR/Dokka/docs门禁通过。两个真实联合用例已包含在完整Bukkit532测试XML中。新增owner资源退休遗漏已修复并有失败→通过证据；其余上游合同保留。原候选和开发分支源码未改，活动工作区为codex/simulation-world-alignment，暂不提交/合并/归档。详见行为子任务research/upstream-alignment.md及upstream-check-result.md。

2026-10-20用户review follow-up：三个P2已纳入同批修复，最小owner/回归边界见design。此前ae862ac8的2491绿门禁为修改前证据；当前修复须经新定向/模块检查和独立review，继续不提交/合并。

## 2026-10-20 parent acceptance closure

Current P2 fixes are checked at52-source c781ed4f; user approved continuing without immediate merge. Parent review found two test-evidence gaps against existing requirements, not a new feature contract: actual fromCore barrier lacks LIVE-SIM/SIM-SIM mode variants; source preservation lacks one non-empty LIVE frame/claim/Dispatcher/signal before-after proof across simulation capture/run/close.

Minimal ownership: acceptance_observation_matrix changes only DefaultRailwayObservationServiceTest mode matrix; acceptance_live_preservation changes only SimpleSignalsWorldRuntimeManagerTest using existing real official-rule/live-domain fixtures. No product API or runtime/persistence changes planned. Root owns parent/child acceptance records and single build token; independent parent_acceptance_check reviews exact tests and current gates. All prior source/recovery artifacts remain preserved. No commit, merge, push, deploy or archive in this batch.

2026-10-20父最终验收：53源c1d805b9（与P2候选相比仅两测试变化）通过当前8模块2507项/364 suites，0失败/错误/跳过；frontend108和生产构建通过，API消费/模块边界/JAR/三模块Dokka通过。补齐真实fromCore的LIVE-LIVE/LIVE-SIM/SIM-SIM模式矩阵，并以非空真实LIVE claim/Dispatcher/正式信号验证同源两SIM运行关闭不改源状态。父AC4/AC6/AC7代码及自动化验收通过，现场Paper/Folia/client/browser未执行；保留上游fatal host故障全进程停机政策。提交/合并/归档暂缓。最终索引见行为research/parent-acceptance.md，机器证据parent-final-evidence.json及独立parent-acceptance-check.md。
提交条目保持未勾选，任务未归档；以上验收不代表已经提交、合并或完成现场运行验证。

2026-10-20用户明确授权提交：实现/测试/规范与docs gitlink已本地提交207807ebcd974f37d921a93937f76aef9fa65393，docs独立提交3bdd5626d35e7706a3fa7a7e167b4d35b104d9d5；53源c1d805b9与2507/108已测候选字节一致并经独立staged复核。验收记录另提交；不合并、不推送、不部署。
