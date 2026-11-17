# Core train physics 契约

## 适用场景与用途

触发：修改 consist geometry、bogie/head/tail movement、collision、derail、reverse、link/split/destroy。

用途：保证rigid consist沿同一active path解算，worker mutation经epoch发布；碰撞、方向与结构变更不把partial geometry暴露给consumer。

联结与解编是保留的正式能力（用户于2026-10-10确认）。`splitBefore`按稳定car ID请求解编；联结只由碰撞及插件决策触发，不保留主动`linkWith`入口。两者均在协调器帧间阶段准备、提交和通知，不改变默认碰撞DERAIL或授权自动联结。

当前支持稳定物理端A-A、A-B、B-A、B-B全部组合。稳定端不随运营换向更名；A-A接触时可将另一侧反序/反向安装加入，保留车厢ID、固定物理端和模型朝向，不用重命名或翻转模型伪装。混合安装、准备和提交机制见下文；离线自动化与真实游戏验证的边界由活动任务记录。

联结准入（CURRENT）：碰撞前至少一方静止，双方都在运动时拒绝；取代此前“两列均已停车”的规则。取消远距离吸附，但允许修正实际碰撞步进越过车钩接触点产生的位移，具体方法见下节；不再要求联结时双方已发布位置恰好接触且所有车厢完全不动。接触须依据同一实际可连接轨道路径上的相向车钩及三维车体端点/两端connector length判定，不要求不含车钩伸出长度的两个`TrainEnd`重合，也不能把菱形交叉处的空间近邻当作接触。静止事实在碰撞冻结前从同一已完成physics epoch捕获；等待结构处理期间的几何冻结不是停车证明。不自行引入近零阈值或可吸附距离。没有实际碰撞证据或路径不连通时拒绝，准备失败保留两列原有结构，再由碰撞处理回退默认脱轨。当前回归覆盖四端、一静一动成功、双方运动拒绝、失败原子性、静止方不动及既有5mm最终几何容差；真实游戏验证仍须单独完成。

### 碰撞后联结的位置修正（CURRENT）

沿用既有“读取上一完整tick碰撞记录 → 冻结 → 后续结构任务决策/提交”流程，不新增控制移动意图阶段或提前精确截断每次移动。车钩范围与位置修正见下方具体机制。碰撞候选包含车钩范围，并与普通车体占用查询隔离；复用已有bucket索引，不另建全世界索引。

碰撞任务持有对应不可变frame引用，复用该tick已有的`bodyTraversal`及`headTraversal`/`tailTraversal`，不新增跨tick路径历史或存档字段。有序片段保存rail ID、AB/BA方向及canonical弧长端点；合并后的swept spans不能代替实际路径。静止方作为几何锚点，与插件选择的主编组身份分开：插件选择运动方为主不意味着移动静止方。按四端连接关系准备结果排列及接近方的刚体几何，只在已有碰撞路径证据覆盖的范围内修正越界，不按当前反向道岔选择重新寻路。若现有记录不足以证明连接或完成求解，明确拒绝并按既有策略处理，不猜测分支或扩大容差。

所有可失败求解须在结构提交前完成，提交时安装预计算结果；最终frame、车端/占用索引及ownership一致后才发布成功事件。失败不得留下部分重排、反向或移动。修正不得重建车厢JS或取消已接受动作。沿用刚体三维轴距/车钩约束，不以弧长近似替代。重算仅在实际联结尝试中执行，不增加普通tick的全编组预测；成本为相关车厢及有限路径上的既有数值求解成本，不承诺O(1)。已发布移动经过事件和里程不会因结构重算自动撤销，必须核对真实consumer，明确碰撞步进与结构修正的不同语义，不宣称实现连续碰撞时间回滚。

必需回归包括非零车钩在车体未重叠时的真实collision入口、一步越过接触点后成功修正、静止锚点不变、插件指定运动方为主、四端方向、接缝/曲线及多支路道岔的路径选择、普通占用语义不变及准备失败原状态保持。已有对应coordinator/index回归；自动测试不替代真实游戏性能验收。错误做法是先提交联结/发布成功再尝试摆车，或从新tick/当前道岔重新取路径；正确做法是复用碰撞所对应frame并准备完整几何后一次提交。

### 车钩碰撞候选与接触证据（CURRENT）

Core在完整epoch的列车frame发布后捕获内部`CollisionEnvelope`，保存同一frame引用、两稳定端的真实三维车体端点/外向方向/车钩长度，以及端部外侧的有限有向轨道片段。它只含不可变值，不持有live train/rail；碰撞任务保留对应envelope引用，不在冻结后重新生成接触证据。普通车体frame与公开占用不增加车钩范围，共享索引的用途隔离由[rail occupancy](./rail-topology-and-occupancy.md)定义。

每端从该frame的TrainEnd沿捕获时选定出口展开，支持跨任意数量的短rail；未选出口或重复有向rail时停止，不扫描世界空间近邻。展开距离沿用单车刚体求解的局部支持域：`2 × 轴距 + 1m既有搜索半径 + 本端转向架到车体端偏移 + 本端车钩长度`。这只是保守候选域，不表示车钩的真实长度等于轨道弧长；极紧自交和巨长车钩绕圈不在既有求解支持范围内。移动epoch还纳入上一份publication两端的当前局部范围，不递归保留旧sweep或新增路径历史；车体中间经过范围仍由既有swept spans覆盖。

collision-only候选在claim、事件和冻结之前必须通过物理检查：真实三维车体端点距离不大于两端车钩长度之和，方向相向、纵向侧正确，并且两端之间的短桥在同一捕获路径中可证明。数值接触比较阈值保持`1e-5m`，独立于刚体几何求解的1mm残差，不以公开最终几何容差5mm提前扩大物理接触。假候选继续检查后续列车，不能先冻结再发现它不相连。

当前端部仅贴靠时，还要求本次实际向内位移之和大于0。稳定端A/B按该frame的运营方向映射到head/tail，从既有有序traversal累加弧长；朝该端移动为正，拉开为负。这里不以几何容差过滤微小运动，也不根据controller的下一步输入猜测。维护摆位或解编frame没有移动traversal，因此静止贴靠不会先冻结下一步，随后真实撞入仍走正常碰撞处理。索引只把严格正长度的车体swept区间交集视为直接碰撞；端点相等或容差内近邻也必须走上述检查。普通blocking查询仍保留闭区间及原容差。

这是无额外历史或状态的最小规则，不是完整相对运动碰撞算法：两车同速贴靠时，正长度swept区间仍可能保守重叠并误报碰撞。用户于2026-10-14明确接受该限制，认为50ms物理步下可忽略，不作为本阶段修复阻塞；不增加逐时刻相对扫掠、解编冷却或临时豁免，也不宣称数学上不存在误报。必需回归包括正反向移动解编后能拉开、零/非零车钩静止贴靠后真实撞入，以及两车各移动`1e-6m`的真实接触；不能用新增运动阈值漏掉低速碰撞。

若一方确实静止而当前端点判定不足，复用该frame的有序移动记录、车体路径及捕获短桥，只准备接近方领车的刚体候选，检查所得head落在同frame的headTraversal内，证明本步确实越过接触点。完整编组重算留在实际联结准备阶段，不对每个近邻预测整列。静止锚点同时要求碰撞前frame的标量及car/bogie速度为0、head/tail没有本步实际移动；不能将正位移后末速0误当作整步静止。两侧都移动时仍可发生body或当前车钩碰撞，但不允许联结，本批不增加连续双车接触时间求解。

