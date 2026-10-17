# Research: 现场编辑、类型和诊断的最小接口接缝

- Query: 已批准实施前快速确定可复用owner及真实缺口。
- Scope: internal，只读产品源码；无远程/测试/产品修改。
- Date: 2026-10-17

## Findings

- 精确模型拾取：公开`ModelPresentationService.java:15`已有owner/world/objectId身份与definition；`ModelHandle.java:4-13`仅submit/close。`ModelPart.java:8-29`仅Group/Item，无bounds/interaction。`config/model/ModelTreeBuilder.kt:80-105`普通入口只创建Dummy/DisplayModel；列车入口`:275`才有InteractionModel，不能直接给普通模型启用。`render/backend/DisplayVisual.kt:25-27`明确packet visual无server entity。
- 最小可实现合同需显式作者选取几何（例如局部box集合）及host命中返回owner/world/objectId/距离，结合已提交ModelPose变换；或host管理Interaction并映射点击身份。现有服务生命周期可复用，缺的是几何/查询，不需扩大成编辑框架。host不知道客户端resourcepack mesh，因此仅ItemAppearance无法提供真实网格拾取；根位置近似不能冒充。若要求逐三角网格而非明确选取体积，现有API与包都缺网格输入。选取几何形状、遮挡/距离、动画是否影响几何需明确。
- 类型信息：`signalpack/SignalTypeCatalogSnapshot.kt:31-34`AvailableSignalType只有signalType/displayName；`SignalPackCatalog.kt:63-75`SignalPackSignal只有model/logic，不存在允许control kind或兼容矩阵。不应按包名/JS路径编造类型兼容性。
- 创建：`SignalWandHandler.kt:122-124`当前默认fixed-block+available.first()+首model；`SignalWandBackend.kt:146-158`capture强制有效signalType，即presentation-only/尚未选择类型也绕不开。取消默认需要调整State/PDC/capture边界，不能只改按钮文案。
- 改type：`SignalWandHandler.kt:252-258`仅改工具偏好；实际`SignalWandBackend.kt:396-412`的place同时重写type和placement。因此现场“只换类型”应增精确设备+revision的独立作者操作，保留placement/binding，不用place冒充；沿用manager事务和附属模型全部variant校验。
- 重绑：Backend`:81-84,170-174,259-281`已有设备/目标/提交；现场选中后跳过全世界设备列表，复用精确SignalRebindChoice和目标复核。实际点击轨道可复用入口resolver，不能仅按坐标/ID自动绑定。表示器已有saveIndicator入口，主信号rebind不覆盖attached。
- 诊断：`runtime/SimpleSignalsWorldRuntime.kt:393-400`提供sectionOccupancySnapshot/sectionSignalSnapshot/protectionRouteSelectionSnapshot/routeIndicatorStates；`SectionOccupancyRuntime.kt:118-127,187`已有一个volatile不可变PublishedState（occupancy/signals/selection/indicators/blockControls），适合一次捕获后投影，避免分开读不同epoch。
- `signalpack/SignalPackEpochRuntime.kt:90-118`投影JSON只是evaluate局部变量，未保留“最近实际JS输入”；project私有，不应诊断时重新运行JS/重新构造后宣称是实际历史输入。需要实际输入时，在信号epoch owner求值时捕获不可变诊断值并随结果发布；有容量/生命周期边界，避免无限轨迹日志。
- 修改SignalPackEpochRuntime、ProtectionRouteSelectionRuntime的可变状态只能信号epoch owner；工作线程可读已发布immutable/volatile快照，Bukkit玩家/eye/对话必须回player owner。当前capture保存的是definitions publication，不固定运行epoch，诊断应有明确stamp/definitionRevision和缺baseline状态。

## Caveats / Not Found

未改变任何产品文件或远端；未运行测试。详细历史拾取/红灯行为见同目录已有研究，不重复调查。兼容类型元数据与精确拾取几何是实际缺口，不能从现有字段猜造。
