# Research: Core 联结事务方案（CR-030）

- Query: 如何修复联结的方向、几何、失败原子性及异步结果？
- Scope: internal；只读源码研究，以下目标均为待确认方案，不是已实现合同。
- Date: 2026-10-11

## 后续用户决定：四端部组合均纳入（2026-10-11）

用户已明确要求支持A-A/B-B；下文“首批仅A-B/B-A并拒绝A-A/B-B”属于被取代的历史提案，不得据此实施限制。随后确认双方已停车、指定车钩已接触才允许联结，取消远距离吸附；下文对应的待确认项已闭合。正式目标见[train physics](../../../spec/javarailways/core/train-physics.md)，需要贯通车厢安装朝向、运动、换向、split及保存恢复，具体模型/API尚待设计。extension prepare与collision失败策略仍待确认。CR-023几何依赖已由独立坐标任务闭合；下文根因与批次1保留为历史，不作为当前待修项。

## Findings

2026-10-11后续需求追加：可指定主编组及联结结果方向；解编两侧可独立指定运营方向，缺省自动选择；两种操作均允许插件通过事件指定编组信息。方向概念、目标要求与待审的默认/事件方案统一见[train physics](../../../spec/javarailways/core/train-physics.md)。下文旧方案未覆盖这些新增要求，不能据此冻结完整接口。

用户进一步澄清：调用方即插件，联结应通过决策事件允许插件依据车钩兼容性等判断是否允许；未来SimpleATO监听事件，依据时刻表等选择主编组并保存双方运营元数据供解编。旧的“调用方显式参数优先于监听插件”方案撤回。下文直接参数驱动的接口建议也须按此重审，不把事件降为联结完成后的被动通知。

### 当前实现与证据

用户已进一步确认联结/解编均分为提交前决策与提交后成功两类事件，拒绝不可被后续监听器清除；解编前也允许插件检查固定单元等限制，正式运营元数据更新在成功事件执行。直接解编无需前置事件的中间建议撤回。最新目标由上述train physics契约拥有，下文仍保留早期研究，不作为当前冻结接口。

下述源码均相对仓库根目录；行号对应本次研究时源码。

- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt:786-806`：`linkWith`仅同步拒绝不同world，排入after-commit队列后丢弃Future；先调用extension、再直接拼cars并从`cars.first()`重解后车，最后移除另一列。没有exact physics等待、接触检查、prepare阶段或新frame发布；失败可能留下已改编组/几何。
- 同文件`:187-199,434-487,612-618`：公开head/tail bogie按整列`isReversed`直接选首尾A/B；restore给所有car传同一个reversed；reverse保持cars稳定顺序。这不是任意车厢朝向的编组模型。
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreCar.kt:133-195,361-412,477-499`：car自身确有reversed和方向感知求解，但train端点、加载及split仍假定整列一致。仅把另一列cars反序，不足以支持A-A/B-B联结。
- 同文件`:361-402`：联结调用的`updateByPrevCar`是会写active状态的几何求解；不能直接当无副作用prepare调用。车钩误差按车体中心/方向、车长及两端connector length求3D距离，不等于两个TrainEnd的轨道弧长差。
- `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt:551-580`：LINK按controller选择survivor并排结构任务，随后另排post-tick任务无条件解除两列freeze；未观察联结成功/失败。`Core/.../api/event/CollisionEvent.kt:7-29`默认仍是DERAIL；本方案不新增自动联结listener。
- `Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt:129-162`：结构任务异常封在FutureTask里；after-commit先于post-tick drain，但名称不表示独立physics epoch已完成。不能靠排队顺序假定成功。
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/train/TrainExtension.kt:5-19`：split已有prepare-like约束，link只是可变回调，无通用外部副作用回滚。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/TrainExtension.kt:47-61,387-389`：当前唯一生产TrainExtension的link是no-op；initialize主要写自身host/包引用。行为runtime与延迟操作是另一生命周期，不能声称被Core world map删除自动取消。
- `Core/src/test/java/work/art1st/javarailways/core/physics/train/CoreTrainLinkTest.kt:14-43`：10m单车分别摆于AB50/35，仍允许联结；只断言端部和publication ownership。源码中的重新摆后车允许远车吸附，但该测试没有检查联结后的实际位置，不能当作吸附几何、接触、方向或事务正确的证明。

