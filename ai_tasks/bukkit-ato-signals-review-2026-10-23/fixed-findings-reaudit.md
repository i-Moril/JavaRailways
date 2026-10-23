# 已修复问题真实性复核（2026-10-23）

> 后续扩展至Core/Basic的[契约成本复核](../review-fix-contract-audit-2026-10-23.md)列出S01–S04简化候选。本页保留最近22组的逐项证据。

用户要求删除 ATO-04，并检查此前修复是否也把无当前消费者的分支、合理简化或未经证明的状态当成真实错误。本轮沿真实入口、原实现、当前差异、约定和已有测试重新核对；只修改审查文档，未修改运行时/测试代码，未运行新测试或服务器，未创建任务或提交。

基线：分支 `dev/signal-system`，HEAD `a0206a9ca9698e0bc2cbd3f7dc149f02463feeb2`；审查包含此前两批及按玩家位置放车的未提交修改。既有无关 dirty 工作保留。以下符号位置对应本轮工作区；原错误通过 `git show HEAD:<path>` 对照，不把当前修复后的行为当作原错误证据。

## 结论与纠错

- ATO-04 已从问题清单删除。WebUI Reset/重算时刻通过 `authoring.lineSimulationRoute` 始终传入各站 `destinationIds`，使用连续选路分支。原先把缺省泊位的后端分支说成实际界面预览故障，证据不足。不安排修改该分支，也不宣称该分支已经通过连续性验证。
- 共复核22组既有改动：第一批9组、第二批12组、BUK-17用户决策1组；BUK-02/SS-3合计一组（共23个问题ID）。本轮未发现足以要求撤回某项代码修复的证据，但不能再把所有条目一概称为“无争议、已证实的正常用户流程故障”。
- BUK-17原起点0的数值反例错误，遗漏旧入口还尝试反向。当前固定玩家投影位置来自用户明确产品决策，独立于原反例；保留当前行为。
- BUK-15只能确认旧回调缺少原世界/会话/对象约束；未验证原生客户端换世界后是否仍能送达旧回调。降为静态回调边界风险，保留身份约束，不称已复现的跨世界误写。
- ATO-03是公共Depot取消接口的拒绝完整性错误；正常Dispatcher另有guard。ATO-05是安装合法扩展action provider时的终态合同错误；全仓未找到随仓库交付的具体生产action provider。二者均须明确API/扩展范围，不冒充当前内置功能已发生故障。
- BUK-07有正常删除后同名重建路径，但未找到一步迁移marker的界面。BUK-05/09是损坏文件/后端失败条件下的问题，不能描述成正常操作必现或现场已经观测到的故障。

没有仓库内置caller不能单独证明一个公开扩展合同错误不存在；反过来，测试能构造输入也不能单独证明普通产品流程会产生该输入。以下逐项保留这两条边界。

## 第一批9组

### BUK-11：显式ID删除回退最近列车 — 保留

入口：Cloud `train delete` → JavaRailwaysCommand → `TrainCommand.handleDelete`（当前约611行）→ 帧间释放并销毁目标。原查询把“显式ID查无”和“未提供ID”共同回退最近列车；输入拼错ID即可命中另一列车。当前只在未提供ID时找最近目标，属于命令语义的直接修复。`TrainDeletionSelectionTest`调用入口所用helper，不是完整Paper命令测试。

### BUK-10：HTTP路径重复解码 — 保留

入口：`WebUI/frontend/src/api.ts`编码线路/世界路径 → WebUI代理 → `TimetableHttpBridge`。领域`LineId`允许`+`、`/`、`%`；原`requestURI.path`先解码，之后URLDecoder再次解码，会改变合法身份或提前展开斜杠。当前rawPath先分段、每段一次解码有真实使用依据。示例应使用合法线路/世界ID；JS文件名正则另有限制，不能拿含`+`的脚本文件名证明该问题。已有测试检查生产路径helper，未运行真实HTTP往返。

### BUK-12：issues路由缺失 — 保留

入口：`BukkitPlugin`只注册Cloud命令树；`CloudCommandRegistrar.ROUTES`原先没有issues，但help和重载失败消息指向`/jr issues`，`WorldIssueCommand`实现存在。未注册literal无法进入delegate，root handler也不兜底未知参数。当前补路由连接真实诊断入口；注册表测试不等于游戏内命令验收。

