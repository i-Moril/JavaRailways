# JavaRailways 主插件工具

## 联挂模式

先由玩家将接近列车停在目标列车后方，两车都停车后，在接近列车的驾驶座执行
`/jr train couple <目标列车ID>`。系统检查当前选中轨道上的实际车头到目标车尾关系和车钩间距；
不会自动将列车摆到接近位置。ATO 已开启时自动低速接近，人工控制时由玩家推进；两者共用接近速度上限。
真实车钩接触后才联挂。普通联挂完成后列车保持停车，驾驶员通过原来的手动控制入口重新接管。
已有时刻表任务的列车由其联挂事件管理，完成后按实际新编组重新寻路；无可用后续路线时继续停车。

`config.yml` 可设置进入距离和接近速度：

```yaml
coupling:
  maximum-entry-gap-meters: 5.0
  maximum-approach-speed-meters-per-second: 0.5
```

间距单位是米，允许零；速度单位是米/秒，必须大于零，默认值相当于 1.8 km/h。
时刻表事件若设置更低接近速度，则使用较低值。配置缺少这两个字段时使用上述默认值。

当前边界：两车车钩长度都为零、且车体已经完全贴合时，独立车身准入会按既有闭区间资源冲突规则拒绝。
这种初始位置需要先留出实际间隔，再进入联挂模式；接口没有额外设置最小间距。

## 道岔操作 Wand

这个独立工具用于切换已经建好的道岔，不负责建造道岔。原 RailWand 的道岔模式仍负责建造。
发放和使用均需要 `javarailways.admin` 权限（默认 OP）。

1. 执行 `/jr wand turnout` 领取工具。
2. 瞄准靠近道岔的来向轨道，左键选择来向。工具根据命中位置选择该轨道较近的端点；
   请瞄准道岔所在的一端，不要瞄准另一端或各支路完全重合的接头。
3. 瞄准希望接通的出口轨道，右键切换。两端必须属于同一道岔，并且存在合法连接。
4. 潜行右键查看操作帮助和当前实际连接；潜行左键空气清空选择。

选轨沿视线最多 96 米，容许距离视线 1.5 米。选择/操作时仅短暂标示端口附近：青色表示来向，
绿色表示两侧实际选择已经相互接通，橙色表示所选方向有出口、但另一侧尚未对应接通。
这些标记不是行车信号，不代表列车已取得资源或允许驶入。

工具先检查资源锁和车体占用，再切换；被列车占用或已被其他申请锁住时会说明原因，不能强制扳动。
操作不申请行车授权，也不释放列车已有资源。成功与拒绝后显示的都是实际连接，不把鼠标目标当作成功结果。
玩家换世界、线路结构改变或工具状态重新载入后，需要重新选择来向。

这只是局部道岔工具。保护区域入口到出口的完整通路预览、进路录制、闭塞和信号机建造工具仍待后续实现。
当前验证覆盖自动化测试；尚未验证真实 Folia 服务器和客户端中的交互及粒子画面。

实现约束见[道岔工具契约](../.trellis/spec/bukkit-plugin/backend/turnout-wand.md)。

# JavaRailways 安装

JavaRailways 需要 Paper/Folia、Java 25，以及**独立安装的 PacketEvents 2.13.0 或更新兼容版本**。构建与静态验证基于 PacketEvents 2.13.0；更新版本仍需针对服务器版本验证。

1. 从 [PacketEvents 官方发布页](https://github.com/retrooper/packetevents/releases)下载 Spigot 版本的插件 JAR。
2. 将 PacketEvents 和 JavaRailways 的插件 JAR 放入服务器 `plugins/`，重启服务器。
3. JavaRailways 声明硬依赖 `packetevents`；缺少该插件时不会加载，版本过旧或未初始化时启用失败。

JavaRailways 使用 `paper-plugin.yml` 声明 Paper/Folia 插件入口；PacketEvents 是必需的 server 依赖，PlugManX 是可选依赖，均在 JavaRailways 之前启用并开放所需类访问。主插件继续使用现代 Cloud `PaperCommandManager`，不额外声明 Bukkit `commands`。

JavaRailways 不再包含 PacketEvents，其他插件内嵌的副本不能代替独立安装。EntityLib 3.3.7-SNAPSHOT（包含 Paper 26.2 的实体 ID API 适配）仍由 JavaRailways 内嵌并重定位，使用独立 PacketEvents 的共享 API。

关闭或重载 JavaRailways 时，它只释放自己的模型和监听器，不关闭 PacketEvents、不撤销玩家网络注入。不要在依赖插件运行时单独卸载 PacketEvents；需要更新它时重启服务器。当前仅以 PlugManX 3.2.1 的 Paper/Folia 路径为支持目标，完整热重载兼容性仍需真实服务器验证。

Gradle 的 `:BukkitPlugin:runServer` 与 `:BukkitPlugin:runFolia` 开发启动任务会单独下载 PacketEvents 2.13.0，不将其打入 JavaRailways JAR。


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
