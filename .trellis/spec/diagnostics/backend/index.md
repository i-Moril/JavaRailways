# Diagnostics 规范

纯 JVM 日志模块供 Bukkit 宿主、附属插件和独立 WebUI 共用，不依赖世界或 Bukkit。

- [磁盘日志读取](disk-logs.md)：固定来源、字节预算、分页快照与轮转。
- [错误写入与生命周期](../../bukkit-plugin/backend/error-logging.md)：共享 PluginErrorLog、宿主装配与异步写入合同。

修改日志读写运行 `:Diagnostics:test`、`:BukkitPlugin:test`、`:WebUI:test`，修改附属插件装配增加对应模块测试。修改打包运行 `:WebUI:shadowJar :BukkitPlugin:shadowJar` 并核验嵌入前端及独立插件依赖。