### BUK-08：内联模型变换两次应用 — 保留

入口：`PackageLoader.buildTrainPackage` → `PartRegistry.load/parsePartDefinition/parsePartList` → `ModelTreeBuilder.buildPart`。原inline YAML同一offset/rotation同时存入definition与entry，builder再相加；不存在第二份独立配置来解释两次变换。import的“定义自身变换＋引用处附加变换”是合理设计，当前仍保留，只去掉inline重复来源。测试真实加载临时YAML后经过builder，覆盖inline和import。

### BUK-07：marker缓存遗漏railId — 保留，触发范围收窄

入口：`RailMap.tsx` → `api.railMap` → HTTP → `RailMapPreviewService.capture/render`。输出位置和railId依赖marker所在轨道，原hash遗漏这个依赖。

未找到单步迁移marker的玩家界面。可达场景是Wand删除marker，再在另一已有轨道同名重建；两次地图请求之间未观察中间删除，且名称、角色、弧长等字段相同，原签名便可复用旧位置。当前一行补railId与实际输出依赖一致。测试实际map service，但用store remove/add模拟重建；不是Wand端到端测试，也未证明这种情况经常发生。

### BUK-16：跨世界同名marker被重命名 — 保留

入口：A世界正常destination编辑改名 → `RailObjectWand.renameMarker`。旧逻辑遍历所有玩家的marker map，仅按字符串ID改键和名称，直接影响B世界同名目标的显示；与迟到dialog无关。当前handle保存world UUID并筛选，保持多世界归属。已有helper回归包含不同世界及待生成显示，未做客户端验收。

### BUK-19：动画外观提前取未来帧 — 保留

入口：资源动画解析 → `PartAnimationRuntime` → builder/模型动画播放 → `Animation.ActiveAnimation.updateTransform`。模型包文档定义关键帧到达时间及离散item/brightness切换；原实现把未来帧包含在已选择displayState中，50ms便显示1s的外观，同文件`displayStateAt`却按已经到达的时间筛选。不是有意设计的另一套插值。当前修复窄化为已到达帧；测试直接动画runtime，未经过游戏显示。

### ATO-03：拒绝取消破坏Depot账本 — 保留，公共API合同

入口：`DepotStore.cancelReservation/cancelStorage`。原实现先remove，再验证完整token/运行状态；错误token或者已IN_SERVICE后再次取消会返回false，却已删除有效assignment，后续正确释放失败。与其他入口先验证再写的现有合同冲突。

正常Dispatcher传自己持有的token，reservation取消另有`depotReservationPending`保护，本轮未证明它在普通时序会传错。修复只是把已有检查放在remove之前；不能说日常调度取消必然丢账。测试直接真实DepotStore边界，覆盖拒绝后有效账本仍可使用。

### ATO-05：已终态扩展动作被误判取消失败 — 保留，插件扩展合同

入口：`TimetableExtension` action provider注册 → Dispatcher接受ticket、等待下tick轮询 → `/jr timetable stop` → `cancelExtensionAction`。ticket只要求取消幂等，没有要求把SUCCEEDED/FAILED改写为CANCELLED；同步完成或两tick间完成均合法。原取消无条件要求CANCELLED，会把合法终态视作停止失败。当前先检查终态，仍要求真正pending工作取消确认。

全仓生产代码搜索只找到注册/工厂设施，没有内置具体action provider；测试提供者证明合法扩展状态被消费者错误处理，不证明用户当前安装配置会触发。测试走真实Dispatcher，但提供者为测试实现。应描述为“安装合法扩展提供者时可触发”，不能当作当前内置动作故障。

## 第二批12组

### BUK-01：跨重启保存序号倒退 — 保留

入口：`WorldManager`周期/卸载捕获 → split writer轮转备份 → 下次load选最大匹配revision。原capture使用重启归零的serverTickIndex，旧备份revision可能高于新主文件，短时间重启保存后下次便读回旧备份。当前独立递增序号从所有解码候选最高值继续，不是schema版本或旧数据兼容。已有真实文件写读测试，但手动调用序号恢复入口，未完整启动服务器模拟重启。

