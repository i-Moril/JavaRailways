# 审查方法与边界

## 证据链

按“规范 → 公共接口 → 生产调用 → 状态生命周期 → 测试 → 文档”追踪，不只浏览目录。删除候选必须检查生产、测试、反射/注册入口、持久化和一次性迁移；公共 API 泄漏须检查签名及 Dokka 范围。

问题台账使用稳定编号，记录状态（待核实、已确认、已修复、延期、排除）、严重性、绝对/仓库相对证据路径和行号、影响、建议、验证与修复提交。初审线索与最终结论分开。

## 文档职责

- Trellis spec：维护者契约、内部 non-trivial 原理、不变量及验证要求；任务目录保存进度与审查历史。
- Dokka：公开类型和方法语义，不能暴露未承诺的实现类型而又隐藏其文档。
- docs-site：玩家、管理员和扩展开发者的使用说明，链接契约而不复制内部实现细节。
- `.codeabs`：简短代码导航，不成为第二份行为契约或计划台账。

该分工已随八步计划获用户批准，具体同步规则见[文档职责规范](../../spec/javarailways/shared/documentation-ownership.md)。

## 修复取舍

优先消除实际双重所有者、失效路径和错误文档，其次处理 API 边界与复杂调用链。大型类按真实职责拆分，保持唯一事务和生命周期，不以拆文件数量作为验收。

并发快照、epoch、资源授权与失败收紧必须保持；认为兜底多余时先说明可达性和替代不变量。模块移动、公共 API 改动及持久化改动必须单独评估，不混入机械清理。

## Core 审查后的产品边界（2026-10-10）

- **已确认**：联结/解编保留为正式能力；CR-021/030按功能缺陷修复，不按废原型清理。详细端部/方向组合和事务实现仍须先给出方案，不改变默认碰撞策略。
- **已确认**：没有仓库外插件不代表没有扩展需求。用户要求保留PreTickEvent/PostTickEvent/RenderableEvent并考虑接入，旧删除建议撤回；接入前明确阶段和线程语义，见[Core事件规范](../../spec/javarailways/core/event-bus.md)。Core Seat与Bukkit乘坐的分层建议仍待确认，不把两份座位列表都作为权威。
- **已确认**：开发服存档需能手动迁移，暂不要求JAR内自动迁移。格式变更先获取一致的真实infrastructure/runtime文件对（或该服实际使用的等价存档集合），记录来源shape并保留原件/恢复点；离线dry-run报告未知type、无法映射的引用及丢失风险，输出到独立目录，拒绝静默丢数据。迁移后用current严格codec及业务引用校验匹配数据，核对rail/train身份、位置、extension及涉及的运营数据。真实样本验证前不得宣称已支持该服迁移。
- **计划协调，不修改他人任务**：工作区既有未跟踪任务`09-24-explicit-rail-endpoint-connections`仍写JAR打包/startup自动迁移。本轮不改动该任务；后续推进其持久化工作前应按本次确认重新对齐验收，不把旧计划当作自动迁移授权。

定向评估见[旧事件](./research/core-event-api-assessment.md)和[座位](./research/core-seat-api-assessment.md)。事件改为保留并规划接入；不能用现有观察服务覆盖部分用途，推出全部事件都可删除。观察服务不等价于逐physics epoch或呈现完成通知，具体接线复用已有事实来源而不另算一套。Core Seat原型的删除仍只是待用户检查的建议，不受本次事件决定影响。

联结修复的待审方案见[CR-030 联结事务研究](./research/core-link-transaction-plan.md)。2026-10-11用户确认四种稳定端部组合均须支持，旧首批拒绝A-A/B-B的提案撤回；随后确认已停车且车钩接触才允许联结，取消远车吸附。目标约束由[train physics](../../spec/javarailways/core/train-physics.md)拥有，具体混合安装朝向模型尚待实现方案。extension准备协议和碰撞联结失败处理仍未批准，不由上述确认推导。CR-023中心恢复及车钩校验依赖已由坐标任务闭合，不重复实施旧研究中的几何批次。

2026-10-11追加的主编组、可选结果方向与联结/解编事件要求，由[train physics的方向选择与插件事件](../../spec/javarailways/core/train-physics.md)统一记录。用户随后澄清联结是事件驱动的决策：调用方即插件，兼容性插件可拒绝，未来SimpleATO监听并选择主编组、保存双方运营元数据供解编使用。撤回调用方参数优先于监听插件的旧提案；默认选择及多监听器/异常处理细节仍待设计。实现需共同覆盖联结和解编，不能只给旧split追加回调而保留整列统一安装朝向的假设。

用户随后确认联结和解编都采用提交前决策事件及提交后成功事件，拒绝不可被后续监听器撤销；未来SimpleATO只在成功事件更新正式运营记录。撤回解编无需前置检查的中间提案；车型和运营限制由插件检查，不依赖其与SimpleATO的监听顺序保证失败时无运营变更。具体行为由上述train physics契约统一拥有。

## CR-030后续决定：车辆行为随车厢保留（2026-10-11）

用户否决把调用者理解为列车：仅collision产生联结尝试，插件通过事件决定；主编组缺省优先碰撞前静止一方。撤回主动`Train.linkWith`及“唯一controller优先”的提案；平局与失败处置尚未获批。

模型行为由Bukkit现有`CarExtension`持有各自独立JS状态，列车适配器只捕获操作目标、分发并汇总，Core结构提交不重建车厢扩展。原列车级JS重建/取消旧延迟动作，以及只保留主JS状态两种中间建议均撤回，当前目标以[每车JS合同](../../spec/bukkit-plugin/backend/car-behavior.md)为准。SimpleATO运营元数据归属不变；先迁移车辆行为和动作生命周期，再完整切换联结事务。包声明、保存形状和任意JS状态序列化不在这次文档批次改动。

## 首批 Core 修复边界：CR-022

只处理CoreTrain构造未返回时遗留的车端、world索引及本次注册的controller；不复用会销毁调用方cars的完整destroy/failed-load清理，不新增泛化事务或extension回滚协议。重复身份、端部位置失败和初始化异常都在该边界验证；编组几何完整性、宿主world构造资源、link/split及事件接入另批处理。机制和失败合同由[train physics](../../spec/javarailways/core/train-physics.md)拥有；无持久化字段/版本或公开API变更。

## 工作区与回退

根仓和 docs-site 分别记录基线及变更。已有修改不纳入新提交；若与审查修复重叠，先协调。每批可独立验证和撤回，禁止大规模顺带格式化。

## 后续无争议修复边界

用户允许连续小提交后统一检查，仍一项问题一个明确边界。CR-023分开处理已明确的加载路径/端部校验与尚未解决的相邻车钩几何，由[train physics](../../spec/javarailways/core/train-physics.md)拥有几何合同；CR-024只使collision broad phase包含其既有exact容差，归[rail occupancy](../../spec/javarailways/core/rail-topology-and-occupancy.md)；CR-016只保持CLOSED终态并拒绝关闭后的迟到publication，归[runtime publication](../../spec/javarailways/core/runtime-publication.md)。并行实现使用互不重叠文件，所有构建串行且在稳定源码上运行；每批只暂存自己的实现/测试/契约，不混入其他在途代码。
