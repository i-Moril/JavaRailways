当前最终验收已更新为53源c1d805b9、8模块2507项零失败；见 [父最终记录](parent-acceptance.md) 与 [独立复核](parent-acceptance-check.md)。下文旧候选/实施中列表仅保留历史，不代表当前开放项。

# 第四批与父任务最终验收证据（实施中）

基线：main9ed27818加第三批未提交27源a5095090；全1124源快照见baseline.json。用户要求继续而不立即合并，本批尚无最终通过结论。

## 本批真实路径

- [ ] 同一真实CarBehavior/TrainBehaviorRuntime/CarOperationBatch在LIVE出口与SIM入口执行；各exact car独立持久Context，同源两SIM独立。
- [ ] 源包SNAPSHOT/FOLLOW_PACKAGE及cross-package composition正确捕获；缺已引用源明确失败，捕获后源World/manager不被目标使用。
- [ ] 混装安装方向、真实Core.reverse触发onReverse，后续动作使用新方向，已接受延迟保持原目标/物理侧。
- [ ] 多延迟动画/声音全部启动后才完成；拒绝、非法脚本、未知动作/动画、初始化/声明副作用失败可观测；CabLights/Announce不再假成功。
- [ ] cancel前未派发不执行；已接受可继续但终态不翻成功；car destroy/同ID重建/Worldclose结束pending并取消迟到作用。
- [ ] 真联结/解编保留exact car runtime和既有pending动作；新car不扩大旧目标，结构后新train/原train可再建ATO并使用当前每车真实profile。
- [ ] 目标operation资源在physics/generic真终止后关闭，创建失败不泄漏Context/event/ticket；关闭仅影响所属目标。

## 父任务最终联合

- [ ] 真实正式四灯+同target Basic资源/inspection+Dispatcher时刻表逐车操作gate与碰撞/事件运行，不以空source/估时gateway替代。
- [ ] LIVE-LIVE/LIVE-SIM/SIM-SIM独立推进及同World有序，结合前批真实owner/observation/logic barrier与本批实际完整运行确认；当前联合候选重新回归。
- [ ] 源LIVE的frame/车/预约/Dispatcher/信号未被SIM修改，两个同源SIM相同文本ID不串状态。
- [ ] 当前11模块领域覆盖inventory已逐项核，必需Feature/extension缺prepare失败；平台专属entity/UI/render排除，任意第三方listener自动克隆不作承诺。
- [ ] 最终源码/独立全范围check/8模块全test/API边界/jar/Dokka/frontend/docs全部实际执行并记录XML+SHA；无持久化格式/版本更改、无push/deploy/merge。

动画能力用真实定义和既有转换/scene normalization；LIVE视距导致的renderer是否已生成仍是Bukkit平台状态，本地SIM不复制它，不把静态有效effects测试写成服务端可见性验收。完整模块coverage、确定缺口与未实测层次见feature-coverage.md。
