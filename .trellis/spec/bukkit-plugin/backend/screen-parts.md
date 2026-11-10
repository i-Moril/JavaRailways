# 通用扩展部件

CURRENT：`type: decorations:display` 是 Decorations 注册的 namespaced 部件。宿主不再定义内建 `screen` 或显示专属字段。通用 `PartDefinition.extension` 保存稳定 type ID、默认 1 的 schema-version 和 JSON object config。不能从 type ID 反射加载 JVM 类。

共用 name、offset、rotation、parts、animations、inline/import 仍由宿主解析。扩展节点保留完整变换、动画和子树；具备 PartExtensionRenderer 的 provider 可在 worker 准备 immutable 文字帧，由宿主 ExtensionPartModel 挂载。未安装 provider 时允许加载，输出诊断并完整保留 opaque 配置。未知非 namespaced 类型仍拒绝；内建节点不得携带扩展 config/schema-version，扩展节点不得携带 item/interaction/platform。

JSON payload 必须可深度分离、不可变和确定性编码；拒绝非字符串 map key、非有限数字、不支持的 YAML 类型、递归/过深 alias。snapshot build/decode 不能引用调用方可变集合。

公开注册入口为 `api.model.extension.PartExtensionService`，Bukkit service registry 提供。type ID 唯一，owner 必须启用。provider 只接收 detached package/part/resource DTO，不能链接 config.model 私有实现。注册时检查已发布包并返回逐包失败；后续 prepare 调用 provider。provider 代码不在 registry lock 内执行。注册、退休改变 generation，prepare/publish 必须防止跨 generation 发布。close/owner disable 清空引用，不删除已加载树。

显示字段及 provider 规则见 [Decorations](../../decorations/backend/index.md)。外部 node schema 为 `schemas/train-model/model-common.schema.json`；display overlay 在 Decorations/schemas。当前开发格式直接修正，无旧 screen alias、legacy reader 或猜测升版。

## 通用静态挂载（CURRENT）

PartExtensionRenderContext 仅携带 owning car 的 exact source package name/hash、detached part/config/resources；不向附属插件暴露平台对象。PartExtensionFrame 是不可变文字列表（最多1024），文字使用公开 ModelPose/ModelScale。原始树及动画槽顺序不包含生成文字，缺失或仅元数据 provider 继续 group 行为。

宿主每 live attachment 最多一个 preparation，2-worker pool；退休取消并移除排队任务。worker 仅发布 immutable 值，在现有 Composite 完整或 root-only commit 消费；先成功构造、绑定 shared root，再从首次 commit 开始 prepare。late output 与显示回放必须复查 exact attachment/registration 和已有观众成员身份；不扫描在线玩家、不在 future callback 操作 Bukkit。后添加文字先发送 child spawn，再通过现有 trainPacketBundles 刷新 root passenger packet，使用 owner 捕获的 IDs。

closeAsync 先失效身份/清空 pending，再删除生成资源；完成结果包含正在应用及部分构造的 rollback，原始 group/children 保留。调用方观察失败但不得阻塞区域线程。provider 后安装/重启仅在模型重建后生效，无自动扫描重绑。无持久化格式变化。
