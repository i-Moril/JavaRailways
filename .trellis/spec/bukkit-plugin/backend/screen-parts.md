# 通用扩展部件

CURRENT：`type: decorations:display` 是 Decorations 注册的 namespaced 部件。宿主不再定义内建 `screen` 或显示专属字段。通用 `PartDefinition.extension` 保存稳定 type ID、默认 1 的 schema-version 和 JSON object config。不能从 type ID 反射加载 JVM 类。

共用 name、offset、rotation、parts、animations、inline/import 仍由宿主解析。扩展节点保留完整变换、动画和子树；具备 PartExtensionRenderer 的 provider 可在 worker 打开私有会话并绘制 immutable 文字帧，由宿主 ExtensionPartModel 挂载。未安装 provider 时允许加载，输出诊断并完整保留 opaque 配置。未知非 namespaced 类型仍拒绝；内建节点不得携带扩展 config/schema-version，扩展节点不得携带 item/interaction/platform。

JSON payload 必须可深度分离、不可变和确定性编码；拒绝非字符串 map key、非有限数字、不支持的 YAML 类型、递归/过深 alias。snapshot build/decode 不能引用调用方可变集合。

公开注册入口为 `api.model.extension.PartExtensionService`，Bukkit service registry 提供。type ID 唯一，owner 必须启用。provider 只接收 detached package/part/resource DTO，不能链接 config.model 私有实现。注册时检查已发布包并返回逐包失败；后续 prepare 调用 provider。provider 代码不在 registry lock 内执行。注册、退休改变 generation，prepare/publish 必须防止跨 generation 发布。close/owner disable 清空引用，不删除已加载树。

显示字段及 provider 规则见 [Decorations](../../decorations/backend/index.md)。外部 node schema 为 `schemas/train-model/model-common.schema.json`；display overlay 在 Decorations/schemas。当前开发格式直接修正，无旧 screen alias、legacy reader 或猜测升版。

## 通用会话挂载（CURRENT）

PartExtensionRenderContext 仅携带 owning car 的 exact source package name/hash、detached part/config/resources；不向附属插件暴露平台对象。PartExtensionFrame 是不可变文字列表（最多1024），文字使用公开 ModelPose/ModelScale。原始树及动画槽顺序不包含生成文字，缺失或仅元数据 provider 继续 group 行为。

公开PartExtensionRenderer.open返回session或null，session.draw接收深度分离的PartExtensionRenderInput(data,elapsedTicks)，宿主serial worker调用close。每live attachment最多一个queued/running任务、一个覆盖式latest request及一个completed结果，2-worker pool；退休取消并移除排队任务。已完成的同attachment结果允许单调提交，不能因已有新请求就永远丢弃慢draw。worker仅发布immutable值，在现有 Composite 完整或 root-only commit 消费；先成功构造、绑定 shared root，再从首次 commit 开始 prepare。late output 与显示回放必须复查 exact attachment/registration 和已有观众成员身份；不扫描在线玩家、不在 future callback 操作 Bukkit。后添加文字先发送 child spawn，再通过现有 trainPacketBundles 刷新 root passenger packet，使用 owner 捕获的 IDs。

closeAsync 先失效身份/清空 pending，再删除生成资源；完成结果包含正在应用及部分构造的 rollback，原始 group/children 保留。调用方观察失败但不得阻塞区域线程。provider 后安装/重启仅在模型重建后生效，无自动扫描重绑。无持久化格式变化。

动态EMPTY清空旧文字；相同数量优先setText/transform复用实体，数量变化事务构建新leaves后清理旧leaves，保留所有obsolete/partial cleanup完成。session.open一次，draw失败保留上次成功输出并限于每失败episode一条诊断，后续采样可恢复。closeAsync必须等待活动callback真正退出并关闭session，而非仅FutureTask取消。service在registry锁内登记retiring futures后再移除entry；shutdown包括这些已脱离registry的退休，待实际close完成才异步关闭pool，禁止丢失idle close任务。

基础train/car Json由WorldManager在simulation owner从同一TrainFrameSnapshot捕获，跟随PendingCarFrame经过既有exact stream/revision gate交给CarExtension，再递交Composite。worker不查live Core状态；接受新train stream或首次给已有动态模型确定source时重建，防止旧session结果跨split/join。每10source ticks刷新；stationary mailbox原有抑制不改，通过现有worldtick只给active extension cars补时钟，排除同tick已发车厢，unload/stop清除exact world bucket，不加scheduler或玩家扫描。原树与持久化格式不变。