共享occupancy记录捕获时的structural topology revision；只有该版本仍current才采纳connector路径证明。过期证据不进入旧rail解析或制造冻结，body overlap保留原处理。普通active选择改变不否定已经捕获的实际路径。连接桥供下方联结准备复用，不从当前反向道岔重新寻路；记录不足则不伪造授权或吸附。

每tick新增两端有限路径展开与候选索引引用，成本随局部rail片段数量增长，不随全世界rail数增长；当前接触检查只涉及两端固定数量的三维向量操作及短桥定位。记录内接触证明额外承担一次领车已有刚体求解，真实联结另承担下方全编组准备成本。没有第二份世界索引、全曲线采样、新schema字段或版本。本节为当前实现；回归包括0.01m低速、车体未重叠时成功联结、短轨接缝/R30/三维坡道、真实未选道岔支路/空间近邻排除、stale不导致epoch失败、末速0步进、新master方向与普通blocking不变。候选不再设置数量截断，必须检查完整局部集合；索引单测覆盖超过原32项后仍找到有效候选。

### 联结入口的有限路径位置修正（CURRENT）

`TrainCoupling`执行有限路径修正，上述车钩入口沿用同一事务。正常流程为捕获碰撞frame、envelope与对应拓扑版本、冻结、进入协调器帧间owner作用域，固定静止方的真实车体端点和车钩长度，先准备移动方几何，再决定主编组及结果方向，最后准备控制参数、extension和完整frame后一次安装。插件选择移动方为主不改变静止物理锚点，结果速度为0而原控制输入保留。

局部`CollisionPath`将该移动frame的`tailTraversal + bodyTraversal`与已捕获的车钩短桥接成有限路径，只合并相邻、同view且连续的片段；重复rail occurrence保序，位置对应多个不同occurrence时明确拒绝，不猜第一个。跨rail只验证既有连接，不读取active selection。逐车按本车当前物理前端及已有轨道间距作预测，复用`prepareFollowingCarOnPath`满足三维轴距与车钩约束；两端、车体及连接间隙只在捕获路径上裁剪。修正后的head必须在同frame的`headTraversal`内，不能撤回记录之前的步进。例如车钩在更早tick已接触、但检测直到本tick才触发时，若记录已不足就拒绝；不加历史或扩大5mm最终容差来吸附。