### BUK-02 / SS-3：取消卸载仍销毁运行时 — 保留，Bukkit事件合同

入口：Bukkit实际注册的WorldUnloadEvent监听器 → `WorldManager.unregisterRailWorld`及SimpleSignals runtime移除。旧监听不尊重最终取消，关闭coordinator等资源后Bukkit世界仍存在，且无新的load事件恢复。仓库不必自带取消器：与其他插件对可取消事件协作是宿主合同。当前MONITOR/ignoreCancelled正确表达“只处理接受的卸载”。测试经过RegisteredListener过滤，不是完整服务器事件排序测试。

### BUK-03：force reset拒绝前移除控制器 — 保留

入口：`/jr timetable reset <id> force confirm` → 命令释放controller → `forceDiscardTimetable`。原顺序在仍有Basic route resources时被正常安全guard拒绝，但controller已移除；多run循环还可先丢弃早先run，再因后者失败。当前先整批预检、discard成功后才释放控制器，在同一owner内修正次序，不改变资源安全限制。测试真实Dispatcher，覆盖无grant的早先run与有grant的后run；非完整命令transport。

### BUK-05：损坏sidecar拒写被当作成功 — 保留，损坏文件失败路径

入口：Wand右键装饰/左键清除 → `RailWandDecorateMode` → `RailRenderMetaStore.updateAll`。拒绝覆盖损坏文件是合理设计；原writer正常返回，使已修改显示及undo被当成保存成功才是问题。当前拒绝抛错、先保存后改显示，原文件拒写时不先改视觉。

范围仅是保存拒绝的处理次序。后续视觉创建失败时不提供磁盘到视觉的完整事务回滚，不能把这次修复描述成所有装饰失败都可回滚。测试走实际同步writer拒绝，未驱动玩家事件。

### BUK-09：模型部分构造失败缺少owner回收 — 保留，后端故障条件

入口：玩家进入audience → `TrainComponentExtension.sendToOwned/ensureModelBuilt` → `CarExtension.buildPreparedModel` → builder → backend实体创建。原后续节点或root创建抛错，早先节点尚未转交最终owner，外层报错不回收；CarExtension又提前发布body，bogie失败后的重试可覆盖旧body。当前构造局部owner和延后字段发布符合真实资源清理需要。

未观测实服泄漏、未测故障频率。`ModelTreeBuilderTest`真实builder注入后端失败；`ModelConstructionTest`用Any资源直接检查新helper，没有走真实CarExtension聚合。因此CarExtension聚合发布顺序仍是静态核实，不能说已做真实车体后端失败回归。

### BUK-13：无装饰物理轨道缺少可选端点 — 保留

入口：Wand UI关闭autoDecorate → 正常创建物理rails但不创建sidecar → 再次选端点。旧枚举只遍历sidecar而漏掉物理端点；单个端点查询本来从物理rail读取，故不能解释成“无装饰刻意不可编辑”。当前从物理rails枚举，继续隐藏split/assembly内部端点。测试走生产枚举helper，未做客户端选取。

### BUK-14：损坏Wand数据被默认值覆盖 — 保留，损坏输入失败路径

入口：实际交互、F切换、换item binding、退出 → `AbstractWand`读取/持久化。原decode失败声称保留PDC，只跳过第一次写入，交互结尾等随后仍写默认值。当前writable标志贯穿所有持久化入口，缺binding时也先decode；落实已有保留原数据合同，不是新增旧版本reader。测试走真实基类状态路径，不是游戏客户端交互。

### BUK-15：旧dialog缺少身份约束 — 保留保护，故障结论降为边界风险

实际callback入口存在：Wand选目标 → 原生dialog customClick → capture/commit → apply。旧目标只有字符串ID，回调到达时读取玩家当前世界，也不验证新会话/同ID对象是否已替换。当前原world/CoreWorld/session/token/object身份校验放在读取提交输入前和领域写前，能阻止静态可构造的迟到提交。

