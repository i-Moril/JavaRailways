# JavaRailways 安装

JavaRailways 需要 Paper/Folia、Java 25，以及**独立安装的 PacketEvents 2.12.1 或更新兼容版本**。构建与静态验证基于 PacketEvents 2.12.1；更新版本仍需针对服务器版本验证。

1. 从 [PacketEvents 官方发布页](https://github.com/retrooper/packetevents/releases)下载 Spigot 版本的插件 JAR。
2. 将 PacketEvents 和 JavaRailways 的插件 JAR 放入服务器 `plugins/`，重启服务器。
3. JavaRailways 声明硬依赖 `packetevents`；缺少该插件时不会加载，版本过旧或未初始化时启用失败。

JavaRailways 不再包含 PacketEvents，其他插件内嵌的副本不能代替独立安装。EntityLib 仍由 JavaRailways 内嵌并重定位，使用独立 PacketEvents 的共享 API。

关闭或重载 JavaRailways 时，它只释放自己的模型和监听器，不关闭 PacketEvents、不撤销玩家网络注入。不要在依赖插件运行时单独卸载 PacketEvents；需要更新它时重启服务器。当前仅以 PlugManX 3.2.1 的 Paper/Folia 路径为支持目标，完整热重载兼容性仍需真实服务器验证。

Gradle 的 `:BukkitPlugin:runServer` 与 `:BukkitPlugin:runFolia` 开发启动任务会单独下载 PacketEvents 2.12.1，不将其打入 JavaRailways JAR。


## Folia 关闭行为

启用后的首个 global 回调检查实际 tick 工作线程数；只有一个线程时会警告热禁用的同步清理可能超时，检测不可用时单独记录诊断。警告不改变线程配置或清理策略。

仅 Folia 整服关闭（server.isStopping）时跳过依赖区域 tick 的原生实体/玩家清理回调。所有原生临时实体在 spawn 初始化阶段设置不持久化，客户端虚拟实体不进入世界存档；正常热禁用仍执行实体清理并等待完成。JR 最终快照与本地资源关闭始终保留；工具 PDC 在运行时状态变更时写回，列车钥匙绑定和用户明确执行的方块修改仍正常保存。


## PlugManX 重载约定

使用 PlugManX 3.2.1 的依赖级联重载，确保其 `config.yml` 保持以下配置：

```yaml
reloadDependentsMode: REQUIRED_ONLY
```

配置修改后执行 `/plugman reloadconfig`，再执行 `/plugman reload JavaRailways`。PlugManX 会先卸载依赖 JavaRailways 的附属插件，替换主插件，然后按依赖顺序重新加载附属插件。当前 TunnelBridgeBuilder 和 JavaRailwaysDev 均声明 `depend: [JavaRailways]`；不要将这些插件列入 PlugManX 的忽略列表。配置 `ALL` 也包含硬依赖级联，`OFF` 则不满足此约定。

不要用只针对主插件的 `disable` → `enable` 或手工 `unload` → `load` 代替上述操作：PlugManX 的 Paper backend 会替换主插件类加载器，而仍加载的附属插件持有旧 API Class，重新查询服务无法修复。必须分步操作时，先卸载附属插件，再卸载/加载主插件，最后加载附属插件。

独立 PacketEvents 保持运行，不随 JavaRailways 一起重载。本轮没有修改实际服务器或 PlugManX 的配置，也没有引入自动重载其他插件的集成。Cloud 保留现状；跨实例的关闭失败保护仍是后续工作，关闭报告失败时不能据此约定认为可以安全继续重载。

依据：[PlugManX 配置](https://github.com/Test-Account666/PlugManX/blob/v3.2.1/plugman-core/src/main/resources/config.yml)、[依赖级联实现](https://github.com/Test-Account666/PlugManX/blob/v3.2.1/plugman-core/src/main/java/core/com/rylinaux/plugman/commands/executables/CascadingPluginCommand.java)。
