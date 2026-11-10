# 通用扩展部件

CURRENT：`type: decorations:display` 是 Decorations 注册的 namespaced 部件。宿主不再定义内建 `screen` 或显示专属字段。通用 `PartDefinition.extension` 保存稳定 type ID、默认 1 的 schema-version 和 JSON object config。不能从 type ID 反射加载 JVM 类。

共用 name、offset、rotation、parts、animations、inline/import 仍由宿主解析。扩展节点以 Dummy/group 保留完整变换、动画和子树；本批没有 runtime mount factory 或屏幕实体。未安装 provider 时允许加载，输出诊断并完整保留 opaque 配置。未知非 namespaced 类型仍拒绝；内建节点不得携带扩展 config/schema-version，扩展节点不得携带 item/interaction/platform。

JSON payload 必须可深度分离、不可变和确定性编码；拒绝非字符串 map key、非有限数字、不支持的 YAML 类型、递归/过深 alias。snapshot build/decode 不能引用调用方可变集合。

公开注册入口为 `api.model.extension.PartExtensionService`，Bukkit service registry 提供。type ID 唯一，owner 必须启用。provider 只接收 detached package/part/resource DTO，不能链接 config.model 私有实现。注册时检查已发布包并返回逐包失败；后续 prepare 调用 provider。provider 代码不在 registry lock 内执行。注册、退休改变 generation，prepare/publish 必须防止跨 generation 发布。close/owner disable 清空引用，不删除已加载树。

显示字段及 provider 规则见 [Decorations](../../decorations/backend/index.md)。外部 node schema 为 `schemas/train-model/model-common.schema.json`；display overlay 在 Decorations/schemas。当前开发格式直接修正，无旧 screen alias、legacy reader 或猜测升版。