### 目标提案：端部与运营方向分开

稳定端部暂记为A端（stable cars.first的bogieA一侧）、B端（stable cars.last的bogieB一侧）；这是提案词汇，不新增冻结的公开enum。运营head在未反向时为A、反向时为B；不能用“车头对车尾”描述稳定联结面。

建议首批只支持两种同向物理编组拼接：

- survivor B ↔ other A：稳定cars为`survivor + other`。
- survivor A ↔ other B：稳定cars为`other + survivor`。
- 两种均覆盖双方运营方向的四个组合；other运营方向不同时，prepare记录需要翻转其car/bogie的local facing，commit才应用，物理位置和模型direction不变。survivor原有cars相对顺序不变，但前接时它们的列表索引会变化；“稳定顺序”不是永不变化的全局车厢编号。
- survivor ID/name/controller/运营方向保留；前接可能改变运营head所处端点，不能误承诺“原head物理位置总是保留”。无瞬移提案下，保留的是每个已有car/bogie实际位置，外端取并列后的真实最外端。
- A-A/B-B首批明确拒绝，不能反转模型或交换A/B ID伪装成功。完整支持需要车厢相对于编组的独立安装朝向，并贯通端点、solver、restore、split、frame和保存；这属于另批模型决策。

### 目标提案：接触、静止与CR-023

建议要求两列已经停止且正确接触，不在link内部拉车/吸附：相同world中的exact注册实例、非自身、car身份无重复、未脱轨；指定两端在同一实际可连接轨道路径上、相向，并满足已有connector length对应的车钩几何。菱形交叉上的空间近邻不能当作可连接。

`TrainEnd`用bogie沿轨道加车体端offset定位，不包含车钩伸出长度；正确接触时两个车体端不应被强制重合。按现有solver同源的3D车体端/车钩几何及路径身份判断，而不是仅用rail端点距离或随意新增“几米以内”。contact容差沿用`Curve.TOLERANCE`，先用真实曲线/坡道验证可用性，不加宽掩盖缺陷。

**CR-023是实际依赖**：`CoreTrain.kt:411-487`只校验路径、车内bogie弧长与首尾offset，不证明相邻车钩；`CoreCar.kt:295-339,488-499`的head/tail锚点恢复不一致。既有台账记录合法R30m曲线restore中心偏移约10mm、车钩残差约6.15mm，大于5mm。因此完整“恢复后仍能按相同接触准则联结”验收，应先闭合中心恢复规则；不得把现有restore validator当作整个编组几何证明。只修异步/失败边界可以先做，但不称完整联结已修复。

### 目标提案：prepare → commit与结果

1. 入口返回可观察异步结果，建议复用`Future<Train>`和具体失败异常，不新建任务框架；参数显式给双方稳定端部，避免猜最近端。接口是否进入`Train`候选API另行确认，不能继续暴露CoreTrain作为新第三方合同。
2. serialized main task先await exact physics ticket，随后验证exact world实例及上述条件；验证基于同一已完成epoch。既有`splitBefore`等待/准备模式可复用，但还须核实timer不会在结构commit期间开启新worker，不能仅凭await代替结构期间准入边界。
3. prepare只计算合并cars、方向归一化、外端、body/occupancy、immutable frame和extension结果；不调用会写car的solver。无吸附方案无需重解所有car。准备所有可失败值后才修改live，失败保持两列cars/端部/controller/extensions/frame/owner/world map原样。
4. extension建议把旧可变link回调改成prepare-like协议：返回待接管survivor的专属extension实例，在同slot安装；初始化只能写返回实例自身，禁止修改两列/共享cars/平台。所有extension均准备成功后一次attach。不提供“任意插件回调异常后Core能外部回滚”的承诺。具体名称、只在一侧有slot时的合并/拒绝语义仍须设计；不默默丢other-only数据。
5. commit-only不再调用插件或几何求解：应用预计算方向/集合/端部、处理controller、转移car publication owner，再移除other索引/车端和旧mailbox；发布同一完成epoch的新revision frame。停止准入方案下controller及所有car/bogie速度必须一致为0，不能只清car却保留非零controller speed。不销毁移入cars或其模型。
6. collision LINK观察真正结果，不再无条件post-tick解冻。建议成功后仅释放survivor；失败在owner边界将仍存在的两列转默认DERAIL并可诊断原因后解除pending，不能失败后让重叠列车继续运动。main显式联结被拒绝仅返回失败、不额外脱轨。此失败策略也须批准，保持默认碰撞行为不变。

