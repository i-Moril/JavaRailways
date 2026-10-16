# Research: 从现场设备或轨道进入信号编辑

- Query: 已有设备应通过右键信号机或右键轨道进入编辑，避免先选全世界设备；检查当前能力和最小缺口，不确定新模式/快捷键。
- Scope: internal；只读公开API、renderer、Signal Wand、已有编辑器和相关规范。UI仅讨论，本轮不实现。
- Date: 2026-10-16

## Findings

### 结论

“看轨道后打开该处已有设备”的入口可主要放在SimpleSignals作者层实现，复用现有选轨API、设备定义、精确身份/revision保护和保存事务；无需新增通用编辑框架。现在并不存在这个入口：目前右键是放置/重放位置，设置里的数值编辑会列出全世界已摆放设备。

“直接点中信号模型”的精确拾取尚无公开能力，尤其不能把packet显示实体当Bukkit实体使用。普通模型没有交互部件/选取边界；若要真实模型拾取，需要额外设计明确的命中范围与设备身份映射。现有列车交互能力不等于公开普通模型支持。

### 文件与代码证据

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/model/ModelPresentationService.java:9-24`：公开接口只有按owner/world/objectId创建handle及关闭owner；无look-at、pick、entity→objectId、模型bounds查询或点击callback。
- 同目录 `ModelHandle.java:4-13`：只有submit/close，不公开渲染实体。`ModelPart.java:8,12,28`普通模型只有Group与Item，无Interaction节点/hitbox。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/DisplayVisual.kt:25-27` 明确packet visual没有server entity，affinityEntity缺省null；该接口是内部renderer边界，不能供附属插件假定拿实体。
- `.../EntityLibDisplayRenderBackend.kt:28-47,77-89` 使用WrapperEntity生成客户端ITEM_DISPLAY/BLOCK_DISPLAY、addViewer；不是world.spawn的Bukkit entity。`DisplayRenderBackendFactory.kt:9-10`同时支持native/entitylib，不能把native-only路径当跨backend解决方案。
- `.../NativeDisplayRenderBackend.kt:19-25`确实创建Bukkit ItemDisplay，但没有信号业务身份/interaction hitbox映射。普通display的视觉网格不是可直接依赖的编辑点击盒；本研究不声称native实体事件能等同点中资源几何。
- 内部列车 `.../render/model/InteractionModel.kt:49-55`另外生成真实Interaction及宽高；`SeatModel.kt:397-398`监听PlayerInteractAtEntityEvent并检查Interaction。此为列车内部能力，不在普通ModelPart/API中。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/rail/RailTargetService.java:15-25`已有findLookedAtRail(player,maxDistance,hitRadius)，捕获玩家eye ray，异步返回；`:28-41`查canonical曲线。`api/internal/BukkitRailTargetService.kt:53-66`复用空间索引取ray候选并做轨道精确查询，不要求SimpleSignals扫描所有轨道。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandler.kt:94` 当前选轨64m/radius0.75；`:139-152`只监听PlayerInteractEvent，Shift+右键设置优先，RIGHT_CLICK_BLOCK先生成ground placement。
- 同文件 `:464-478`：放置模式左键=BIND、右键=PLACE；未绑定时右键提示先绑定；删除模式左键=DELETE。`:562-572`有ground时直接place，不执行选轨；否则`:575-596`选轨并按本次视线确定方向。故直接把全部右键改成编辑会抢占现有放置/ground流程，必须在交互决策中明示优先级。
- 同文件 `:398-444`设置入口构造PlacementWandEditor后调用selectDevice。`PlacementWandEditor.kt:24-48`从backend.placedDevices取得全量列表，然后进入数值表单。
- `SignalWandBackend.kt:177-189`该列表来自当前世界四类定义，仅含有placement设备；每项已有kind/id/revision/placement。`:192-225`savePlacement检查exact已捕获设备、保持rail anchor/facing或ground类型、单调revision、复用唯一manager.applyDefinitionPatch。选择设备本身无需新事务。

