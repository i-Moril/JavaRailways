# SimpleDisplays 适配归属

CURRENT：适配已迁入独立 `Decorations` 插件。JavaRailways 主插件没有 SimpleDisplays API 依赖、softdepend、连接字段或监听器。

宿主仅公开 [通用扩展部件](./screen-parts.md) 与 [原文资源快照](./package-displays.md)。Decorations 的 compile-only API、延迟类加载、服务 owner 校验和连接退休合同见 [Decorations backend](../../decorations/backend/index.md)。迁移没有增加实际 mounted renderer。