但uses(1)/lifetime及客户端换世界后的dialog行为尚未端到端验证，不能断言正常原生客户端一定能在换世界后点击旧dialog，也没有已发生跨世界写错的证据。相比ATO-04，这里有现用入口，但触发窗口仍未证实；本轮降级描述，不以helper测试绿色冒充客户端复现，也没有足够依据撤销保护。

### BUK-18：取消下车仍释放驾驶会话 — 保留，Bukkit事件合同

入口：实际注册的SeatModel EntityDismountEvent → align/通知离座 → TrainControlManager.releaseManualControl。旧监听在确认取消前执行，其他插件合法取消时玩家仍坐着、控制却释放。当前最终观察阶段并忽略取消事件合理。与世界卸载一样，无仓库内取消器不等于无效分支。测试走RegisteredListener取消过滤，未做真实多插件客户端操作。

### SS-1：固定进路遗漏自身入口依赖 — 保留

生产者：`ProtectionRouteSelectionRuntime.update`真实接收admission/entryClearance变更并输出evaluatedEntryIds；这些变化能只改readiness而不改occupancy或道岔。消费者：`SectionOccupancyRuntime.update`将entry变化送给规则，FIXED_ROUTE的selectPlan又依赖自身root ready状态。旧dirty依赖漏自身root，仅包含前方入口/current-entry，导致已经收到更新却不重算设备。当前加入自身入口是直接数据依赖修复。新增测试用真实JS catalog；不因此声称Basic行车授权失效或列车必然碰撞。

### SS-2：局部复用与全局revision相等检查矛盾 — 保留

入口：真实topology listener → `SectionTopologyGuard.invalidate`。无关编辑按设计不安排重编译，旧publication继续使用；之后相关occupancy/admission等更新 → `SignalPackEpochRuntime.evaluate`却要求全局revision一致，抛错破坏epoch。当前仍由per-device topologyGuard拒绝相关依赖变动，只移除与局部复用矛盾的全局要求，并非撤掉全部安全门禁。

影响条件是无关编辑之后又触发相关epoch，不是编辑后必然立即失败。原报告追踪的Core physics railwayEpochProcessors调用位于PhysicsCoordinator的try中；抛出到该catch（约791行）时会直接调用RailwaySimulationHalt。WorldManager外层对已STALLED的过滤不能撤回此前内部halt。此处只保留这条具体传播链的静态结论，不推断任意宿主回调都必然全局停止，未做运行复现。

### ATO-01：已撤销空限制在换路线后误停车 — 保留

真实生产者包括`RouteAuthorityAdmissionClient.end`（grant结束后发布空限制）及`ProtectionRegionInspectionSource`；snapshot store保留空流以维护revision，SimpleATO在运动前读取这些快照。source服务也明确允许空撤销后正常移除，故不是测试杜撰的非法状态。

原adapter在检查是否为空之前比较旧route，已撤销来源不再更新时，新路线可持续被误加停车约束；不是所有换路线都必现。当前保留空快照的旧消息拒绝能力，只跳过其已无约束含义的route比较；world/epoch/异常来源丢失检查仍存在。测试直接adapter边界，生产者链本轮静态核对。

## 用户后续决定1组

### BUK-17：固定玩家投影点放车 — 保留用户决定，纠正原例

原先15.5m孤立轨道、起点0、整列长15m的示例遗漏反向候选：反向在原点就可容纳整列，不能证明创建失败。若分析旧搜索本身，两向同时漏解的静态例子是同轨道中点7.75m：各向需要平移7.25m，但4m不够、8m已越界。这仍只是旧算法反例，未在旧版本执行红色回归。

当前删除自动挪车来自用户明确要求，按玩家位置投影固定车头，仅AB/BA选朝向，两向都不足便拒绝。因此不是“继续完善旧指数搜索”，也不依赖旧起点0反例。已有测试含真实Core setLocation/commit后车头车尾断言，仍未做游戏内放车验收。

## 复核边界

本轮是此前22组改动的定向静态复核，检查原实现/生产调用者/当前改动和既有测试证明范围，不是重新审完全部模块。其余未修复问题未扩大审查范围；没有重跑历史测试，没有证明旧版红→新版绿，没有Paper/Folia或客户端验收。前两批及放车测试数字仍为此前运行记录，本轮不重新宣称这些测试实际执行。