coordinator仅在对应world frame的epoch及两份train frame引用一致时捕获该world frame的`topologyRevision`，否则联结缺少证明而拒绝；默认DERAIL仍可执行。捕获envelope同时保存其有限路径所用rail确切对象、必要有向连接及car身份/朝向；结构作用域进入、决策回调后及插件准备后的第一写入前均核对实际依赖。仅world topology revision变化不拒绝联结：远处编辑允许继续，相关对象/连接/编组变化以局部联结拒绝处理；缺失同帧证明仍是核心不变量错误。普通道岔选择变化不改变structural revision，因此仍可复用记录路径。`CoreCar.prepareStationaryFrame`与`CoreBogie.prepareStationaryFrameAt`只生成自身候选value；提交先安装修正几何，再按修正后的bogie坐标应用已准备的运营反向，不能用旧反向坐标覆盖新位置。控制器或extension准备失败时原几何、归属、frame与索引不变，再由同一帧间操作消费结果并回退脱轨。成功通知晚于完整结构和[共享碰撞索引安装](./rail-topology-and-occupancy.md#联结后的共享索引更新current)。

全编组修正成本只发生于联结尝试：路径整理、定位与裁剪为有限相关片段上的顺序操作，逐车仍承担原嵌套距离求解成本，不承诺O(1)；没有每tick全编组预测。共享索引另有一次低频全世界span/bucket准备成本。无新增持久化字段、版本、路径历史或索引实现。回归通过真实coordinator推进、prepass LINK和main提交验证四端×两种主编组、非零车钩越界、静止方不动、完整frame/live向量、R30曲线与坡道，以及stale/双方运动/准备失败的拒绝及后续障碍检测；另有真实未选多支路道岔碰撞候选回归。完整自动化门禁及现场验收状态以任务记录为准。

### 联结流程的职责边界（CURRENT）

联结处理优先独立于实体对象，不为拆文件而先提取通用数值工具。协调器负责排队与结构准入；内部`TrainCoupling`负责接触端和主编组选择、插件决策、结果排列、extension/controller准备及提交顺序。`CoreTrain`继续独占cars、方向、车端、占用与frame的状态和发布，向处理器提供少量成块的准备/安装操作；不能开放一组任意状态setter或维护第二份编组状态。`CoreCar`只负责单车参数、刚体几何和自身状态，不增加联结事件、主编组选择或多列车事务代码。

普通运动与联结需要相同几何时复用同一求解实现；是否抽出领域求解器由实际复用边界决定，不分别复制联结算法，也不把二分函数搬家当作职责隔离。共享split/frame准备只能留一份，提取后仍按原结构准入与发布顺序执行，失败及插件回调语义不变，不新增每tick工作、索引或持久化字段。验证须保持既有解编及联结草稿的状态、索引、controller和事件行为，而不是断言代码位于某个类中。

车钩候选、静止锚点修正及碰撞结果消费共同构成正式联结链，不以仅通过重构测试代替端到端验收。不会为了迁移保留旧`linkWith`处理器或临时兼容入口；自动测试与游戏验证的具体完成状态由活动任务记录。

coordinator将一次LINK的实际执行与失败处理放入同一项`submitBetweenFrames`操作，内部同步调用
`TrainCoupling.linkFromCollisionOnOwner(...): Train`，不排入另一个队列再等待Future，不依赖generic postTick顺序。
普通拒绝/准备失败沿既有规则脱轨；一旦提交或共享发布失败使Core Stall，不继续按普通失败回滚或脱轨修改。
`splitBefore`也直接提交同一有限FIFO。成功前完成两侧frame、共享occupancy/world和mailbox发布；
成功监听器中新提交的rename进入下一捕获批次，不能递归drain或自等。

`CoreTrain`只委托联结入口；共享几何/frame准备和`applyPreparedStructure`继续由其独占。
类型`PreparedTrainGeometry/PreparedTrainStructure`是implementation-internal、owner-local短期候选，不是公开跨线程对象。
联结端部、主编组及插件决策留在流程owner；失败计算和扩展回调在安装前完成，成功通知在安装后执行。

### 联结/解编的方向选择与插件事件（CURRENT）

联结由插件共同决定兼容性、主编组及结果方向，不是调用方先决定、监听器只能补空值。未来SimpleATO按时刻表选择主编组，并保留双方元数据供解编使用；Core不解释运营信息。A-A/B-B允许另一侧反向安装接入。当前解编已允许插件分别指定两个结果方向，默认各自继承原方向，见下文；Core联结已接入；SimpleATO运营元数据集成仍另行设计。

方向必须分为三个概念：车厢固定物理A/B及模型朝向、车厢相对于编组稳定顺序的安装朝向、结果编组的运营方向。反向接入只改变第二项，插件通过决策事件选择第三项；不能为编码安装方向而翻转模型或重命名物理端。碰撞越界导致的实际几何修正另按上节执行。例如两列A-A接触时，以主编组为基准反向排列另一侧，并记录其安装朝向；解编后两侧可以独立选择运营方向，不改变切分的稳定car ID连接面。

#### 两阶段规则（联结与解编均已接入）

联结与解编均采用“提交前决策事件 → Core执行结构事务 → 成功事件”。联结前插件检查车钩兼容性等条件；解编前插件可以拒绝拆开固定单元、禁止的连接面或不允许的运营状态。即使操作由另一个插件发起，也不能绕过这些检查。Core只检查物理与结构合法性，不解释车型和运营规则。

提交前事件允许插件拒绝及指定结果编组信息；拒绝是本次操作内的单向状态，一旦任一插件拒绝，后续监听器不能清除。Core必须等决策派发完成再决定是否提交。主编组和结果方向影响物理结构，仍在此阶段确定；监听器只能修改决策/结果草案，不能提前修改原编组及正式运营记录。

联结成功事件、解编成功事件仅在对应结构、索引及完整物理frame提交成功后触发，每次成功操作各一次；被拒绝或准备失败不触发。成功事件是通知而非第二次决策，不允许取消或改写已完成的结构。未来SimpleATO在成功事件中才正式保存/更新双方运营元数据及编组关联，提交前只能准备所需信息。这样无需依赖SimpleATO与兼容性插件的监听优先级来避免失败操作留下运营变更。

多个插件指定互相冲突的方向时拒绝，不采用最后写入覆盖。提交前监听器异常时记录错误并继续派发，但不得提交；成功通知异常时继续通知，不回滚或将成功报告为失败。当前解编入口/事件及既有联结与extension结构钩子已标记unstable，后续可能修改签名和语义；这不表示仅用于测试，也不改变存档版本。

正常流程为：取得结构变更准入及已完成epoch → 基础物理校验 → 决策事件 → 汇总拒绝及编组/方向选择 → 准备extension/frame → 一次提交 → 成功事件。插件不能绕过物理校验；准备失败保留原结构、方向、extension、索引及frame。可变草案仅在结构owner线程使用，现有总线不会自动取消，结构owner须显式检查派发失败。

当前已接入联结决策/成功两阶段事件：仅由碰撞产生尝试，插件决定是否联结、主编组及方向，不新增主动`Train.linkWith`入口。“调用方”指插件，不指参与碰撞的列车。插件未指定主编组时选碰撞前静止的一方，取消train ID字典序推测（2026-10-12用户修订）；不另设等速时的自动选择规则。该选择只决定主编组，不替代实际接触端与安装方向决定的车厢排列。联结结果取碰撞前双方较低速度，本阶段至少一方静止，因此提交后的标量速度与车厢/转向架运动速度均为0；不能只清车体速度却保留非零controller速度。Core保留主编组controller，只在成功提交中设置其发布速度为0，不取消手柄或ATO需求，不重写插件自有命令状态；后续tick可由现有控制逻辑产生新的速度，不能将结果速度0理解为持续停车承诺。临时几何冻结不改变上述控制需求。用户已批准联结被拒绝或准备失败时回退默认脱轨。缺省方向保持主编组实际运营朝向；插件冲突拒绝。车厢JS与动作随车厢保留，见[车厢行为合同](../../bukkit-plugin/backend/car-behavior.md)；不因合并而重建JS或清空开门状态。结构API及事件标记Experimental，不承诺后续源码或二进制稳定，不表示仅用于测试。

未来SimpleATO只在准备阶段捕获双方运营元数据与稳定车厢/连接面关联，成功后才正式更新记录；准备失败不得先删除另一列数据。元数据内容、保存/恢复协议及运用分配由SimpleATO另行设计，Core不新增时刻表字段，也不假定解编必然恢复旧运用。

方向映射预计为O(N)，N为车厢数，不增加每tick事件或全世界扫描。联结后续验收须覆盖四种端部组合、指定/缺省方向、选择主编组后被拒绝及反向监听顺序、准备失败原状态保持、成功一次且完整frame可见；解编验收见下节。未来SimpleATO接入另验双方元数据保留与解编映射，不以Core结构测试替代业务验证。

## Owner、签名、单位与 lifecycle

`CoreTrain`拥有有序cars、head/tail、epoch-end placement spans、published swept spans和controller registration。`moveTrainForward(deltaS: Double)` 仅physics worker调用，`deltaS`单位米且`>=0`：

```kotlin
override fun moveTrainForward(deltaS: Double) {
    if (activeDerailState.derailed) return
    require(deltaS >= 0.0) { "deltaS must >= 0" }
    // signal scan and consist solve follow
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt`。

公开的`moveTrainForward(deltaS)`只有距离、没有时间基准，因此不得从`deltaS`猜测速度；该路径移动几何时把本次
car/bogie速度按0发布。`TrainController.moveControlledTrain`对`CoreTrain`改用implementation-only
`moveTrainForwardFromController(deltaS, speedMetersPerSecond)`，其中speed是command完成本次`tick(deltaT)`后的
当前m/s值。内部CoreTrain方法不进入`core.api`；protected controller seam是子类合同，非Core `Train`才回退公开distance调用。
基类、Basic realistic manual与SimpleATO ATO均使用该入口，覆盖`tick`的新controller不得绕开。

controller移动head bogie后，Core用其**新位置**的train-local facing tangent乘当前speed，得到首个head bogie速度；
随后沿有序编组传递tail/head端速度。car刚体角速度使用实际head→tail bogie chord `r`计算
`omega = r × (vTail - vHead) / |r|²`，单位为rad/s；car中心和下一车连接点速度再用
`v(point) = vHead + omega × offset`求得。括号是合同的一部分：Kotlin infix `cross`的优先级不能让表达式退化成
`(vHead + omega) × offset`。bogie/car velocity与geometry各自维护dirty状态和CommitBuffer槽位，避免
motion-only epoch翻转未写入的geometry槽；两类状态仍在同一个epoch barrier发布。consumer不得按位移或train标量速度重算。

零速controller epoch、collision/derail拒绝移动、运动异常、无controller的下一physics epoch及只发布换向的epoch均把
整列active motion清零，防止上一epoch非零速度残留。`deltaS == 0`但controller仍报告非零当前speed时保留**epoch结束瞬时速度**
语义：这可表达本tick末才建立的速度，不表示本tick发生了位移；collision/reverse等明确冻结分支仍强制为0。无时间基准的
公开/manual移动、`setLocation`/maintenance placement、存档placement恢复与主线程reverse/derail始终发布0。换向后的下一次
移动从反向后的active rail facing tangent重新注入速度。

运动顺序：coordinator先检查published swept collision并标记参与双方，再启动worker；未冻结的controller扫active route signal并同步发event → 解head bogie/cars/tail → 写active buffers/swept spans及两端有序traversal。`PhysicsException`转physics derail。tail从train-local forward path重算，不依赖reverse branch当前selection。

### 碰撞查询时点（CURRENT）

正常epoch先捕获controller列表，然后在启动任何worker前，以AsyncTick线程上下文按该列表查询上一完整epoch的共享碰撞索引。参与双方先加入既有pending集合，后续worker跳过其controller tick并冻结物理运动。原`moveTrainForwardFromController`不重复查询；这不是预测本tick位移，也不新增移动意图、任务或等待屏障。每个controller至多一次既有局部索引查询，不扫描全世界列车。查询从worker移到coordinator串行执行，自定义不调用move的controller也先检测既有碰撞；不承诺查询总数或墙钟成本完全不变，实际开销尚未profiling。

检测先检查发起查询列车的内部active脱轨状态。该检查避免对已脱轨发起方重复派发碰撞。该检查迁移原移动入口最前面的脱轨拒绝语义，内部查询不成为公开API。不能因为查询命中的另一列车已经脱轨就忽略它：它仍是物理障碍，正常列车撞上它仍须冻结/处理；在首个候选返回后直接忽略它还会遮蔽后续候选。事件仍在physics上下文同步派发；决定由本epoch完成后的紧接owner边界处理。冻结不执行controller tick，因此不提前消耗其命令或新增该tick的ATO里程，但不会撤销上一碰撞步进已发布的经过事件和里程。

预扫耗时计入总`wallNanos`，但不在现有`coordinatorNanos`的worker等待后计时窗口内；不能用该局部指标声称覆盖新增串行阶段。`PhysicsCoordinatorTest`以两列重叠、均有可移动controller的真实epoch断言两个tick调用均为0、双方head/tail不变；本epoch后紧接owner脱轨及每pair一次事件仍须通过。并行worker测试的列车必须分开放置，否则被正确冻结不能作为并行性能fixture。此调整只闭合观察帧与worker启动之间的竞态，不代表车钩检测或联结重算已经完成。

运动失败的脱轨原因保留异常类型与消息，并附列车ID和本次请求距离（米）；它沿原有frame→Main事件链发布，不因诊断改动而重试或改变失败分类。
`BogieException(Bogie)`在构造时复制转向架已发布位置的rail ID、facing与弧长为字符串，不持有live bogie；该位置不是失败时的active位置，不能用来声称精确故障坐标。比如请求100 m越过线路尽头时，诊断同时说明请求距离和此前已发布位置，而不是只留下异常类名。正常移动不构造这些诊断。
原因文本供人阅读，不是可解析的稳定错误协议；既有脱轨事件回归须验证上下文可见、frame先发布、事件只发一次，不能仅检查抛异常。

每次正距离移动已经取得head的`peekCurve(deltaS)`有序segments；同一列表同时供signal扫描、swept
occupancy补全和head traversal publication使用。tail endpoint仍通过既有train-local forward search求解，
并保留该search的`RailSegments`作为tail traversal；不得从反向`RailView`或移动后的active branch重建。
publication把每个segment转换为canonical AB弧长但保留local facing与列表顺序。零距离移动显式发布
空的head/tail movement traversal，下一epoch无移动也清空前一epoch证据；这两项不是持续占用状态。

每次发布还从同一已提交train-local path生成`bodyTraversal`：它是epoch末从保守车尾到车头的有序
持续位置，不是一次移动事件，也不从无序`occupiedIntervals`反推。Dispatcher只把它接到已经选定的
head-forward mission route前面，以满足Basic“冻结路线从物理车尾开始”的合同；Dispatcher不得为此再寻路。

公开`tail`、`bodyTraversal`与`tailTraversal`的长期安全语义是“保守安全车尾”，而不是强制精确几何车尾。当前实现发布精确train-end；未来低精度模式可让安全车尾落后于真实车尾并延迟释放，但禁止让它沿行驶方向领先真实车尾。与此一致，`bodyTraversal`和`occupiedIntervals`必须覆盖epoch末真实车体；允许多占，不允许漏占。

完整删除只有`Train.destroy(): CompletionStage<Void>`一个公开入口。它进入协调器帧间作用域，移除controller、head/tail、cars/extensions及world索引，再刷新共享占用和世界帧；返回结果等待所有子资源实际清理完成，包括平台实体异步删除。重复调用返回同一结果，失败通过结果传播并使协调器stall。World只保留internal unregisterTrain，不能绕过完整销毁。耦合内部retireAfterCoupling仅退休原身份，不销毁已转移车厢。TrainController和TrainEnd强引用所属列车，注销消除外部根引用；普通循环引用不阻止JVM回收。

每个runtime `CoreTrain`持有不持久化的publication stream UUID；`CoreCar`只用一个`AtomicReference<CoreTrain?>`保存当前owner，公开`Car.train`读取同一引用，render proof从该owner派生UUID，不维护两份归属或全世界索引。构造完整成功后才绑定cars；prepare失败保留旧owner；link转移后移除旧train，split新构造取得其cars。`CoreWorld.removeTrain`按exact旧train实例CAS退休，仅清除仍属自己的car；已转移或同ID替换不受影响。查询/转移均O(1)，整列绑定沿用原cars遍历。原用例覆盖构造/解编失败及成功、旧owner退休和同ID重建；不改变旧link事务、epoch或存档语义。

## 既有Core Seat的位置投影

`CoreCar.addSeat()`保留现有未接入默认玩法的Seat API，不据此决定其与Bukkit乘坐的最终归属。座位位置从所属车厢已发布的position/direction/normal计算，沿原有`-length/4`及`floorHeight + 0.5m`偏移；匿名Seat的getter必须明确读取`this@CoreCar.position`，不能读取自身的`position`而递归。返回独立向量值，不修改车厢状态；普通位置查询不新增持久化、占用表或物理计算。`CoreCarPositioningRegressionTest`以固定水平10m车和真实50ms运动检查座位坐标随车厢发布更新；此测试不代替Bukkit实体/passenger验证。

## 构造与失败清理

`CoreTrain`构造在协调器帧间owner作用域完成。先通过`CoreWorld.validateNewTrainIdentity(id, name)`检查ID/name唯一性及交叉命名空间冲突，再计算head/tail两个位置；任一位置无法放置时抛`CannotCreateTrainException`，此时尚未注册任何`TrainEnd`。`addTrain`复用同一身份校验，仍允许同一实例的重复添加（world加载链会调用）。校验查world索引，不额外遍历列车。

`CannotCreateTrainException(message: String? = null)`保留JVM无参构造和默认null消息，另可接收不可变诊断文本；不持有live列车或位置对象。正常构造的head/tail定位返回null时才生成消息，记录失败端、列车ID、输入转向架anchor的rail/facing/弧长以及有符号端点位移（米）。anchor是本次定位的起点，不是尚未生成的失败端点坐标；例如120m线路上从119m请求+2.5m定位HEAD，应说明起点119m和请求+2.5m，而非声称已得到121.5m的合法轨道位置。这里没有被捕获的原异常，不人为增加cause。消息供玩家/维护者排错，不是稳定可解析协议；split/link的其他拒绝点不因此改变。此改动不增加成功路径扫描或改变失败注册/清理边界。

随后依次创建注册head/tail、添加world索引、初始化train extensions、生成初始published frame。该阶段失败时，构造器仅撤销自己已完成的注册：移除controller，分别destroy已创建的车端，只在本次`addTrain`成功后移除world索引。清理逐项尝试，失败附加到原异常的`suppressed`后继续，最终重抛原异常。不能在身份拒绝后无条件`unregisterTrain`，否则可能删除同ID的原列车及其frame。

构造未返回时，传入cars仍由调用方拥有；此清理不调用完整`destroy()`或`discardFailedLoad()`，不销毁cars/bogies。已返回列车的加载恢复失败仍由[持久化加载边界](./persistence.md)处理。`TrainExtension`没有通用destroy/rollback协议，本边界不承诺撤销extension任意外部副作用；正常的列车销毁及结构变更也不能用此局部清理替代。

## 解编事务

### 解编事件与方向提交（CURRENT）

公开入口为`@ApiStatus.Experimental fun Train.splitBefore(carId: String, newId: String): Future<Train>`，按稳定车厢ID切分，返回的future表示结构操作结果，不表示平台实体显示完成。复用JetBrains annotations的`ApiStatus.Experimental`标识联结/解编接口和事件不保证后续源码或二进制兼容，不引入自定义opt-in协议；Java/Kotlin调用方均须遵守KDoc约定。旧主动`linkWith`入口已移除。

入口非阻塞地调用协调器`submitBetweenFrames`，由owner在最近完整ticket完成、且禁止新epoch进入的帧间阶段执行，直到成功通知完成或异常退出才释放；仅await不能替代该[临时准入边界](./runtime-publication.md#结构变更的临时物理准入current)。检查切分边界、新身份及源列车仍为world中exact实例后才派发`TrainSplitEvent(train,carId,newTrainId)`。事件内`originalCarIds`为不可变副本，`originalReversed`保存原方向；live train只能在owner线程读取。插件通过`reject(reason)`保留第一次拒绝原因，或分别调用`requestRetainedDirection(reversed)`、`requestSeparatedDirection(reversed)`。未指定时继承原方向；同值重复有效，异值冲突拒绝。派发后冻结，mutator同时检查main context，不能延后修改。总线继续隔离handler异常，结构owner显式检查派发结果；失败或拒绝使future以`IllegalStateException`异常完成且不进入extension准备。调用方不得在负责drain队列的线程等待future。

只有决定通过后，才准备两侧几何、extension和最终frame。先按原运营方向取得tail到head的占用路径，再在该有序路径上按bogie位置与车端offset定位切分端点，不能调用反向选路来向后推算车端；不在原占用路径内的bogie或端点拒绝解编。方向改变交换并反向表示head/tail，逆序原有body traversal并交换每段起止位置/方向，预计算bogie反向位置；不能从反向道岔选择重新寻路或调用摆车算法。所有可失败计算都在原状态变更前完成。成功构造分离列车后，提交区间只应用准备好的方向、位置表示和frame，不再执行扩展回调或几何求解。

未改变保留侧方向时保留既有运动状态；改变时按现有主线程换向语义，保留controller及其标量speed，但本次car/bogie速度清零，下一physics epoch由controller沿新方向生成运动。分离侧始终无controller且速度清零。换向不改变稳定car顺序、物理端身份、安装朝向或模型位置/朝向。最终两侧frame、车端索引与publication ownership提交后，才发布一次`TrainSplitSuccessEvent`；通知失败不撤销结构，不将future改为失败。事件携带切分前的稳定车厢身份和原方向，运营层可据此映射自己的元数据，Core不解释其内容。

事件只在低频结构操作执行。方向/frame准备为O(N+P)，N为相关车厢及bogie数、P为两半车身路径段数，另计既有路径展开与端点定位成本；不增加每tick扫描。`CoreTrainSplitTest`检查方向组合、同值重复、冲突顺序、单向拒绝、两阶段异常和初始化失败；`CoreTrainAsymmetricTurnoutTest`检查反向选择未设置时仍保留原路径。离线测试记录见任务，不表示平台实体或游戏验证已完成，也不替代未来运营层集成验收。

### 混合安装朝向的表示（CURRENT）

Core复用每车`CoreCar.isReversed`表示物理B端是否为当前运营前端，整列`CoreTrain.isReversed`只决定稳定cars列表的运营遍历顺序。车厢相对于稳定编组的安装朝向为`car.isReversed xor train.isReversed`，不另存一张运行时朝向表。整列换向同时翻转整列与每车的运营方向，因此安装朝向不变；模型direction及bogieA/bogieB身份仍保持。例如安装朝向为`[true,false,true]`的编组，整列未反向时每车运营反向状态相同，整列换向后变为`[false,true,false]`。

车头/尾先按整列方向选首/末车，再使用所选车自身的head/tail bogie、offset与connector；恢复校验和解编端点也按每车选择。移动、速度与车钩求解继续复用既有逐车active方向算法，不增加每tick索引或几何扫描。单项方向查询为O(1)，保存/恢复的朝向转换随原有cars遍历为O(N)。错误做法是所有车统一使用train的布尔值选bogieB；正确做法是先选运营首车，再由该车自己的方向选择物理端。

`SavedCarV1.installedReversed: Boolean = false`保存相对所属列车稳定顺序的安装朝向；`CoreTrain.save()`逐车传入上述异或值，独立`CoreCar.save()`没有编组上下文，明确写false。`SavedTrainV1.load()`先在新创建的cars上只恢复`train.reversed xor installedReversed`端部选择，不反转已保存的bogie facing，也不在校验前计算几何；随后按已保存整列方向构造，沿既有路径/三维间距校验后重建车身并提交。非法位置仍拒绝并沿现有failed-load清理，不增加静默摆车或兼容reader。字段有明确默认值，不改变runtime格式版本；缺字段表示同向安装，不根据位置猜测朝向。

`CoreTrainMixedInstallationTest`使用非对称offset `+3/-2m`、connector `.25/.75m`及首尾均反装的三车编组，覆盖两种初始方向的真实epoch运动、换向、稳定切分并独立反转分离侧、完整codec精确恢复及手写缺字段输入。混装定向用例只覆盖直线；混装曲线/坡道与四端联结仍须后续验收，不以现有同向曲线回归替代。

`Train.cars`是稳定编组顺序，reverse不重排；`operatingCars`只是`cars + isReversed`的只读派生。`splitBefore`始终在稳定目标car之前切分：原列车保留前缀，新列车取得目标及后缀。目标未知、不唯一或为首车时抛`CannotCreateTrainException`；末车之前可分出单车。新ID/name冲突由world身份校验拒绝。换向前后同一car ID对应同一连接面，不按运营head偷换保留方。

决策通过后，准备两侧几何及完整frame，按原slot顺序取得独立`TrainExtension.splitBefore`结果并构造新列车。准备或构造失败保留原cars/方向/controller/extensions/frame/owner/world与rail索引，新注册由构造器清理。构造期间新host仍使用原方向及几何，所选方向和最终frame仅在initialize成功后安装，extension不得提前将prepare host当作最终结果。

Core没有能撤销任意插件外部副作用的通用协议，因此`TrainExtension.splitBefore`是prepare-like边界：不得修改原extension或发布外部效果，必须返回尚未attach的独立实例。新实例保留同一slot identity，只在新列车构造期间initialize；该次initialize仍属于prepare，只能写返回extension自身，不得修改两列共享的cars或发布平台效果。这是extension实现者的合同，不通过猜测原extension内部状态实现伪回滚。成功后新`CoreTrain`取得转移cars的publication stream ownership，原列车以预计算结果更新两端、占用和frame，不销毁任何car。新列车没有controller，因此分离cars/bogies的运动状态在提交时清零；两列结构frame沿用切分前已完成的同一physics epoch，不能发布“新车speed为0但车体仍携带旧非零速度”的混合帧，也不能把新车留在epoch 0。新列车构造成功后的commit-only区间不再调用可扩展代码或几何求解：只应用预计算字段、清零分离侧运动、翻转`CommitBuffer`，更新rail端队列、原子publication引用和latest-wins mailbox。

车型或运营插件通过决策事件拒绝禁止的连接面，即使另一个插件发起也能检查；成功后才更新正式元数据。Core不实现车型、权限或SimpleATO业务。

## 三维刚体约束与局部距离求解

新建模拟列车的唯一放置准备、候选计数和一次安装契约见[train placement](./train-placement.md)。

CoreCar 拥有车内和车间几何求解。正常运行时 controller 先移动首节车的前转向架，CoreCar 固定这个新位置，求后转向架，再按运营顺序逐车满足车钩约束；后车纠偏不能反推或重建领车目标。显式摆位与恢复采用同一车体中心规则。

车内约束使用连接点 `location.position + location.normal * floorHeight` 的三维直线距离，等于 `bogieAOffset - bogieBOffset`；不能用轨道弧长代替。车体中心统一从运营前转向架连接点，沿后→前的连接点弦方向减去相应前端配置偏移。正反向仅选择不同端点和偏移，不改变配置距离的意义。车间约束为两个车体端点的三维距离等于两端 connector length 之和，纵向投影仅用于辨认正确侧。

### 本次解算的路径与候选

每次解算捕获一个有序 RailSegments，并以累计弧长查询其中候选位置；内层车内求解复用外层同一条前向路径，不重新读取反向道岔选择。运行时先按上一位置加 deltaS 预测；后续车的内层轴距预测复用上一 tick 的轨道间距。若必须向后纠正，范围严格限制在上一转向架所在 RailView 的当前轨道内，以直接弧长定位，不能跨 reverse turnout。

`RailLocationWithFacing.peekCurve(distance)`按所选方向把有限距离展开成有序`RailSegment`。每根rail先计算
`requestedEnd = segmentStart + remainingDistance`：当该终点仍在当前rail内（包括精确落在末端）时，只输出当前片段并结束；
只有`requestedEnd > railLength`才输出到末端的片段、以`requestedEnd - railLength`作为下一rail余量并跟随
`activeRail`。越界判定和余量必须复用同一个请求终点，不能先计算终点、再用“片段长度减原距离”反向重算余量；
后者会因浮点消去制造约`1e-15m`的假越界片段，使同一physics frame的移动路径不连续。这里不增加近零容差：
终点仍在本轨时不产生余量，真实的任意正跨界仍必须保留；没有`activeRail`时返回已证明的有限前缀。算法成本为
`O(K)`，`K`是实际展开的rail数量。回归必须覆盖正反方向非整数距离、零距离与精确末端、无后继截断，以及真实跨多轨。

私有 `solveDistance(maximumDistance, prediction, tolerance, sample, condition)` 先验证预测候选的实际残差；不满足时从附近扩张区间，初始半径为 max(0.01m, |残差|)，每次加倍至捕获路径边界。找到异号区间后最多迭代60次：割线落在区间中间80%内时采用割线，否则取中点，保持区间收缩。候选连同已计算的几何和残差保存，命中时复用结果。只有有限残差满足精度才能成功，步长很小或耗尽次数不能视为收敛。该局部根搜索依赖目标附近连续、可包围的距离条件，不是任意自交线路上的全局最近根证明。用户明确将轴距接近曲线直径的极紧曲线排除本次支持范围；不为其非单调窄根区间增加特殊扫描，找不到可验证的根则明确失败。

内层轴距与外层车钩共享 `CoreCar.GEOMETRY_SOLVE_TOLERANCE_METERS=1e-3m`（1mm）距离残差，小于最终公开几何容差 Curve.TOLERANCE=5mm。停车车体几何证明的轴距、锚点与车钩残差上界及累计包络同步使用该常量；碰撞接触比较阈值仍独立保持1e-5m。在有限线路末端，先用实际连接点距离求出最后可行尾转向架候选，不能只按名义轴距弧长裁剪，否则外层试探可能越过可解区间。

候选几何计算不修改 CommitBuffer 或 dirty 标志；成功应用后才设置 geometryPhysicsDirty，运动状态保持原有独立 dirty/publication 合同。无包围区间、不满足残差或不能到达目标时抛既有 BogieException，运行入口沿 PhysicsException→derail 边界处理，不将求解失败标为成功；这不新增整个编组运动的事务回滚保证。

### 有限路径上的几何准备（CURRENT）

`CoreCar.prepareFollowingCarOnPath(segments, desiredReversed, predecessorTailPosition, predecessorTailConnectorLength, maximumTailDistance, predictedTailDistance, predictedBogieSpacing)`在调用方捕获的有限`RailSegments`上准备一节后车的位置。三个距离参数均为米：尾转向架距路径起点的搜索上界、预测位置，以及预测轨道轴距。`desiredReversed`表示物理B端作为本次求解前端，不读取车厢当前方向来猜测安装关系；前车车体尾端坐标与该端车钩长度由调用方明确提供。

准备复用上述内外层刚体距离求解及有限末端裁剪，不读取新的道岔选择、不写车厢或转向架buffer。只在成功后计算一次最终normal并生成`PreparedCarGeometry`：固定物理A/B转向架位置、目标求解方向和车体position/direction/normal，三个向量使用既有`Vector3Snapshot`保存。结果只在同一结构/物理owner内短期使用，轨道位置仍引用live rail/view，不是可跨线程或拓扑修改长期保留的快照。调用方负责提供连通、有序、朝向一致的路径，并在使用结果期间维持对应结构边界。

正常`updateByPrevCar`的运行分支也使用同一准备内核，保留原预测、搜索范围与残差精度，成功后安装几何，再按原规则传播motion。安装方法为private，只处理运行时未改变方向的结果，不承担假想反向编组的方向提交。显式摆位的旧取路径方式仍保留；不能据此声称所有摆位都已不读道岔选择。失败沿既有`BogieException`返回且准备本身不改变geometry、motion或方向；不能先移动原车厢再以异常回滚伪装准备。

数值准备与状态写入分离，不改变搜索复杂度或容差；每节成功求解增加一份短期结果和固定数量的向量值，不在每次数值迭代生成快照。回归须验证假想反向、三维刚体约束、跨接缝且道岔选择已变化时仍仅用给定路径，以及有限路径不足时原状态保持。车钩候选生产、碰撞越界修正、反向安装提交和四端联结由上方联结事务组合使用这些准备结果。

### 计算成本、边界与验证

每次曲线坐标查询仍为 O(log P)，候选路径定位为 O(K)，P为轨道采样点数、K为本次跨越段数。轴距搜索与车钩搜索仍可能嵌套；预测命中可减少次数，最坏成本包含扩张和60次区间修正，不能宣称单车严格 O(1)。所有缓存只属于本次求解，不跨 tick 或拓扑更新保留。

直线匀速时预测通常直接满足残差；R30/6m轴距反例必须求真实弦长；S弯、坡道、normal/floorHeight、非对称偏移、零车钩、换向及有限轨道末端均须验证。新增 `CoreTrainRigidGeometryTest` 用真实 epoch 检查实际连接点间距、两端配置偏移、车钩长度/侧、精确领车目标及加载中心一致性，不能通过扩大5mm容差掩盖误差。

## 存档摆位恢复

`restorePersistedPlacement`先验证保存的位置，再恢复derived car state和车端；不重新摆车来覆盖损坏输入。它复用body publication的`placementSegments(savedTail, savedHead)`：沿tail→head的active path一次展开，不能从head反向沿当前道岔选择重算，因为两个方向的选择可以不对称。

单调cursor按operating顺序从车尾向车头扫描各车tail/head bogie，要求exact RailView身份（包含facing）、位置落在该segment且距离不倒退。累计弧长只证明路径顺序及首尾bogie到saved endpoint的沿轨offset；车内间距按上述三维连接点距离校验。随后按相同前连接点锚定规则计算各车中心，校验相邻车体端点距离与车钩前后侧；各项允许误差不超过 Curve.TOLERANCE（5mm）。内部某车整体平移而保持轴距/顺序的损坏输入必须因车钩不符而拒绝。

除既有路径展开外，只顺序访问一次segments/bogies/cars；这些校验在load执行，不增加正常tick扫描。失败沿既有failed-load清理，合法保存的bogie/车端精确位置保留；这不代表整个多列车world materialization失败可原子回滚。

摆位恢复保留既有位置字段及格式版本；混合朝向另增加上述默认false的安装字段，不保留旧弧长近似的运行时兼容分支。如需保留不符合当前刚体约束的旧开发数据，应另行提供可审计、dry-run且保留恢复点的离线迁移。错误做法是用按弧长放置的旧数据证明新刚体合法，或先移动保存位置再静默接受；正确做法是在当前模型边界校验实际几何，并明确拒绝不一致输入。

## Collision、derail、reverse矩阵

| 触发 | 当前epoch结果 | 后续结果/side effect |
| --- | --- | --- |
| swept overlap首次claim | pair冻结，发布一次`CollisionEvent` | 本epoch后紧接owner边界应用`DERAIL/LINK` |
| listener `requestLink()` | pending resolution=`LINK` | 本epoch后紧接owner执行link transaction |
| worker physics failure | active derail pending | epoch frame flip后queue main derail reason |
| external `derail(reason)` / `derailAsync(reason)` | 完整帧间操作，reason非blank、确切live train | 共享train/world/occupancy发布后Main event，仅一次 |
| controller reverse奇数request | 在帧间owner执行方向准备/安装 | 完整快照及Main事件后完成Future，下一epoch正常movement |
| 同一待执行操作捕获偶数次reverse | 帧间抵消 | 无direction mutation或额外publication |
| `destroy()` | 提交完整帧间cleanup | 共享占用/world刷新且子资源清理完成后stage完成 |

Reverse改变operating head/tail，但保持`Car.direction`的模型/渲染朝向不变。`Car.operatingDirection`是只读派生值，
表示正的`moveTrainForward`距离对应的当前物理前进方向；换向后它必须翻转，供Basic坡度计算使用。不得把这两个方向
重新合并成一个字段。exact persisted bogie/head/tail placement restore后重建derived car state。

### 帧间换向（CURRENT，第3a批）

`@MainThread fun CoreTrain.reverse(): Unit`保持同步语义，由既有`withStructuralMutation`进入协调器帧间作用域：
在等待exact ticket以前关闭新physics epoch准入，成功等待后才校验仍是world登记的同一列车并执行换向。
控制器异步请求复用该同步apply入口，不保留worker中的`reverseFromPhysics/preparePhysicsTick`路径。
注解和现有guard不改变线程分类；本批不新增停车或运营准入规则。

写入前要求当前所有列车frame属于协调器捕获的已完成epoch。第4批构造直接采用该epoch，
完整创建及摆位最终通过下述owner批量发布；运行中创建不再遗留epoch0。不能为了完成换向把world epoch倒退，
或写入后才发现无法形成一致世界帧。

准备复用解编已有的方向变换：捕获当前完整frame的head、tail、有序body路径及车身占用，
预先反向表示车端/路径、计算所有bogie的反向位置并生成新frame；随后一次安装。
不沿当前反向道岔重新找车端，不改变稳定车厢顺序、安装朝向或模型方向。
准备失败发生在原列车写入之前；保留controller及其标量speed/命令，本次car/bogie运动清零，下一正常physics tick按新方向继续控制。

安装后立即用现有构建器重建共享occupancy并发布同一已完成physics epoch的world frame，再同步通知`TrainSwitchDirectionEvent`。
因此监听器及Future成功都能观察到同一方向的train/world/occupancy，旧不可变frame仍保持原值。
不分配假physics epoch、不增加schema/revision类型、不重跑signal processor。安装后若共享发布失败，
沿用协调器Stall使后续physics不能继续，不把已写入状态当作普通无副作用拒绝；没有通用副作用回滚。

每次实际换向准备成本随该编组车厢及路径长度增长，另承担一次低频全world span/bucket重建；
多列车在同一宿主tick实际换向会各自重建，普通tick和抵消请求没有这项额外扫描。
本批选择同步一致通知，而不新增批量dirty tracker和延迟通知队列；其它维护编辑的批量发布仍留给后续批次。
应验证请求尚未执行时方向不变、偶数抵消、旧controller拒绝、真实worker下一步立即按新方向运动，以及监听器看到一致发布。
错误做法是只替换train frame、保留旧world occupancy，或先改方向再进行可能失败的车端寻路。

## 外部脱轨（CURRENT，第5批）

`derail(reason)`保持同步owner入口，用现有结构门禁等待exact ticket并排除下一epoch；
`derailAsync(reason): CompletableFuture<Unit>`将整个请求排入协调器有限FIFO。
执行时检查非空reason和world仍持有同一train；重复脱轨不再次通知。先移除controller并清除车体/转向架运动，
再复用`publishOwnerChanges(listOf(train))`刷新当前完整epoch的train/world/occupancy及mailbox，最后发Main `DerailEvent`。
Future成功不表示平台渲染已经完成。发布失败保持Core Stall，不把部分结果伪装成无副作用拒绝。
物理原因脱轨仍由原physics屏障发布及随后pending通知，不排队等待自身；两者不可混成同一调度路径。
一次真实外部脱轨承担一列车准备和低频共享索引重建，无普通tick额外扫描。真实运动回归检查请求尚未执行时旧frame不变，
事件看到同一epoch的零运动车体/转向架和共享快照，重复请求不重复通知，旧不可变frame保留。

## 帧间创建、删除与维护摆位（CURRENT，第4批）

Core拥有状态准备及发布；平台将完整创建/删除操作排队，初始时刻表摆位和Depot重置在已进入的owner阶段同步执行。
构造器可先登记列车供extension初始化，但此时不提交mailbox或世界帧；完整初始化和摆位成功后才对外发布。
阶段门禁阻止下一物理worker读取半成品，不承诺任意线程未经协调读取live world也得到事务快照。
`SavedTrainV1.load`要求同一作用域，`CoreWorld.load`为同步bootstrap提供已有结构门禁。

`setLocation(headLocation)`只暂存几何；单车`commitMaintenancePlacement()`或显式`commit()`的摆位分支委托
`CoreWorld.publishOwnerChanges(changedTrains: Collection<CoreTrain>)`。多车调用必须先捕获全部
`capturePlacementSnapshot()`，完成全部`setLocation`、实际包络及与未移动列车的重叠检查，再调用一次批量发布。
每车快照保存原不可变frame及车体/转向架几何、方向与运动值；发布前失败用`restorePlacementSnapshot`精确恢复，
不重新求解旧位置，不增加revision、不替换mailbox，不提前通知方向变化。已发布后恢复明确拒绝。

批量发布先验证确切live train和其他列车的epoch，准备全部候选frame，再安装所有候选；复用既有构建器
重建一次共享occupancy/world，最后提交变化列车的mailbox并发出其暂存方向通知。维护位移没有时间基准，
车体/转向架发布速度为0且不将瞬移路径作为移动穿越证据；不因此重建controller或清除其控制速度，下一物理步仍按控制器输入运行。只使用最近完整physics epoch，不分配假epoch，不重跑signal processor。
空集合仍刷新共享观测，供删除及低频运行时选择变化使用。

准备失败不发布；开始安装或共享发布失败令Core进入STALLED，不能再按普通创建/加载失败反向删除实体。
创建者只在未成功发布且Core仍RUNNING时清理自己拥有的候选。方向通知发生在提交之后，通知失败不代表可以回滚。
`destroy(): CompletionStage<Void>`在当前owner作用域内直接执行，否则排队。owner同步撤销注册和刷新共享观测，随后异步汇总所有子资源结果；禁止在owner线程join/get等待平台任务。

准备及安装随变化列车车厢/路径数量增长，共享索引每批低频重建一次；不增加普通tick扫描或另一套占用索引。
`BetweenFramesOperationsTest`验证阻塞worker期间创建不可提前执行、运行中创建使用当前epoch、删除后注册/车厢归属/
共享占用/mailbox无旧列车、失败多车摆位的原frame/world/速度不变，以及批量发布后的下一真实物理步进。
错误做法是每车提前commit后才查重叠，或失败恢复时重新发布旧frame来掩盖中间状态；正确做法是全部准备、校验后一次发布。

## 项目内正确示例

完整destroy按owner顺序清资源：

```kotlin
train.destroy().whenComplete { _, failure -> /* 将真实结果回送平台owner */ }
```

来源：`CoreTrain.kt`。它避免只删world map后遗留controller、TrainEnd或render extension。

## 禁止实现

- worker直接修改published getter或发Main event；
- collision同epoch检测并立即link/derail；
- tail沿独立reverse active branch求位置；
- 从合并swept span或epoch终点反推head/tail经过顺序；
- 从`deltaS`推测速度，或由Bukkit/其他consumer补算Core car/bogie速度；
- 用单位车身方向代替实际bogie chord计算角速度（会丢失杆长并破坏rad/s单位）；
- 用整epoch `sweptSpans`判断epoch末列车是否仍占用一个监测区；
- structure mutation直接改cars/map而无serialized task、prevalidation与rollback；
- 用`world.removeTrain`代替destroy；
- 将构造失败清理放在调用方取得列车之后，或让失败构造销毁调用方传入的cars；
- 让split在身份、端点、extension或新列车构造可失败阶段前修改原编组，或将extension副作用伪装成Core可回滚的事务。

## 必需测试与验证

- `CoreTrainTailBogieTest.kt`、`CoreTrainAsymmetricTurnoutTest.kt`：turnout/reverse/tail spacing。
- `CoreTrainSplitTest.kt`：正/反方向与末节解编，非法边界、身份、端点、extension prepare/initialize失败时的原状态保持，slot和publication ownership。
- `TrainSlopeMotionTest.kt`、`TrainSlopeJointMotionTest.kt`、`CoreCarPositioningRegressionTest.kt`：3D geometry。
- `TrainUniformMotionTest.kt`：真实50 ms coordinator epoch同时断言0.5 m位移、car/bogie frame的m/s速度、停车清零及换向后tangent符号。
- `PhysicsCoordinatorTest.kt`：stationary collision、pair once、next-epoch decision。
- `CoreTrainDerailEventTest.kt`、`CoreTrainPublicationBoundaryTest.kt`：publication before event/getter。
- `CoreTrainConstructionTest.kt`：直接构造/存档加载的ID、name及交叉命名空间拒绝不污染world/rail索引；head或tail无法放置时诊断含对应端点及输入anchor/请求位移，不注册车端、不接管cars；extension初始化失败清理车端和controller、保留原列车及调用方cars，并允许同身份成功重试。
- `CoreTrainPersistedPlacementTest.kt`：固定损坏车端JSON；脱节/重排/错误rail或facing、bogie连接点距离及两端offset拒绝并保持原world/rail/frame/mailbox；合法直线/曲线AB与BA、非对称道岔精确位置保留。内部车厢整体平移而保持顺序/轴距时，因相邻车钩不符而拒绝。
- 新link/split/destroy变更必须断言cars、ends、controller、world indexes和extensions全量cleanup。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.train.*' --tests '*PhysicsCoordinatorTest'
```

## 创建与清理所有权（2026-10-20）

`Car`及通用`Renderable`不公开`Destroyable`；车厢资源仅由Core内部随整列生命周期回收。`CoreTrain.create`只接管未属于其他列车的新车厢，构造失败内部回收；联结/解编的借用与移交仍使用内部constructor，失败不得销毁源编组。平台与持久化创建通过`CoreTrain.createOwned`/`TrainCreation`在Core拥有的创建作用域直接按参数构造新bogie、car和train，不接受返回任意现有对象的lambda。car只能使用本作用域尚未装配的bogie，train必须使用本作用域全部新car且不得带入已归属car；只允许构造一列。成功返回必须先publish，返回或失败后关闭作用域，禁止逃逸scope继续构造；独立SavedCar加载通过内部完成出口移交新car。初始化失败由作用域统一清理；已halt的部分publication保留诊断状态及真实占用。公开销毁stage只提供内部完成源的观察视图，调用方取消或完成自己的future不得改变真实清理终态。

移动入口`deltaS`必须finite且非负，公开location正负位移和peek距离必须finite；不能让Infinity进入闭环遍历。

碰撞prepass在本epoch冻结双方后，该epoch发布完成前即将决策排入owner队列；紧接完成ticket的帧间处理执行LINK或DERAIL，不额外等待下一物理epoch。pending冻结标记保留到owner操作结束，未知准备/发布错误仍halt。CollisionEnvelope中的内部身份引用不对外暴露为可写快照，不建立第二套world索引。

## Host-issued coupling geometry and output fences

`World.inspectCouplingApproach` is a serialized read-only host operation. It uses the same finite selected continuation, coupler envelope and rigid contact solver as actual collision preparation. The opaque proof captures exact source composition/direction, stationary pose, selected topology and solved contact head; it grants no infrastructure or signal permission. `validateCouplingApproachGeometry` remains owner-only. Physics controllers use `validatePublishedCouplingApproach`, which reads a coherent immutable publication and private host/structure identities instead of traversing writable ownership maps. A structural publication invalidates the proof even when new cars reuse the same IDs.

`World.inspectCouplingModeEntry(approachingTrainId, stationaryTrainId, maximumGapMeters)` is the
read-only rear-coupling entry check on that same owner boundary. It selects the approaching operating
head and stationary operating tail, requires both published trains to be stopped, then reuses the
selected connected bridge and rigid contact solve. A nearby disconnected rail, wrong direction or
front-of-target placement cannot pass through spatial proximity alone. It rejects a gap greater than
the caller's finite non-negative metre bound without moving/reversing either source or issuing authority.
`CouplingApproachProof.couplerGapMeters` captures the existing collision envelope's actual endpoint
separation minus both connector lengths; it is the same physical gap used by real coupler contact.
It is distinct from `distanceToContactMeters` (selected head travel) and raw leading-bogie travel,
which differ on curves. No hypothetical future consist body or route is calculated by this entry check.
`CouplingApproachTest` covers both operating directions, exact gap acceptance, both stopped sources,
disconnected rails, front/wrong-end rejection and a curved gap that differs from head travel.

`clampCouplingApproachDistance` is physics-worker-only: it computes raw leading-bogie travel to the solved contact bogie on the captured path, rejects invalid/stale/off-path proof and never returns extra movement beyond contact. Head-arc displacement on a curve is not interchangeable with raw bogie movement. Actual structural events expose Core-solved contact heads so the admission guard can verify the final geometry.

`World.completeCouplingContact(proof)` is the serialized owner counterpart for a pair already
physically touching and stopped. It checks the current proof, exact source identities, coherent
stopped frames, selected bridge and actual coupler contact before entering the same `couple`
transaction used by collision resolution. A real positive gap returns typed `NotInContact` with
no movement, event or publication; invalid geometry and normal veto return `Rejected`.
Actual completion retains ordinary TrainLink decision guards, controller/extension preparation,
occupancy publication and requested output fences, then returns the actual survivor.
It does not fabricate traversal or a CollisionEvent. `CouplingApproachTest` covers zero and nonzero
connector lengths in both operating directions, unchanged physical car/bogie poses within 1e-8m,
no recorded movement, positive-gap noncompletion, stale target and structural veto.

Prepared link/split decisions can request sticky independent `FormationMovementHold` capabilities. Core installs every result fence before publication; controllers and explicit distance movement cannot move fenced outputs. Releasing one exact live token preserves configuration quarantine. Strict `restoreFormationMovementHold` accepts only exact current ordered cars, coherent direction, nonderailed stopped frame and zero car/bogie velocities; no stale or foreign token releases another output. No capability is serialized.

Only an unquarantined formation hold permits `TrainController.prepareWhileFormationHeld` on the physics worker instead of normal `tick`. Configuration quarantine, derailment and pending collision exclude this callback. It prepares controller value state without actuation, movement, reversal or structural mutation. Core unconditionally freezes the controller speed buffer and complete car/bogie motion afterward, including when preparation throws; the independent physical fence remains authoritative. The default callback does nothing. `FormationHeldControllerPreparationTest` exercises these exclusions and a faulty callback attempting movement.

`CoreTrain.hasFormationMovementHold` exposes only read-only structural fence presence, never the release token; consumers must not substitute configuration quarantine for a formation fence.