### 需要用户确认的产品选择

1. 首批是否只支持稳定A-B/B-A，并显式拒绝A-A/B-B？建议先如此；完整混合朝向不作为“顺手修bug”扩张。
2. 是否采用“已停止、车钩已接触才可联结”，取消当前远车吸附？建议是；若保留吸附，必须另定最大距离、移动哪侧及无副作用几何prepare，不能把它默认保留成正式能力。

extension prepare与collision失败处理是随方案提交审查的技术合同，不声称已经获批。

### 分批实施与验证建议

- 批次0（本次）：只提交方案与未决项；CR-030仍待修，不改runtime/API/schema。
- 批次1：闭合CR-023中心恢复一致性（单独方案/提交）；直线、曲线、坡道、换向、加载的独立几何预期，不能以扩大容差通过。
- 批次2：按确认的端部/接触规则实现完整的联结事务切换：exact epoch/结构准入、可观察Future、无副作用prepare和commit、extension调用方，以及collision LINK对成功/失败结果的消费必须一起闭合。不能先让旧的可变联结返回Future便声称失败保持原样，也不能先改联结拒绝条件却让collision继续无条件解冻。内部可分准备代码的小提交，但运行入口切换的提交必须包含结果消费与已批准的失败策略，不能作为可独立验收的半修复交付。
  - 验证真实阻塞epoch、exceptional Future与原状态保持；AB/BA、前接/后接、运营方向四组合、非对称offset/connector、跨轨/非对称道岔、拒绝远车/异支/菱形近邻。
  - 失败注入首个/后续extension，断言全量原状态；成功后下一真实epoch movement/反向/保存恢复验证，不止一次拼接。collision失败检查两列仍冻结直至安全失败处理完成，成功检查仅存survivor恢复；默认DERAIL回归保留。
- 批次3：验证联结与既有渲染换属主机制的集成，拒绝旧stream迟到帧；检查removed-owner的行为/延迟操作生命周期，超出本批的宿主问题单列。批次2已经负责转移publication owner并消费碰撞联结结果，本批不能成为前批正确性的延迟补丁；若审查发现必要的生命周期修复，运行入口切换须等待该依赖闭合。
- 源码变化按触及边界运行Core/Basic/Bukkit及直接consumer回归；公开API变更加Dokka/Java consumer；没有现场Folia或开发服验证不得宣称游戏通过。

## Related specs / External references

- `.trellis/spec/javarailways/core/train-physics.md`：稳定cars、split prepare、当前restore缺口及collision合同。
- `.trellis/spec/javarailways/core/runtime-publication.md`、`render-publication.md`：exact ticket、结构任务与physics的区别、完整frame/ownership。
- `.trellis/spec/javarailways/core/api-boundaries.md`、`extension-data.md`：候选API、slot与host生命周期。
- 本研究只用仓库源码/合同；没有外部资料、构建、远端或游戏测试。

## Caveats / Not Found

- 本文不是已实现接口说明；未改任何runtime/spec。上述支持范围、接触策略和extension合同待用户确认。
- 未证明完整混合车厢物理朝向可表达；已发现train级端点和恢复假设与之冲突。
- 当前没有可依赖的通用extension rollback/destroy协议，也没有获得开发服实际存档验证；不引入runtime兼容或猜测升版。
