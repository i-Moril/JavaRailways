# JavaRailways 安装

JavaRailways 需要 Paper/Folia、Java 25，以及**独立安装的 PacketEvents 2.12.1 或更新兼容版本**。构建与静态验证基于 PacketEvents 2.12.1；更新版本仍需针对服务器版本验证。

1. 从 [PacketEvents 官方发布页](https://github.com/retrooper/packetevents/releases)下载 Spigot 版本的插件 JAR。
2. 将 PacketEvents 和 JavaRailways 的插件 JAR 放入服务器 `plugins/`，重启服务器。
3. JavaRailways 声明硬依赖 `packetevents`；缺少该插件时不会加载，版本过旧或未初始化时启用失败。

JavaRailways 不再包含 PacketEvents，其他插件内嵌的副本不能代替独立安装。EntityLib 仍由 JavaRailways 内嵌并重定位，使用独立 PacketEvents 的共享 API。

关闭或重载 JavaRailways 时，它只释放自己的模型和监听器，不关闭 PacketEvents、不撤销玩家网络注入。不要在依赖插件运行时单独卸载 PacketEvents；需要更新它时重启服务器。当前仅以 PlugManX 3.2.1 的 Paper/Folia 路径为支持目标，完整热重载兼容性仍需真实服务器验证。

Gradle 的 `:BukkitPlugin:runServer` 与 `:BukkitPlugin:runFolia` 开发启动任务会单独下载 PacketEvents 2.12.1，不将其打入 JavaRailways JAR。


## Folia 关闭行为

启用后的首个 global 回调检查实际 tick 工作线程数；只有一个线程时会警告热禁用的同步清理可能超时，检测不可用时单独记录诊断。接入下述 GentleUnload 后，单线程警告会提示使用两次请求流程；独立 disable 仍存在同步等待限制。不会修改服务器线程配置。

仅 Folia 整服关闭（server.isStopping）时跳过依赖区域 tick 的原生实体/玩家清理回调。所有原生临时实体在 spawn 初始化阶段设置不持久化，客户端虚拟实体不进入世界存档；正常热禁用仍执行实体清理并等待完成。JR 最终快照与本地资源关闭始终保留；工具 PDC 在运行时状态变更时写回，列车钥匙绑定和用户明确执行的方块修改仍正常保存。


## PlugManX 重载约定

使用 PlugManX 3.2.1 的依赖级联重载，确保其 `config.yml` 保持以下配置：

```yaml
reloadDependentsMode: REQUIRED_ONLY
```

配置修改后执行 `/plugman reloadconfig`，再执行 `/plugman reload JavaRailways`。PlugManX 会先卸载依赖 JavaRailways 的附属插件，替换主插件，然后按依赖顺序重新加载附属插件。当前 TunnelBridgeBuilder 和 JavaRailwaysDev 均声明 `depend: [JavaRailways]`；不要将这些插件列入 PlugManX 的忽略列表。配置 `ALL` 也包含硬依赖级联，`OFF` 则不满足此约定。

不要用只针对主插件的 `disable` → `enable` 或手工 `unload` → `load` 代替上述操作：PlugManX 的 Paper backend 会替换主插件类加载器，而仍加载的附属插件持有旧 API Class，重新查询服务无法修复。必须分步操作时，先卸载附属插件，再卸载/加载主插件，最后加载附属插件。

独立 PacketEvents 保持运行，不随 JavaRailways 一起重载。本轮没有修改实际服务器或 PlugManX 的配置，也没有引入自动重载其他插件的集成。Cloud 保留现状；暂不增加跨实例的关闭失败拦截。发现清理失败时，控制台会以 SEVERE 汇总失败组件，警告 PlugManX 仍可能继续加载新实例；应停止继续热重载，检查详细错误及 PID、保留快照/备份，确认遗留 WebUI 子进程退出后重启服务器。

依据：[PlugManX 配置](https://github.com/Test-Account666/PlugManX/blob/v3.2.1/plugman-core/src/main/resources/config.yml)、[依赖级联实现](https://github.com/Test-Account666/PlugManX/blob/v3.2.1/plugman-core/src/main/java/core/com/rylinaux/plugman/commands/executables/CascadingPluginCommand.java)。


## Folia 上的 PlugManX GentleUnload

Folia 与 PlugManX **3.2.1** 同时存在时，可选接入 GentleUnload。`threads: -1` 即使实际只启动一个 tick 工作线程，也不需要更改线程配置：

1. 第一次执行 `/plugman unload JavaRailways`（附属插件须先卸载），本次请求被暂缓。JavaRailways 在命令返回后的 global 回调开始准备：停止业务入口、模拟和生产者，提交区域/玩家清理，然后释放 tick 工作线程。
2. 等控制台出现 `JavaRailways unload preparation completed`。该消息只在区域清理、嵌套清理和最终快照写入全部成功后出现。
3. 再次执行原来的命令，完成正式卸载。准备期间的重复请求会被拒绝；不会自动执行第二次卸载。准备后插件已停止提供业务，不能继续使用，必须完成卸载/重新加载或正常重启服务器。

也可按前述依赖级联约定两次执行 `/plugman reload JavaRailways`。第一次 GentleUnload 返回拒绝时，PlugManX 会尝试重新加载本次卸载的附属插件；第二次 reload 再次处理依赖并替换主插件。以控制台的准备完成消息和最终级联结果为准；第一次 PlugManX 显示卸载失败是暂缓的结果，不表示已经卸载。此级联行为仍需真实服务器验收。

准备异常或超时会打印具体错误并拒绝后续卸载，不报告准备成功。此时应检查错误并正常重启服务器。不要在准备期间改用 `/plugman disable`：PlugManX 的独立 disable 绕过 GentleUnload，不属于本流程；若外部强制禁用或停服打断准备，会取消尚未执行的区域回调、报告不完整关闭，并确认正在执行的回调退出后才释放共享运行资源。

普通 Paper/Bukkit 和没有安装 PlugManX 的场景保持原关闭路径。PlugManX 不被内嵌，也不是硬依赖。适配仅针对已核对的 3.2.1；由于其没有注销 API，卸载时通过版本限定的适配移除内部注册表中属于本实例的精确回调，避免旧类加载器被保留，不修改其他插件的注册。