### 当前“沿轨找设备”的真实边界

- `FixedBlockSignalEntranceResolver.kt:27-48,68-76`点击的是**逻辑入口**，要求同rail、同facing、距入口0.75m；再按blockId+entryDirection找到已有主信号。即使模型ground摆放或移远，仍可由逻辑入口找到它；但未绑定设备没有该入口，不能靠此找回。
- `SignalWandBackend.kt:639-662,678-685`保护信号也按逻辑入口及当前controlKind筛候选，保留精确signalId/revision，不是按模型实体命中。`:573-594`表示器按入口取全部已有表示器。
- `SignalWandBackend.kt:537-570,635-637`附属模式先找**rail摆放的主信号**（同rail/facing/弧长差<=0.75），再列出挂在该主信号上的附属设备；不是点中附属自己。ground主信号会在as? RailAttached处被排除，即使肉眼就在轨道旁。
- 当前没有按rail attachment就近筛选四类已摆放设备的通用作者函数，也没有ground设备的现场空间命中函数。placedDevices包含ground值，但只有全量列表入口。
- rail anchor与可见位置有偏移；按同轨弧长选设备是“此轨道位置的设备”，不能声称精确点中了旁边的灯头。ground没有rail anchor，不能虚构最近轨道绑定。

### 最小建议（供界面讨论，不构成已批准交互）

1. 将“选设备”和“编辑所选设备”分开：在现有PlacementWandEditor中允许从一个已经确认的PlacedSignalChoice直接进入现有表单；继续复用savePlacement和capture/current/session保护。这样入口可改变，保存合同不动。
2. 轨道上下文入口复用RailTargetService：只在点击时从当前定义筛该rail、该弧长附近的已摆放设备，或明确选择逻辑入口所属设备。两种含义应在UI上区分；不能把逻辑入口查询包装成“点中了模型”。唯一候选直接打开，多候选只列本地候选并让玩家选择；不按列表首项猜设备。可先按当前定义低频筛选，不为了单击新增每tick全世界索引。
3. 现场编辑普通设备无需把玩家视线当编辑权限方向；若按rail anchor列候选，是否同时列AB/BA应由所选交互决定，不能无意继承当前入口BIND的“视线必须向内”限制而让背面设备无法选择。
4. ground设备需要单独的命中依据：可以是明确展示的作者选取标记/局部候选，也可以新增可靠hitbox/射线命中，但当前没有可直接复用的公开模型pick API。仅按根位置距离/最近轨道是近似候选，不应称为精确右键信号模型；用户尚未批准此种替代。
5. 不擅自选择新工具模式。右键命中设备时编辑优先、仅在无候选时放置，还是显式动作区分，需在交互讨论中确定；尤其已有binding的玩家可能正在原地换模型/移动设备，不能静默改为弹编辑页。Shift+右键已是设置入口，不能当未占用快捷键。

### 相关规范

已复用本轮读过的workflow和当前任务PRD/design模型作者范围；直接规范为`.trellis/spec/simple-signals/backend/section-authoring-wands.md:76-78,100-116,144-156`（ground/逻辑绑定/实例编辑），`model-presentation.md`（显示与逻辑独立、rail/ground）及`.trellis/spec/bukkit-plugin/backend/model-presentation-api.md`（普通模型接口；列车交互不作为普通模型能力）。按研究角色隔离不读implement/check manifests。

## External References

无；本研究以当前仓库接口/调用链为依据，不依赖外部API记忆，也未远程操作。

## Caveats / Not Found

- 未运行服务器/客户端点击验证或测试；没有声称存在可直接用的模型拾取功能。
- 当前服务器选择哪个backend未在本轮查询；建议必须适用于两个backend。
- 模型视觉bounds、交互体大小、遮挡规则、最大距离、同处多设备选取和放置优先级均未有已批准完整合同；这是实现“直接右键信号机”的真实缺口，不需因此扩成泛用框架。
- 本轮仅写本研究文件，未改UI、代码、持久化或版本，也不增加旧开发格式兼容。
