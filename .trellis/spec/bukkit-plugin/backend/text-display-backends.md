# 通用文字 Display 后端

CURRENT：`DisplayVisualContent.Text(Component)` 与 `DisplayVisual.setText(Component)` 为列车渲染后端内部能力。通用 extension 挂载已通过内部 TextModel 复用此能力；公开边界仅接收 immutable 文字帧，不暴露 backend handle。包 schema、配置和持久化字段不变。EditorTextService 保持独立的原生编辑标记生命周期。

NativeDisplayRenderBackend 创建真实 Bukkit TextDisplay；EntityLibDisplayRenderBackend 创建 TEXT_DISPLAY wrapper/TextDisplayMeta。共用既有 location/transformation/viewRange/interpolation/teleportDuration，继续显式观众管理和原有挂载/亮度/销毁路径。文本保留不可变 Adventure Component 的内容、字体、颜色和样式，允许空 Component 清屏。

两个后端的文字基线相同：FIXED billboard、CENTER 对齐（packet 两侧对齐位都为 false）、Int.MAX_VALUE 行宽避免自动换行、255/full opacity（协议 byte -1）、无 shadow/seeThrough/defaultBackground、背景 ARGB0。字体大小、逻辑像素换算、文字基线补偿和屏幕平面位置由内容适配层决定，不能由 backend 猜测屏幕物理尺寸。

setText 在已销毁后无副作用；未销毁的 Item/Block visual 明确拒绝文字 setter。Native 直接更新对应 TextDisplay，Packet 修改实际 TextDisplayMeta 后走既有 queueMetadata/trainPacketBundles；不新增发送器、tick、scheduler、观众扫描或第二条移动协议。新观众仍使用 wrapper 中最新 metadata。

创建和 native mutation 沿用调用方目标 owner 约定，不能把 global simulation 或异步 worker 当 owning region。销毁继续返回原有 CompletionStage，使用现有 native dispatcher / packet remove，重复调用保留同一结果。新增能力不能改动 Item/Block 外观或清理策略。

验证：真实 Component/metadata 标志与队列快照、native 观众/变换/退休保护、非文字拒绝、Item/Block/ordinary model/构造清理回归；打包并检查 relocated TextDisplayMeta。独立 Paper 创建/更新/移动/删除两种实际 visual，可证明服务端状态与清理；真实客户端画面、网络观众及 Folia runtime 需另验，不能由代理或 descriptor 标志推断。

无玩家的隔离 Paper 探针必须显式加载并持有目标区块，不能仅凭 server started 或延后一 tick 推断新建实体有效；应检查真实 entity validity/位置和删除完成。相关实际证据见任务 text-backends-validation.md。

## 非等比文字缩放与安装旋转

TextModel 的 CompactTransform 表示 `T * R * S`：glyph 在本地平面缩放，再随屏幕/车厢旋转。映射至 Bukkit Transformation 必须将 rotation 放在leftRotation、rightRotation置identity。不能使用 `T * S * R`：当矩形X/Y缩放不等且屏幕有Y=90或倾角时，实际平面法向会随矩形宽高比变化，色块不再共面。统一缩放可交换，容易掩盖此错误。该要求属于扩展文字叶节点，不在本修正中重写普通ItemDisplay路径。

回归需经过真实TextModel→DisplayVisual，重建客户端 `T * L * S * R` 矩阵，验证倾斜屏幕/车体旋转下的glyph四角、法向和边长，并覆盖统一缩放与旧行为等价。只验证未旋转二维框或SDL逻辑坐标不充分。宿主矩阵验证仍不能代替真实客户端画面验收。

## Packet 显示挂载的发送顺序与去重

CURRENT：Bukkit 的 EntityLib display 后端负责本节；普通模型和动态屏幕共用它。客户端出现
`Received passengers for unknown entity` 时须检查载具根实体是否已生成，不能直接认定是座椅。
Minecraft26.1.2 对相同挂载列表也会先卸下再重挂，因此重复发送会增加实际客户端工作量。

正常显示仍由 CompositeModel 先显示子节点再显示根节点。仅用于 Item/Block/TextDisplay 的
`PassengerManagedDisplayEntity` 保留 EntityLib 的 riding/passenger 关系维护，关闭库内逐叶自动广播，
将 Spawn、Metadata、Destroy 和显式挂载都交给已有 `TrainPacketBundleCoordinator`。元数据保留原有
每观看者的文字翻译与编辑器描边过滤。不得新增网络发送器或将原生座椅纳入这个 display adapter。

