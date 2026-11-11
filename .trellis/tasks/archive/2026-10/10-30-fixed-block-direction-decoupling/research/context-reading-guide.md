# 大规范的定向读取导航

部分规范超过原生 context injection 的单文件 32 KiB 限制，直接登记会截断。manifest 注入体积合适的专项规范与本任务研究；下列大文件在进入对应改动前按章节读取。此导航不是新规范，也不改变原文所有权；最终需求和待实施设计见任务的 `prd.md`、`design.md`、`implement.md`。

## SimpleSignals

源文件 `.trellis/spec/simple-signals/backend/index.md`：读取“世界定义持久化”“固定闭塞定义与索引”“固定闭塞进路资源”“后续单线支持的边界”“时刻表静态资源依赖”“定义编辑事务”“具体固定闭塞信号”“依赖与打包不变量”“质量检查”。

保留：不可变 publication、按 rail 的候选索引、同一物理区间一个资源、真实车尾释放、内部起步 floor、无配置与未知状态的区别、定义事务失败保持旧值、Java consumer 和 JAR 隔离。旧 bidirectional 权限和整块方向一致条款由本任务替换；不得删掉拓扑不可用／未知故障门禁。

源文件 `.trellis/spec/simple-signals/backend/section-authoring-wands.md`：读取“区间 Wand”“信号 Wand”“整体删除”“入口”“模型摆放”“数值编辑”等相关章节。保留两阶段逻辑绑定／模型放置、实际边界朝内点击、唯一性、物品设置、revision 与事务；只移除单双向入口过滤和创建分类。

## SimpleATO

源文件 `.trellis/spec/javarailways/simpleato/operational-routing-and-authority.md`：读取方向配置消费、冻结路线、admission client、pending／continuation、换向、限制合成等章节。运营策略归 ATO，物理准入归 Basic；不重跑第二次寻路，不提前发布尚未授予的权限，不清理旧 claim 作为重试。

源文件 `.trellis/spec/javarailways/simpleato/reactive-signal-epochs.md`：读取 source 注册／替换、信号 epoch 顺序、按 rail 变化分发、restriction 发布和失败关闭章节。新方向约束复用现有 source／snapshot 管线，不创建事件总线或世界扫描；无外部信号 source 时也要处理 ATO 方向失效。

## Basic 仅作为必须保持的边界

源文件 `.trellis/spec/javarailways/basic/route-resource-admission.md`：读取“不可删除的物理资源底线”“插件附加资源”“申请、准备和释放”“同一 operating route 的后续请求”及请求／结果、换向相关合同。

Basic 只接收冻结路线与请求边界，再原子组合物理底线和插件资源。真实 grant、claim、pending、准备和车尾释放含义不变；不能伪造较短 grant，不能先释放闭塞再申请回程，不能让插件漏贡献表示拒绝。本任务不修改该运行 API，也不把闭塞类型或运营策略搬进 Basic。

## 读取方法

先用 `rg -n '^##|^###' <规范文件>` 找到当前章节，再读取相关范围；行号随文档变化，不把本导航行号写死为实现条件。发生冲突时区分旧 CURRENT 行为和本任务明确要求替换的合同；其余安全、线程和事务规则仍须遵守。
