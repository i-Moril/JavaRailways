# 通用文字 Display 后端

CURRENT：`DisplayVisualContent.Text(Component)` 与 `DisplayVisual.setText(Component)` 为列车渲染后端内部能力。通用 extension 挂载已通过内部 TextModel 复用此能力；公开边界仅接收 immutable 文字帧，不暴露 backend handle。包 schema、配置和持久化字段不变。EditorTextService 保持独立的原生编辑标记生命周期。

NativeDisplayRenderBackend 创建真实 Bukkit TextDisplay；EntityLibDisplayRenderBackend 创建 TEXT_DISPLAY wrapper/TextDisplayMeta。共用既有 location/transformation/viewRange/interpolation/teleportDuration，继续显式观众管理和原有挂载/亮度/销毁路径。文本保留不可变 Adventure Component 的内容、字体、颜色和样式，允许空 Component 清屏。

两个后端的文字基线相同：FIXED billboard、CENTER 对齐（packet 两侧对齐位都为 false）、Int.MAX_VALUE 行宽避免自动换行、255/full opacity（协议 byte -1）、无 shadow/seeThrough/defaultBackground、背景 ARGB0。字体大小、逻辑像素换算、文字基线补偿和屏幕平面位置由内容适配层决定，不能由 backend 猜测屏幕物理尺寸。

setText 在已销毁后无副作用；未销毁的 Item/Block visual 明确拒绝文字 setter。Native 直接更新对应 TextDisplay，Packet 修改实际 TextDisplayMeta 后走既有 queueMetadata/trainPacketBundles；不新增发送器、tick、scheduler、观众扫描或第二条移动协议。新观众仍使用 wrapper 中最新 metadata。

创建和 native mutation 沿用调用方目标 owner 约定，不能把 global simulation 或异步 worker 当 owning region。销毁继续返回原有 CompletionStage，使用现有 native dispatcher / packet remove，重复调用保留同一结果。新增能力不能改动 Item/Block 外观或清理策略。

验证：真实 Component/metadata 标志与队列快照、native 观众/变换/退休保护、非文字拒绝、Item/Block/ordinary model/构造清理回归；打包并检查 relocated TextDisplayMeta。独立 Paper 创建/更新/移动/删除两种实际 visual，可证明服务端状态与清理；真实客户端画面、网络观众及 Folia runtime 需另验，不能由代理或 descriptor 标志推断。

无玩家的隔离 Paper 探针必须显式加载并持有目标区块，不能仅凭 server started 或延后一 tick 推断新建实体有效；应检查真实 entity validity/位置和删除完成。相关实际证据见任务 text-backends-validation.md。
