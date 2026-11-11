# Wand 工程结构补查

当前静态源码：BukkitPlugin/src/main/java 内46个Java、197个Kotlin文件；src/main/kotlin内3个Kotlin文件，均为scriptworker。build.gradle未以这两个目录划分独立worker source set；它们属于main。目录整理是一致性/导航改进，不是运行逻辑错误，也不能用目录名证明进程隔离。未运行构建。

RailWand.kt为6253行，类从565行起；AbstractWand276行、RailObjectWand887行、TurnoutWand297行。不是所有Wand都同等巨大。

已有有效拆分：AbstractWand<S,M>/WandState/WandModeSpec统一物品身份、PDC状态、交互与模式切换；ConfigUI/PlayerConfigSession承担配置；RailWandTopologyEditPlan承担准确拓扑基线与编辑许可；PreparedRailWorldUndo/Builder承担前后快照与sidecar准备；RailObjectDestinationEditor/Edit分离目的地编辑值与保存；TurnoutGeometryPolicy与ExplicitConnections分离部分规则/计划。

不足：ConnectMode(56行)、EditMode(67行)、GradeSmoothMode(32行)直接持有整个RailWand，操作同一WandPlayerState，并回调connectSelectedAnchors/smoothSelectedGrades/editRail等主类大方法。这是有用的事件路由拆分，但不是完整的业务职责拆分。WandModeSpec仅提供显示名/描述，不是编辑操作能力接口。

主文件仍同时拥有配置key/对话框、几何计划准备、拓扑提交编排、锚点目录/显示、分割合并、删除装饰、undo/redo及状态codec。已有跨世界回调和显示目录绑定物理端点的问题可以作为边界检查用例，但不能单凭文件长度推导这些bug的根因。

建议按变化原因拆具体类/纯函数：会话与选点、锚点目录/显示、对话编辑、连接/平滑/分割等计划准备；保留一个world编辑提交owner，复用现有permit/current-plan/sidecar/undo顺序。只有真实外部依赖或可替换能力才加小接口；不给每个mode建立工厂/策略体系，也不把所有RailWand方法复制成巨型context接口。目录移动独立批次，检查脚本和配置中的硬编码路径。

检查范围：main文件统计；build.gradle sourceSets；AbstractWand声明/状态生命周期；RailWand类字段和internal入口；Connect/Edit/GradeSmooth全文；TopologyEditPlan声明与许可；PreparedRailWorldUndo声明/准备；RailObjectDestinationEditor入口。其余工具此前全文阅读，本次不声称所有几何/undo实现再次全文核查。仅工程观察，无新增运行缺陷，未改源码。
