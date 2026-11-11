# 主审专项：生命周期对称性、模型图、快照所有权与资源重载

静态复核当前工作树，无测试/构建/服务器执行。逐项记录见 jr-deep-parent-coverage.tsv。

## BUK-22 [P2] 车体模型引用环被资源加载接受，首次显示时无限递归

位置：SavedCarModelTree.kt:53-56、ModelTreeBuilder.kt:187-193,251。

触发：一个可解析的 part `ns/body` 在 parts 中 import 自己（或 A→B→A），车体 body-parts 引用该 part。PartRegistry/CarLoader/PackageLoader 只解析字段，没有引用环校验。ModelManager.prepareReload→buildResourceVersion→SavedCarModelTree.build 收集引用时用 putIfAbsent 跳过已见名称，把有环图当作成功快照；哈希序列化保存的是字符串引用，不会自行发现环。资源因而能发布。

实际显示链：RenderChunkManager.sendRenderable→CarExtension/TrainComponentExtension.sendToOwned→ensureModelBuilt→buildPreparedModel→ModelTreeBuilder.buildCarBody→buildPartList→buildPart→buildPartList(def.parts)。最后一段没有活动递归栈检查，会持续递归直至 StackOverflowError。CarExtension.buildModel 只 catch Exception，不能将该 Error 转成普通 MODEL_UNAVAILABLE Issue。影响限于错误模型包被接受后延迟到显示阶段失败，不声称整个服务器必然退出，也没有执行递归探针。

反证：普通公开模型资产编译器 ModelAssetYamlCompiler.kt:348 有显式 cyclic part reference 检查，但车体包路径没有调用它；旧 PartDefinition.resolveTree 也不是当前生产校验入口。Bukkit 专项审查者独立复核了这一差异。现有 SavedCarModelTreeTest 只检查无环递归解析及确定性 hash。

最小方向：资源准备时用活动路径检测真正的环，并与已完成节点复用分开；在发布前拒绝非法图，保留合法共享子部件。增加自身引用、间接环、共享无环子图三类回归。

## E-SNAPSHOT [P2 API/value risk] 内容寻址模型/运行资源快照未完整隔离输入

SavedCarModelTree.build 保留原 CarDefinition 与 PartDefinition 对象，内嵌 bodyParts/bogies/parts/animations 列表未复制。SavedTrainPackageSnapshot.build 仅复制外层 actions/carControls map，内嵌动作列表和控制曲线列表仍来自调用方。公共构造器/copy 同样没有归一化边界。

可构造的具体例子：用 mutableList 创建 CarDefinition.bodyParts，build 得到 hash H 后清空原 list，snapshot.car.bodyParts 随之变化而 hash 仍为 H。CarModelSnapshotStore.save 按 H 写出当前内容而不重算；load 会重算 hash 并拒绝同一个文件，因而破坏“捕获后固定、以内容寻址”的值合同。生产模型管理器公开暴露 package/definition/snapshot 对象，但本轮未找到内置生产调用方在捕获后修改这些列表，故单列公开API/值边界风险，不计入正常操作必现缺陷。与旧 Depot alias 同类但对象和writer不同。

最小方向：在快照构造入口递归冻结值，避免仅以外层 toMap/unmodifiableMap 作为不可变证明；writer 可校验 hash 与内容一致，但不能替代捕获隔离。不涉及格式升版或兼容读取。

## 工程观察与排除

- CarLoader 构造器接收并保存 partRegistry，但整个类未读取；PackageLoader 的注释说复用解析并可能覆盖扫描，实际未覆盖。该参数不能当作引用校验已执行的证据，建议删除或落实职责，不以此再计一个运行故障。
- WorldManager 卸载和注册失败路径已对主要独立清理步骤累积异常；RailWorldFactory.WorldConstructionResources 逆序尝试所有 cleanup。不能泛报一次 renderer 失败必跳过 Core 关闭。工厂持久化轨道显示注册遗留仍是既有 BUK-04。
- WorldManager 注册失败未单独 closeBehavior 看似与卸载不对称，但恢复 initialize 只恢复值，行为 runtime/context 是懒初始化，没有证明该阶段已创建可泄漏JS上下文，未立项。
- 多世界 metrics 合并看似覆盖同名phase，但生产键包含 world name，未发现冲突，不立项。
- TrainResourceReloader 在全部world owner内重新预演混合编组，拒绝关联候选类型后迭代重算；ModelManager generation拒绝过期候选。未把允许的按类型部分成功误报为事务破坏。
- TrainComponentExtension 已保留异步清理 completion，并在最终销毁汇总；重复重建不等于清理句柄立即丢失。实际native/packet资源删除完成仍需运行验证。
