# PlugManX 3.2.1 GentleUnload 核实

版本固定 v3.2.1 / f52c043673dd45d73d1053154af9d2d044e2338b。2026-11-11 通过 GitHub connector 读取官方源码；本地发布 JAR `/tmp/jr-merged-deploy-20261111/PlugManX-3.2.1.jar` 含相同 bukkit API class path。

- `plugman-bukkit/src/main/java/bukkit/com/rylinaux/plugman/api/GentleUnload.java`：唯一方法 `boolean askingForGentleUnload()`，无 Future、operation intent 或异步 continuation。
- `PlugManAPI.java`：`pleaseAddMeToGentleUnload(Plugin, GentleUnload)` 将 plugin/callback 放入 private static `gentleUnloads` HashMap；已有 key 返回 false；`getGentleUnloads()` 返回副本，无公开 remove API。注销不能 remove 副本；必须精确移除本插件的原始 entry，否则旧 Plugin/classloader 被强引用。
- `BasePluginManager.java:82-86`：handleGentleUnload 同步调用 callback。
- `plugman-paper/.../PaperPluginManager.java:1000-1003`：unloadWithPaper 先检查 callback；false 直接返回 gentle-failed，不进入禁用。`disable` 201-211 直接 extract，不检查 GentleUnload。ModernPaperPluginManager.unload 复用 unloadWithPaper。
- `plugman-core/.../CascadingPluginCommand.java:runPlugin`：reload 先卸载 enabled dependents；主插件 unload false 后立刻同步 loadDependents。异步准备必须在原 command 返回后的 global tick 执行，不能 inline 注销服务，否则 rollback dependent 加载时缺失服务。第二次仍由用户发起，不自行恢复或重放请求。

源码基址：https://github.com/Test-Account666/PlugManX/tree/v3.2.1 。

本次 root cause 实服日志：Folia 实际 1 tick worker（08:53:51 warning），09:30:57 PlugManX unload，09:31:03 watchdog 显示唯一 global tick worker 在 PlatformCleanup.runAndAwait，09:31:08 TimeoutException。配置 threaded-regions.threads=-1。需要非阻塞 owner barrier，不以延长 timeout 或额外后台 waiter 代替调度器释放。