## 运营输入与上下文切换（CURRENT）

WorldManager 仅将视觉发布移动到 dispatcher/postTick 之后，仍处同一 simulation owner 边界；领域 observation/dispatcher 顺序不变。每10tick bucket 或 SimpleATO display revision 改变时新鲜捕获并投影为 unit/operational 和 destination/runType/currentStationName/nextStationName 字符串别名。固定单元用 actual carIds 映射；车厢索引不代替单元身份。author values 保持原样；仅当 destination key 缺失时，以领域 actual currentRun.terminalStationId 投影默认目的地，不能取完整运营车次／下一运行线的最后一站。stops为单份完整已知有序序列，保留history/occurrence/PASS；stationIndex和phase由SimpleATO独立进度事实给出，不由completed或stationId反推，tick/cycle 用精确字符串。inactive 无运营对象；未解析后续不能宣称下一站。

每个位置 envelope 携带最近一次同 publication stream 的不可变采样引用，避免 latest-only 区域任务合并丢失未消费的 SET/reset。stream 不同则未知并重建，不能复用旧采样；每次新采样释放移除车厢的缓存，unload/stop 清除。静止强制刷新仅对 active extension cars。

TrainPartRenderSnapshot.contextKey 由 unit+operational 事实构成，不含速度/时钟。经过 exact stream/revision gate 后，语义键变化先调用 Composite.invalidateExtensionContext：清空旧生成文字、使 attachment 私有 generation 过时、拒绝旧 queued/completed 输出、重置本次10tick throttle。保留 session 和 elapsed clock origin。普通同上下文失败保留成功画面，新上下文失败保持空白。所有旧/部分清理仍纳入退休完成；worker/输入预算失败不能影响领域 simulation 或悄悄截断停站。


## 生成内容观众策略（已实现，整体验证待统一执行）

session.audiencePolicy 在 worker open 后一次发布；PartExtensionAudiencePolicy 只接 viewer UUID，返回 null（普通车身观众）、0（关闭）或有限非负方块距离。callback 为便宜/thread-safe/value-only，可与 draw/close 并发；不得读取 live Bukkit/Core。Decorations 根据 interior 注入设置，宿主不知道 screen 专属 config。

ExtensionPartModel 保留候选观众与可见子集，entity owner 捕获 eye/world，model owner 捕获所有父级、旋转、动画后的 part origin；用平方距离比较，不用车厢中心。root-only 平移和独立动画均更新 screen point，只在决定可能变化时为该已有观众合并最多一个 entity task；执行重查 exact viewer token/attachment/最新 point。原始子树不受生成内容距离过滤。注册句柄 refreshAudience(UUID) 在锁外请求已有 attachment 的单个观众刷新，命令无需玩家移动。

无可见观众时不请求 draw；warm start 只 open 不执行默认 draw。最新输入仍可通过 serial session.update 更新便宜 detached 元数据，禁止在 update 执行脚本。最后一位退出使待处理 generation 失效，保留最新输入与时钟，返回时立即补最新请求。旧/增长画面的异步 replay 也在玩家 owner 重算可见性。隐藏实体可继续分配并跟随共享 root，不宣称清除了 native tick 成本。本阶段按用户要求只编译，测试和真实双后端/Folia/客户端验收待统一执行。


公开 render context 附带 nullable world UUID；session.renderRevision 是便宜/thread-safe 的非负选择 revision。registration.refreshRendering 锁外通知其 attachments；group 比较自身 revision，只在变化时使 generation 失效、退休旧生成文字、保留最新输入和 source clock 并立即补帧。commit 消费结果前再次检查 revision，不能仅依赖通知。宿主不解释 unit/category/template。旧 generation 的 running/completed frame 不可重新发布。session.update 与 draw/close 在同一串行 worker；仅 update 的隐藏请求不发布 frame。


## 模型配置包装一致性（2026-11-10）