`EntityLibDisplayVisual.refreshPassengersFor(player: Player)` 只接受已在 wrapper viewers 中、
且已记录有效客户端 chunk generation 的根实体；当前 anchor 区块未发送时也直接返回。
每个根节点保存实际子 visual 引用，过滤掉已退休、已换挂、未向该玩家显示或 generation 过期的子节点。
按玩家保存最近发布的有序实体 ID 列表；相同列表不发包。初始空列表无需发包，非空变为空则必须发送空列表。
`showTo` 在根节点 Spawn 之后刷新自己的挂载，保证无 extension 的普通模型也能正确挂载。

观众退出、隐藏、根实体重新生成和销毁清除对应去重状态。真正的区块重发必须重新生成并重发挂载；
普通跨区块移动仍不触发反复重建。每位玩家使用独立状态，室内距离隐藏的文字不能出现在另一玩家的列表中。
原始 PacketEvents wrapper 状态、实际 visual 关系和待发送数据在现有 owner 内捕获；同一帧的
Spawn→Mount→Destroy 必须由同一个 coordinator 顺序发出，不能将 Mount 延后而立即直发 Destroy。
关闭仍沿用现有 destruction 完成结果，不引入持久化字段或 schema version。

无当前观看者或失效根节点的刷新是无副作用 no-op；不是重试发送旧挂载。空 eager packet 在
EntityLib forceBundles 设置下也不能产生空 delimiter bundle。每次刷新遍历本根节点的 N 个子节点，
时间 O(N)，去重状态最坏 O(观看者数×N)；状态随观众/visual 生命周期释放。未变化刷新发送0个挂载包，
一次显式完成的动态节点增删批次只发送1个最终列表，避免逐叶广播导致 O(N²) 的 ID 传输量。

例如等待区块时反复刷新100次不发挂载；区块到达后按子→根→挂载显示；再刷新100次不重挂。
90个文字节点替换为146个，须先生成所有新节点，再发布最终可见列表；隐藏最后一组文字必须清空旧列表。
禁止用“关掉客户端日志”替代修复，也不能只 guard 手动刷新而留下 EntityLib 的自动广播出口。

必需回归：真实生产 wrapper/backend 加最小客户端实体表，覆盖未生成根、稳定刷新计数、动态增删/EMPTY、
隐藏观众、可见/隐藏/重显、换挂及库内 riding 关系、区块卸载/重发、同帧隐藏/销毁、普通模型，
并验证每玩家翻译不串值与描边过滤保留。旧实现必须先复现错误，修复后未知实体计数为0。
这些测试验证协议机制；现场实体丢失原因和实际客户端帧率仍须另行验收，无数据迁移或待定产品决策。


## 稳定图元与增量文字更新（CURRENT）

公开 `PartExtensionText` 的 `key: String` 必填，非空白且最长1024字符；同一 `PartExtensionFrame` 不允许重复key。它仅在当前挂载的生成文字内唯一，不是实体ID或持久化身份。SimpleDisplays的静态XML使用 `id:<id>` 或 `path:<original element-index path>`，原始路径跨每次DOM克隆及移动保留；JS新建drawable必须声明显式id。不得使用当前帧扁平索引、随机值或可见顺序作为身份。

宿主在既有owner提交路径按key对照上次成功帧，复用仍存在的TextModel，仅新建缺失项并退休删除项。Component和pose未变化不产生元数据或变换更新；空Component仍是存在的图元。新增先通过ModelConstruction成功构造，再更新复用项；失败回滚复用项并清理部分新项，保持旧帧。所有退休/部分清理仍被closeAsync跟踪。准备的show回放只能显示当前map仍持有的实体；较新帧保留相同实体时不能仅因list对象变化丢弃它。

语义上下文、模板revision、attachment退休与EMPTY沿用已有失效边界。普通动画不借此扩大为全屏重建。没有第二条调度/发包路径，图元增删继续遵守同一coordinator的Spawn、Mount、Destroy顺序。每帧对照时间和附加状态为O(N)，受1024文字上限约束。

SimpleDisplays CSS `z-index` 为0..63整数，默认0，容器向后代绝对继承，子节点显式值覆盖而非相加。Decorations将其映射为 `zIndex/63 * min(width,height) * 0.01`，单机预览使用相同固定比例。不能用当前图元数量或帧顺序重新分配深度；同层重叠由模板指定层级解决。无持久化结构或schema版本改变。

必需回归：重排、增加/删除、空圆点连续闪烁时仍存在的实体身份与深度不变；只有新增/删除项创建销毁，失败保留旧帧，较新帧保留尚未回放的新增项仍能显示。真实模板须经过实际XML/JS绘制验证稳定key与层级，再与宿主实体复用测试相结合。上述本地证据不能替代客户端对闪烁、深度精度和最终可读性的验收。