独立 part 文档要求 config-version=1；内联 part 严格禁止重复 config-version，继承所属 car/part 文档版本。schema 以 partContent 复用内容，partDefinition/inlinePartDefinition 分别包装并严格限制未知字段。PartRegistry 两入口复用内容解析。部件 animations 为 name→definition map，值不再重复 name；internal PartAnimationConfig list 和快照 DTO 保留插入顺序，不更改动作执行列表、composition、部件安装/import顺序。模型 YAML 在构造 map 前拒绝重复 key。开发格式保持已有版本，无旧开发格式 reader；一次性迁移见 scripts/model-schema-consistency。独立普通模型及 SimpleSignals scene 动画是不同契约，不随本批改成 train part map。

## Station metadata 投影（2026-11-10）

projectOperationalPartRender 从simulation owner的StationStore捕获current/next/remaining stop metadata，缺失资料用{}，不存在current/next仍null。metadata在OperationalPartRenderProjection再次深度分离，metadata变化进入contextKey使旧结果失效；沿现有10tick fresh capture更新，无model重建/live store读取进入guest。站点资料不会改变停站occurrence、PASS过滤或下一站证明。包含所有后续站资料仍受SimpleDisplays整体输入界限约束，不静默截断。

## 线路索引与运行线metadata（2026-11-10）

simulation owner在每次fresh sample构建一份当前world全部已知线路索引ctx.data.lines，record为{id,displayName,metadata,color?}；train snapshot/sample cache与geometry coalescing一起保留该数据，不跨world或publication stream借用。JS可按actual LineId查询非当前线路（例如换乘），没有record则无条目。全部线路/站点输入仍受SimpleDisplays aggregate limits约束，不静默截断。无需给SimpleDisplays加入铁路API或让JS读取live store。

operational.currentRun为领域捕获的actual scope或null，携带serviceId/runId/lineId/metadata/terminalStationId；与同一outer timetable/cycle一起使用，变化进入现有contextKey并清旧帧。line metadata HTTP读写和scriptworker严格参数/镜像run DTO需同时同步，metadata缺失默认{}，显式非object拒绝；权威restore执行共同JSON限额校验。


## 站点序列与进度输入

operational.stops 序列来自实际单元的display.plannedStops，包含站点metadata。stationIndex为0-based nullable整数；phase为at_station/in_transit/finished/unknown。到站索引指向本站，在途指向下一未经过的物理call（含PASS），后两状态索引null。completed保留“已到达”诊断语义，不作为已驶离标志。宿主不重复发送remainingStops/completedStops/plannedStops数组，也不按基础设施线路站表重建运营序列。

nextStation在in_transit从index、at_station从index+1查找STOP/TERMINAL；未知/结束或unresolvedRuns非空不宣称下一站。重复stationId仍按不同call occurrence处理。currentStation继续只表示已证明泊位而非伪造call identity。序列、进度和索引包含在现有语义contextKey，确保到站/发车后的旧worker结果不能重放。HTTP诊断投影保留其既有三份stop lists，并非此JS专用接口，不能机械替换。


## 屏幕开门侧与逐车操纵端

SimpleATO提供当前已证明站停的operational.doorSide（operating LEFT/RIGHT/BOTH/NONE或null），宿主不改领域开门决策。WorldManager在既有simulation owner采样，复用TrainControlManager.operatingControlEndFor及Train.behaviorRelativeTail，与TrainExtension实际车门执行相同；只把逐车head/tail字符串写入car.operatingEnd，经现有sample cache传递。无worker live lookup、新timer或Core物理snapshot字段。

TrainPartRenderSnapshot的contextKey包含已捕获carOperatingEnd及operational.doorSide，端位/开门侧变化失效旧方向结果；默认未知端位为null，不猜train.reversed或车序。屏幕固定安装侧和本/对侧映射由Decorations拥有，详见其backend spec；host不读取decorations配置。

默认目的地只写入 detached `operational.values`，不修改领域 authored values：终点
metadata.name 的非空字符串或有效 `{zh,en}` 字符串映射优先，空／损坏名称回落到 canonical
stationName，仍未知则不添加 destination。语言映射只保留有效非空 zh/en，不生成翻译。
显式存在的 destination（对象、空串、null、其他类型）均阻止默认值；原有 exact-string alias
行为不变。自动默认时，标量 alias 使用 metadata.name 字符串，否则 canonical stationName，
再否则已知 zh/en；没有运行线／终点时不猜测，inactive context 清空。终点 ID 和最终值进入
contextKey，修改终点 metadata 或实际切换运行线会清除旧画面。
